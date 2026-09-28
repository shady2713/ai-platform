import type {
  ConversationApi,
  ConversationRunApi,
  ConversationSummary,
} from '@vben/ai-chat-ui';
import type { BusinessContext, ResultBlock } from '@vben/ai-contracts';

import { createOpenApiClient } from '@vben/ai-chat-ui';
import { AiOpenApiError, parseCommonResult } from '@vben/ai-contracts';

/**
 * 组件的应用端端口（X09）：把**现有的 ChatUI 会话端口**接到平台应用端。
 *
 * <p>两条与 iframe 路径一致的硬约束：
 * <ol>
 *   <li><b>票据只在内存、只在请求头</b>：票据由页内桥（`local-port`）从 AUTH 消息里保存，
 *       这里每次请求时读取；不写 URL/storage/日志；</li>
 *   <li><b>401 只换票一次</b>：交给 `createOpenApiClient`（共享开放客户端）的既有语义，
 *       换票回调走页内桥的 TOKEN_REQUIRED（宿主侧 `HostBridge` 的 single-flight）。</li>
 * </ol>
 *
 * <p>运行受理额外携带业务上下文快照（`businessContext`，契约字段）：上下文在**受理瞬间**取值，
 * 之后的 `updateContext` 只影响下一次运行（与 `createBusinessContextStore` 同口径）。
 * 这是组件路径相对 iframe 路径多出来的能力（iframe 路径的 CONTEXT_UPDATE 发送入口仍缺失）。
 */

/** 会话接口的响应形状（O01 应用端契约，与独立 Chat 应用同一份）。 */
interface ConversationResp {
  conversationKey: string;
  id: number;
  title: string;
  updateTime?: string;
}

/** 业务数据级别：与 SDK 客户端同一默认值（服务端仍按主体权限裁剪）。 */
const DATA_LEVEL = 'L2_INTERNAL';

/** 业务键（`svc_xxx` / `run_xxx`）→ 数值编号（开放 API 的编号列）。 */
export function numericId(key: string): number {
  return Number(key.replace(/^\D+/u, ''));
}

/**
 * 会话列表端口：`/app-api/ai/conversation/*`（列表/新建/重命名/删除）。
 *
 * <p>传输层失败与非法 JSON 都归一为 `AiOpenApiError`（稳定原因码），界面只处理一种错误通道。
 */
export function createComponentConversationApi(options: {
  accessToken: () => null | string;
  baseUrl: string;
  fetchImpl?: typeof fetch;
}): ConversationApi {
  const base = options.baseUrl.replace(/\/+$/u, '');
  const doFetch = options.fetchImpl ?? globalThis.fetch;

  async function sendRequest(
    url: string,
    init: RequestInit,
  ): Promise<Response> {
    try {
      return await doFetch(url, init);
    } catch {
      throw new AiOpenApiError(
        0,
        'NETWORK_UNREACHABLE',
        `无法连接 AI 服务（${base}）：网络不可达`,
      );
    }
  }

  async function request<T>(path: string, init: RequestInit): Promise<T> {
    const headers = new Headers(init.headers);
    headers.set('Accept', 'application/json');
    if (init.body) {
      headers.set('Content-Type', 'application/json');
    }
    const token = options.accessToken();
    if (token !== null) {
      // 票据只进请求头
      headers.set('Authorization', `Bearer ${token}`);
    }
    const response = await sendRequest(`${base}${path}`, { ...init, headers });
    const contentType = response.headers.get('content-type') ?? '';
    let payload: unknown;
    if (contentType.includes('application/json')) {
      try {
        payload = await response.json();
      } catch {
        throw new AiOpenApiError(
          response.status,
          'MALFORMED_RESPONSE',
          `响应不是合法 JSON（HTTP ${response.status}）`,
        );
      }
    } else {
      payload = await response.text();
    }
    return parseCommonResult<T>(response.status, payload);
  }

  function toSummary(resp: ConversationResp): ConversationSummary {
    return {
      conversationKey: resp.conversationKey,
      id: resp.id,
      title: resp.title,
      ...(resp.updateTime === undefined ? {} : { updateTime: resp.updateTime }),
    };
  }

  return {
    async create(title?: string): Promise<ConversationSummary> {
      const created = await request<ConversationResp>(
        '/ai/conversation/create',
        {
          body: JSON.stringify(title === undefined ? {} : { title }),
          method: 'POST',
        },
      );
      return toSummary(created);
    },

    async list(): Promise<ConversationSummary[]> {
      const page = await request<{ list: ConversationResp[] }>(
        '/ai/conversation/page',
        { method: 'GET' },
      );
      return (page.list ?? []).map((item) => toSummary(item));
    },

    async remove(id: number): Promise<void> {
      await request<unknown>('/ai/conversation/delete', {
        body: JSON.stringify({ id }),
        method: 'POST',
      });
    },

    async rename(id: number, title: string): Promise<void> {
      await request<unknown>('/ai/conversation/rename', {
        body: JSON.stringify({ id, title }),
        method: 'POST',
      });
    },
  };
}

/** 运行端口：共享开放客户端 + 组件自己的票据/上下文接线。 */
export function createComponentRunApi(options: {
  accessToken: () => null | string;
  baseUrl: string;
  context: () => BusinessContext | null;
  exchangeTicket: () => Promise<null | string>;
  fetchImpl?: typeof fetch;
}): ConversationRunApi {
  const client = createOpenApiClient({
    accessToken: options.accessToken,
    baseUrl: options.baseUrl,
    exchangeTicket: options.exchangeTicket,
    ...(options.fetchImpl === undefined
      ? {}
      : { fetchImpl: options.fetchImpl }),
  });

  return {
    async cancelRun(runKey: string, version?: number): Promise<unknown> {
      return client.cancelRun(numericId(runKey), version ?? 0);
    },

    async createRun(
      request: { conversationId?: number; message: string; serviceId: string },
      idempotencyKey: string,
    ): Promise<{ runId: number; runKey: string; status: string }> {
      const serviceId = numericId(request.serviceId);
      if (!Number.isInteger(serviceId) || serviceId <= 0) {
        throw new AiOpenApiError(
          0,
          'INVALID_REQUEST',
          `服务标识不是受支持的形状：${request.serviceId}`,
        );
      }
      const context = options.context();
      const accepted = await client.acceptRun({
        dataLevel: DATA_LEVEL,
        idempotencyKey,
        message: request.message,
        serviceId,
        ...(request.conversationId === undefined
          ? {}
          : { conversationId: request.conversationId }),
        // 上下文快照只在受理瞬间取一次：之后的更新只影响下一次运行
        ...(context === null
          ? {}
          : { businessContext: JSON.stringify(context) }),
      });
      return {
        runId: accepted.runId,
        runKey: accepted.runKey,
        status: accepted.status,
      };
    },

    async streamRunEvents(
      runKey: string,
      handlers: {
        afterSeq?: number;
        onEvent: (event: {
          block?: ResultBlock;
          seq: number;
          status: string;
        }) => void;
      },
    ): Promise<{ lastSeq: number; reason: string }> {
      const result = await client.streamRunEvents(numericId(runKey), {
        onEvent: (event) => handlers.onEvent(event),
        ...(handlers.afterSeq === undefined
          ? {}
          : { afterSeq: handlers.afterSeq }),
      });
      return { lastSeq: result.lastSeq, reason: result.reason };
    },
  };
}

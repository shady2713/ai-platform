import type { ConversationApi, ConversationSummary } from '@vben/ai-chat-ui';

import type { TicketStorage } from './client-factory';

import { AiChatApiError } from '@vben/ai-embed-sdk';

/**
 * 会话接口端口实现（C02）：独立 Chat 应用走**应用端**会话接口（O01 契约）。
 *
 * <p>为什么单独一层：会话 composable 只依赖端口（可单测、可换宿主），
 * 具体请求与票据读取留在应用侧；错误统一走 `parseCommonResult`（与开放客户端同一口径），
 * 传输层失败与非法 JSON 也在本层归一为同一错误类型与稳定原因码。
 */
export interface ConversationApiOptions {
  /** 应用端基址，例如 https://host/app-api */
  baseUrl: string;
  fetchImpl?: typeof fetch;
  ticketStorage?: Pick<Storage, 'getItem'> | TicketStorage | undefined;
}

interface ConversationResp {
  conversationKey: string;
  id: number;
  title: string;
  updateTime?: string;
}

export function createConversationApi(
  options: ConversationApiOptions,
): ConversationApi {
  const base = options.baseUrl.replace(/\/+$/, '');
  const doFetch = options.fetchImpl ?? globalThis.fetch;

  /**
   * 发出请求：传输层失败（连接被拒/DNS/断网）没有 HTTP 状态码，
   * 统一转成 `AiChatApiError`（`status = 0`、`code = NETWORK_UNREACHABLE`）。
   *
   * <p>为什么不在调用点兜底：界面与日志需要**稳定原因**而不是运行时的原始
   * `TypeError: Failed to fetch`；错误类型与信封错误一致，消费者只处理一种错误通道。
   */
  async function sendRequest(
    url: string,
    init: RequestInit,
  ): Promise<Response> {
    try {
      return await doFetch(url, init);
    } catch (error) {
      if (error instanceof AiChatApiError) {
        throw error;
      }
      throw new AiChatApiError(
        0,
        'NETWORK_UNREACHABLE',
        `无法连接 AI 服务（${base}）：网络不可达`,
      );
    }
  }

  /**
   * 读取 JSON 正文：正文不是合法 JSON 时同样给出稳定错误，
   * 不回显原始正文（错误页可能是 HTML，界面只显示状态码与原因码）。
   */
  async function readJsonBody(response: Response): Promise<unknown> {
    try {
      return await response.json();
    } catch {
      throw new AiChatApiError(
        response.status,
        'MALFORMED_RESPONSE',
        `响应不是合法 JSON（HTTP ${response.status}）`,
      );
    }
  }

  async function request<T>(path: string, init: RequestInit): Promise<T> {
    const headers = new Headers(init.headers);
    headers.set('Accept', 'application/json');
    if (init.body) {
      headers.set('Content-Type', 'application/json');
    }
    // 票据只进请求头：URL 与请求体里不出现
    const token = options.ticketStorage?.getItem('ai_access_ticket') ?? null;
    if (token) {
      headers.set('Authorization', `Bearer ${token}`);
    }
    const response = await sendRequest(`${base}${path}`, { ...init, headers });
    const contentType = response.headers.get('content-type') ?? '';
    const payload = contentType.includes('application/json')
      ? await readJsonBody(response)
      : await response.text();
    return parseEnvelope<T>(response.status, payload);
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
          body: JSON.stringify(title ? { title } : {}),
          method: 'POST',
        },
      );
      return toSummary(created);
    },

    async list(): Promise<ConversationSummary[]> {
      const page = await request<{ list: ConversationResp[] }>(
        '/ai/conversation/page',
        {
          method: 'GET',
        },
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

/**
 * 解析 CommonResult 信封（与共享协议层同一口径）。
 *
 * <p>为什么在本文件内联：本应用只依赖 `@vben/ai-embed-sdk`（不直接依赖 `@vben/ai-contracts`），
 * 错误类型复用 SDK 导出的 `AiChatApiError`（即共享协议层的稳定错误）。
 */
function parseEnvelope<T>(status: number, payload: unknown): T {
  const envelope =
    typeof payload === 'object' && payload !== null
      ? (payload as { code?: number; data?: T; msg?: string })
      : {};
  const code = typeof envelope.code === 'number' ? envelope.code : null;
  if (status < 200 || status >= 300) {
    throw new AiChatApiError(
      status,
      code === null || code === 0 ? `HTTP_${status}` : String(code),
      envelope.msg ?? `请求失败：HTTP ${status}`,
    );
  }
  if (code !== 0) {
    throw new AiChatApiError(
      status,
      code === null ? 'MALFORMED_RESPONSE' : String(code),
      envelope.msg ?? '响应缺少 CommonResult 信封',
    );
  }
  if (envelope.data === undefined) {
    throw new AiChatApiError(status, 'EMPTY_DATA', '响应缺少 data');
  }
  return envelope.data;
}

export { AiChatApiError };

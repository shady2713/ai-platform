import type { RunEvent } from '@vben/ai-contracts';

import type { CreateRunRequest, RunAccepted, RunSnapshot } from './types';

import {
  AiOpenApiError,
  createSeqTracker,
  createSseByteParser,
  parseCommonResult,
  parseRunEvent,
} from '@vben/ai-contracts';

/**
 * 宿主 SDK 客户端（C01）：在**共享协议层**（`@vben/ai-contracts` 的信封/SSE/seq 口径）之上
 * 提供受理、查询、取消与事件流。
 *
 * <p>为什么与 `@vben/ai-chat-ui` 的共享客户端并存：本包只依赖 `@vben/ai-contracts`（不依赖 Vue 组件包），
 * 因此复用同一份**协议解析**（`parseCommonResult` / `createSseByteParser` / `createSeqTracker`），
 * 传输层各自实现（宿主可替换 fetch）。C06 若让 SDK 依赖 chat-ui 客户端，可把传输层也收敛为一处。
 *
 * <p>三条硬约束与共享客户端一致：票据只在内存与请求头；401 由宿主回调换票（不复用管理端 Cookie）；
 * 断线按 `afterSeq` 重连、窗口过期读快照，绝不重新发起运行。
 */

/** 兼容旧名字：错误类型就是共享协议层的稳定错误。 */
export const AiChatApiError = AiOpenApiError;

export interface AiChatClientOptions {
  /** 可选 Bearer 票据提供者；宿主负责换票，SDK 不接触凭据存储 */
  accessToken?: () => null | string;
  /** 平台开放 API 基址，例如 https://host/app-api */
  baseUrl: string;
  /** 401 时的换票回调；至多调用一次（不复用管理端 Cookie 刷新逻辑） */
  exchangeTicket?: () => Promise<null | string>;
  /** 可注入的 fetch，便于宿主在受限环境替换实现与测试 */
  fetchImpl?: typeof fetch;
}

export interface RunEventHandlers {
  afterSeq?: number;
  onEvent: (event: RunEvent) => void;
  onHeartbeat?: (comment: string) => void;
  signal?: AbortSignal;
}

export interface RunEventStreamResult {
  lastSeq: number;
  reason: 'closed' | 'snapshot' | 'terminal';
  snapshot?: RunSnapshot;
}

export interface AiChatClient {
  cancelRun(runId: string, version?: number): Promise<RunSnapshot>;
  createRun(
    request: CreateRunRequest,
    idempotencyKey: string,
  ): Promise<RunAccepted>;
  getRun(runId: string): Promise<RunSnapshot>;
  streamRunEvents(
    runId: string,
    handlers: RunEventHandlers,
  ): Promise<RunEventStreamResult>;
}

const TERMINAL_STATUSES = new Set(['CANCELLED', 'FAILED', 'SUCCEEDED']);

/** 重放窗口过期（服务端稳定错误码）：改为读取快照。 */
const RUN_EVENT_WINDOW_EXPIRED = '1003004009';

/** 业务键（`svc_xxx` / `run_xxx`）→ 数值编号（开放 API 的编号列）。 */
function numericId(key: string): number {
  return Number(key.replace(/^\D+/, ''));
}

/**
 * 创建开放 API 客户端。
 *
 * <p>受理请求必须带调用方生成的幂等键（重试用同一键即安全）；输入在本地先校验再发请求。
 */
export function createAiChatClient(options: AiChatClientOptions): AiChatClient {
  const base = options.baseUrl.replace(/\/+$/, '');
  const doFetch = options.fetchImpl ?? globalThis.fetch;

  function authHeaders(): Record<string, string> {
    const token = options.accessToken?.() ?? null;
    // 票据只进请求头：URL 与请求体里不出现
    return token ? { Authorization: `Bearer ${token}` } : {};
  }

  async function readEnvelope<T>(response: Response): Promise<T> {
    const contentType = response.headers.get('content-type') ?? '';
    const payload = contentType.includes('application/json')
      ? await response.json()
      : await response.text();
    return parseCommonResult<T>(response.status, payload);
  }

  async function request<T>(
    path: string,
    init: RequestInit,
    allowExchange = true,
  ): Promise<T> {
    const headers = new Headers(init.headers);
    headers.set('Accept', 'application/json');
    if (init.body) {
      headers.set('Content-Type', 'application/json');
    }
    for (const [key, value] of Object.entries(authHeaders())) {
      headers.set(key, value);
    }
    const response = await doFetch(`${base}${path}`, { ...init, headers });
    if (response.status === 401 && allowExchange && options.exchangeTicket) {
      // 只换一次票、只重试一次（不做 Cookie 续期）
      const token = await options.exchangeTicket();
      if (token) {
        return request<T>(path, init, false);
      }
    }
    return readEnvelope<T>(response);
  }

  return {
    async createRun(
      request_: CreateRunRequest,
      idempotencyKey: string,
    ): Promise<RunAccepted> {
      // 校验失败以 rejection 形式返回，调用方只需处理一种错误通道
      if (!request_.serviceId.trim() || !request_.message.trim()) {
        throw new AiOpenApiError(
          0,
          'INVALID_REQUEST',
          'serviceId 与 message 不能为空',
        );
      }
      if (idempotencyKey.length < 16 || idempotencyKey.length > 128) {
        throw new AiOpenApiError(
          0,
          'INVALID_IDEMPOTENCY_KEY',
          '幂等键长度必须在 16..128 之间',
        );
      }
      return request<RunAccepted>('/ai/run/accept', {
        body: JSON.stringify({
          conversationId: request_.conversationId,
          dataLevel: 'L2_INTERNAL',
          idempotencyKey,
          message: request_.message,
          serviceId: numericId(request_.serviceId),
        }),
        headers: { 'Idempotency-Key': idempotencyKey },
        method: 'POST',
      });
    },

    cancelRun(runId: string, version = 0): Promise<RunSnapshot> {
      return request<RunSnapshot>('/ai/run/cancel', {
        body: JSON.stringify({ runId: numericId(runId), version }),
        method: 'POST',
      });
    },

    getRun(runId: string): Promise<RunSnapshot> {
      return request<RunSnapshot>(`/ai/run/get?id=${numericId(runId)}`, {
        method: 'GET',
      });
    },

    async streamRunEvents(
      runId: string,
      handlers: RunEventHandlers,
    ): Promise<RunEventStreamResult> {
      const tracker = createSeqTracker(handlers.afterSeq ?? 0);
      const query = new URLSearchParams({ runId: String(numericId(runId)) });
      if ((handlers.afterSeq ?? 0) > 0) {
        query.set('afterSeq', String(handlers.afterSeq));
      }
      const headers = new Headers({ Accept: 'text/event-stream' });
      for (const [key, value] of Object.entries(authHeaders())) {
        headers.set(key, value);
      }
      const response = await doFetch(
        `${base}/ai/run/events?${query.toString()}`,
        {
          headers,
          method: 'GET',
          ...(handlers.signal ? { signal: handlers.signal } : {}),
        },
      );
      if (!response.ok) {
        const contentType = response.headers.get('content-type') ?? '';
        const payload = contentType.includes('application/json')
          ? await response.json()
          : await response.text();
        let failure: AiOpenApiError;
        try {
          parseCommonResult<never>(response.status, payload);
          failure = new AiOpenApiError(
            response.status,
            `HTTP_${response.status}`,
            '事件流不可用',
          );
        } catch (error) {
          // 契约层保证：parseCommonResult 对非 2xx 只抛 AiOpenApiError
          failure = error as AiOpenApiError;
        }
        if (failure.code === RUN_EVENT_WINDOW_EXPIRED) {
          // 重放窗口过期：读取快照（不是重新发起运行）
          return {
            lastSeq: tracker.lastSeq(),
            reason: 'snapshot',
            snapshot: await request<RunSnapshot>(
              `/ai/run/get?id=${numericId(runId)}`,
              {
                method: 'GET',
              },
            ),
          };
        }
        throw failure;
      }
      const body = response.body;
      if (!body) {
        throw new AiOpenApiError(
          response.status,
          'EMPTY_STREAM',
          '事件流没有响应体',
        );
      }
      const reader = body.getReader();
      const parser = createSseByteParser();
      let terminal = false;
      for (;;) {
        const { done, value } = await reader.read();
        const frames = done ? parser.flush() : parser.push(value);
        for (const frame of frames) {
          for (const comment of frame.comments) {
            handlers.onHeartbeat?.(comment);
          }
          if (!frame.data) {
            continue;
          }
          const event = parseRunEvent(JSON.parse(frame.data));
          if (!tracker.accept(event.seq)) {
            continue;
          }
          handlers.onEvent(event);
          if (TERMINAL_STATUSES.has(event.status)) {
            terminal = true;
          }
        }
        if (done || terminal) {
          break;
        }
      }
      return {
        lastSeq: tracker.lastSeq(),
        reason: terminal ? 'terminal' : 'closed',
      };
    },
  };
}

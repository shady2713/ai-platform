import type { RunEvent, RunStatus } from '@vben/ai-contracts';

import {
  AiOpenApiError,
  createSeqTracker,
  createSseByteParser,
  invalidRequest,
  parseCommonResult,
  parseRunEvent,
} from '@vben/ai-contracts';

/**
 * 共享开放客户端（C01）：Bearer **内存票据** + CommonResult/真实 HTTP 错误 + fetch SSE 解析。
 *
 * <p>三条硬约束：
 * <ol>
 *   <li><b>票据只在内存、只在请求头</b>：由宿主的 `accessToken()` 提供，绝不写入 URL、
 *       storage 或日志；本模块不接触应用客户端凭据；</li>
 *   <li><b>不复用管理端 Cookie 刷新逻辑</b>：401 只调用宿主给的 `exchangeTicket()` **一次**并重试一次，
 *       再失败即抛错（不做 Cookie 续期、不做静默重试链）；</li>
 *   <li><b>断线重连不重执行</b>：流按 `seq` 去重（重复/乱序丢弃），重连用 `afterSeq=lastSeq`；
 *       重放窗口过期时读取快照（`getRun`）而不是重新发起运行。</li>
 * </ol>
 */

/** 幂等键长度约束来自开放 API 契约（16..128）。 */
const IDEMPOTENCY_KEY_MIN_LENGTH = 16;
const IDEMPOTENCY_KEY_MAX_LENGTH = 128;
const MESSAGE_MAX_LENGTH = 16_000;

/** 终态集合：到达终态即停止订阅（不重执行）。 */
const TERMINAL_STATUSES = new Set<RunStatus>([
  'CANCELLED',
  'FAILED',
  'SUCCEEDED',
]);

/** 重放窗口过期（服务端稳定错误码）：改为读取快照。 */
export const RUN_EVENT_WINDOW_EXPIRED = '1003004009';

export interface OpenApiClientOptions {
  /** 内存票据提供者（宿主负责换票与失效；本模块不持久化任何令牌） */
  accessToken?: () => null | string;
  /** 开放 API 基址，例如 `https://host/app-api`（应用端通道） */
  baseUrl: string;
  /** 401 时的换票回调；**至多调用一次**（不复用管理端 Cookie 刷新逻辑） */
  exchangeTicket?: () => Promise<null | string>;
  /** 可注入的 fetch（受限环境替换实现与测试用） */
  fetchImpl?: typeof fetch;
}

/** 受理运行请求（字段与开放 API 契约一致）。 */
export interface RunAcceptRequest {
  attachmentKeys?: string[];
  businessContext?: string;
  conversationId?: null | number;
  dataLevel: string;
  idempotencyKey: string;
  message: string;
  serviceId: number;
}

/** 受理结果（`reused=true` 表示命中幂等，复用首次受理的运行）。 */
export interface RunAccepted {
  releaseId?: number;
  releaseVersion?: number;
  reused: boolean;
  runId: number;
  runKey: string;
  status: string;
}

/** 运行快照（重连/窗口过期时使用）。 */
export interface RunSnapshot {
  id: number;
  runKey?: string;
  status: string;
  version?: number;
}

/** 事件流结束原因：终态 / 连接关闭（可重连）/ 已改为读取快照。 */
export type StreamEndReason = 'closed' | 'snapshot' | 'terminal';

export interface RunEventStreamHandlers {
  /** 起始序号（重连时传上次的 lastSeq；服务端只补发更大的 seq） */
  afterSeq?: number;
  /** 已去重的事件 */
  onEvent: (event: RunEvent) => void;
  /** 心跳注释（不是事件） */
  onHeartbeat?: (comment: string) => void;
  /** 中止信号 */
  signal?: AbortSignal;
}

export interface RunEventStreamResult {
  /** 已确认的最大序号（重连时作为 afterSeq） */
  lastSeq: number;
  /** 结束原因 */
  reason: StreamEndReason;
  /** 结束时的快照（reason=snapshot 时有值） */
  snapshot?: RunSnapshot;
}

export interface OpenApiClient {
  acceptRun(request: RunAcceptRequest): Promise<RunAccepted>;
  cancelRun(runId: number, version: number): Promise<RunSnapshot>;
  getRun(runId: number): Promise<RunSnapshot>;
  pageRuns(params?: Record<string, number | string>): Promise<unknown>;
  streamRunEvents(
    runId: number,
    handlers: RunEventStreamHandlers,
  ): Promise<RunEventStreamResult>;
}

function assertAcceptRequest(request: RunAcceptRequest): void {
  if (!Number.isInteger(request.serviceId) || request.serviceId <= 0) {
    throw invalidRequest('serviceId 必须是正整数');
  }
  const message = request.message.trim();
  if (message.length === 0 || message.length > MESSAGE_MAX_LENGTH) {
    throw invalidRequest(`message 长度必须在 1..${MESSAGE_MAX_LENGTH} 之间`);
  }
  if (
    request.idempotencyKey.length < IDEMPOTENCY_KEY_MIN_LENGTH ||
    request.idempotencyKey.length > IDEMPOTENCY_KEY_MAX_LENGTH
  ) {
    throw invalidRequest(
      `idempotencyKey 长度必须在 ${IDEMPOTENCY_KEY_MIN_LENGTH}..${IDEMPOTENCY_KEY_MAX_LENGTH} 之间`,
    );
  }
  if (!request.dataLevel.trim()) {
    throw invalidRequest('dataLevel 不能为空');
  }
}

/**
 * 创建共享开放客户端。
 *
 * <p>只实现开放 API 的运行相关端点（受理/查询/取消/分页/事件流）；本地先校验再发请求，
 * 重试策略由调用方决定（受理用同一幂等键即可安全重试）。
 */
export function createOpenApiClient(
  options: OpenApiClientOptions,
): OpenApiClient {
  const base = options.baseUrl.replace(/\/+$/, '');
  const doFetch = options.fetchImpl ?? globalThis.fetch;

  function authHeaders(): Record<string, string> {
    const token = options.accessToken?.() ?? null;
    // 票据只进请求头：URL 与 storage 里永远不出现令牌
    return token ? { Authorization: `Bearer ${token}` } : {};
  }

  async function readEnvelope<T>(response: Response): Promise<T> {
    const contentType = response.headers.get('content-type') ?? '';
    const payload = contentType.includes('application/json')
      ? await response.json()
      : await response.text();
    return parseCommonResult<T>(response.status, payload);
  }

  /** 带"401 只换票一次"的请求。 */
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
      // 只换一次票、只重试一次；再 401 就是真的没权限（不做 Cookie 续期）
      const token = await options.exchangeTicket();
      if (token) {
        return request<T>(path, init, false);
      }
    }
    return readEnvelope<T>(response);
  }

  return {
    async acceptRun(request_: RunAcceptRequest): Promise<RunAccepted> {
      // 校验失败以 rejection 形式返回：调用方只需处理一种错误通道
      assertAcceptRequest(request_);
      return request<RunAccepted>('/ai/run/accept', {
        body: JSON.stringify(request_),
        headers: { 'Idempotency-Key': request_.idempotencyKey },
        method: 'POST',
      });
    },

    cancelRun(runId: number, version: number): Promise<RunSnapshot> {
      return request<RunSnapshot>('/ai/run/cancel', {
        body: JSON.stringify({ runId, version }),
        method: 'POST',
      });
    },

    getRun(runId: number): Promise<RunSnapshot> {
      return request<RunSnapshot>(
        `/ai/run/get?id=${encodeURIComponent(runId)}`,
        {
          method: 'GET',
        },
      );
    },

    pageRuns(params: Record<string, number | string> = {}): Promise<unknown> {
      const query = new URLSearchParams(
        Object.entries(params).map(([key, value]) => [key, String(value)]),
      ).toString();
      return request<unknown>(`/ai/run/page${query ? `?${query}` : ''}`, {
        method: 'GET',
      });
    },

    async streamRunEvents(
      runId: number,
      handlers: RunEventStreamHandlers,
    ): Promise<RunEventStreamResult> {
      const tracker = createSeqTracker(handlers.afterSeq ?? 0);
      const query = new URLSearchParams({ runId: String(runId) });
      if ((handlers.afterSeq ?? 0) > 0) {
        query.set('afterSeq', String(handlers.afterSeq));
      }
      const headers = new Headers({ Accept: 'text/event-stream' });
      for (const [key, value] of Object.entries(authHeaders())) {
        headers.set(key, value);
      }
      let response: Response;
      try {
        response = await doFetch(`${base}/ai/run/events?${query.toString()}`, {
          headers,
          method: 'GET',
          ...(handlers.signal ? { signal: handlers.signal } : {}),
        });
      } catch (error) {
        // 网络层中断（断线）：交给调用方按 lastSeq 重连，不在这里重执行
        if (error instanceof Error && error.name === 'AbortError') {
          return { lastSeq: tracker.lastSeq(), reason: 'closed' };
        }
        throw error;
      }
      if (!response.ok) {
        try {
          await readEnvelope<unknown>(response);
        } catch (error) {
          if (
            error instanceof AiOpenApiError &&
            error.code === RUN_EVENT_WINDOW_EXPIRED
          ) {
            // 重放窗口过期：读取快照（不是重新发起运行）
            const snapshot = await request<RunSnapshot>(
              `/ai/run/get?id=${encodeURIComponent(runId)}`,
              { method: 'GET' },
            );
            return { lastSeq: tracker.lastSeq(), reason: 'snapshot', snapshot };
          }
          throw error;
        }
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
      try {
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
      } finally {
        if (terminal) {
          await reader.cancel().catch(() => undefined);
        }
      }
      return {
        lastSeq: tracker.lastSeq(),
        reason: terminal ? 'terminal' : 'closed',
      };
    },
  };
}

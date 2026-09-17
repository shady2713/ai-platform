import type { CreateRunRequest, RunAccepted, RunSnapshot } from './types';

/** 幂等键长度约束来自开放 API 契约（16..128）。 */
const IDEMPOTENCY_KEY_MIN_LENGTH = 16;
const IDEMPOTENCY_KEY_MAX_LENGTH = 128;
const MESSAGE_MAX_LENGTH = 16_000;

/** 稳定错误类型：调用方按 status/code 分支，不需要解析文案。 */
export class AiChatApiError extends Error {
  readonly code: string;

  readonly status: number;

  constructor(status: number, code: string, message: string) {
    super(message);
    this.name = 'AiChatApiError';
    this.status = status;
    this.code = code;
  }
}

export interface AiChatClientOptions {
  /** 可选 Bearer 票据提供者；宿主负责换票，SDK 不接触凭据存储 */
  accessToken?: () => null | string;
  /** 平台开放 API 基址，例如 https://host/app-api/ai/v1 */
  baseUrl: string;
  /** 可注入的 fetch，便于宿主在受限环境替换实现与测试 */
  fetchImpl?: typeof fetch;
}

interface ResponseEnvelope<T> {
  code: number;
  data?: T;
  msg?: string;
}

export interface AiChatClient {
  cancelRun(runId: string): Promise<RunSnapshot>;
  createRun(
    request: CreateRunRequest,
    idempotencyKey: string,
  ): Promise<RunAccepted>;
  getRun(runId: string): Promise<RunSnapshot>;
}

function assertCreateRunRequest(request: CreateRunRequest): void {
  if (!request.serviceId.trim()) {
    throw new AiChatApiError(0, 'INVALID_REQUEST', 'serviceId 不能为空');
  }
  const message = request.message.trim();
  if (message.length === 0 || message.length > MESSAGE_MAX_LENGTH) {
    throw new AiChatApiError(
      0,
      'INVALID_REQUEST',
      `message 长度必须在 1..${MESSAGE_MAX_LENGTH} 之间`,
    );
  }
}

function assertIdempotencyKey(idempotencyKey: string): void {
  if (
    idempotencyKey.length < IDEMPOTENCY_KEY_MIN_LENGTH ||
    idempotencyKey.length > IDEMPOTENCY_KEY_MAX_LENGTH
  ) {
    throw new AiChatApiError(
      0,
      'INVALID_IDEMPOTENCY_KEY',
      `幂等键长度必须在 ${IDEMPOTENCY_KEY_MIN_LENGTH}..${IDEMPOTENCY_KEY_MAX_LENGTH} 之间`,
    );
  }
}

/**
 * 创建开放 API 客户端。
 *
 * <p>只实现契约中的三个端点（受理/查询/取消），输入在本地先校验再发请求；
 * 不使用全局 fetch 之外的隐式状态，不做重试（重试由调用方使用同一幂等键决定）。
 */
export function createAiChatClient(options: AiChatClientOptions): AiChatClient {
  const base = options.baseUrl.replace(/\/+$/, '');
  const doFetch = options.fetchImpl ?? globalThis.fetch;

  async function request<T>(path: string, init: RequestInit): Promise<T> {
    const headers = new Headers(init.headers);
    headers.set('Accept', 'application/json');
    if (init.body) {
      headers.set('Content-Type', 'application/json');
    }
    const token = options.accessToken?.() ?? null;
    if (token) {
      headers.set('Authorization', `Bearer ${token}`);
    }
    const response = await doFetch(`${base}${path}`, { ...init, headers });
    const payload = (await response.json()) as ResponseEnvelope<T>;
    if (!response.ok || payload.code !== 0) {
      throw new AiChatApiError(
        response.status,
        payload.code === 0 ? 'HTTP_ERROR' : String(payload.code),
        payload.msg ?? `请求失败：HTTP ${response.status}`,
      );
    }
    if (payload.data === undefined) {
      throw new AiChatApiError(response.status, 'EMPTY_DATA', '响应缺少 data');
    }
    return payload.data;
  }

  return {
    cancelRun(runId: string): Promise<RunSnapshot> {
      return request<RunSnapshot>(`/runs/${encodeURIComponent(runId)}/cancel`, {
        method: 'POST',
      });
    },
    async createRun(
      request_: CreateRunRequest,
      idempotencyKey: string,
    ): Promise<RunAccepted> {
      // 校验失败以 rejection 形式返回，调用方只需处理一种错误通道
      assertCreateRunRequest(request_);
      assertIdempotencyKey(idempotencyKey);
      return request<RunAccepted>('/runs', {
        body: JSON.stringify(request_),
        headers: { 'Idempotency-Key': idempotencyKey },
        method: 'POST',
      });
    },
    getRun(runId: string): Promise<RunSnapshot> {
      return request<RunSnapshot>(`/runs/${encodeURIComponent(runId)}`, {
        method: 'GET',
      });
    },
  };
}

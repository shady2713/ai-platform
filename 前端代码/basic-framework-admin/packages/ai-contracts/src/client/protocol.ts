/**
 * 开放 API 响应契约（C01）：CommonResult 信封 + 稳定错误类型 + seq 去重。
 *
 * <p>为什么把这三样放在契约包：它们是**协议语义**（不是某个界面的实现）——
 * 服务端 `CommonResult{code,data,msg}`、`seq` 单调去重、错误按 `status/code` 分支，
 * 共享客户端与宿主 SDK 必须用同一份口径，否则"同一次运行"在两边会有两种解释。
 */

/** 稳定错误：调用方按 status/code 分支，不解析文案。 */
export class AiOpenApiError extends Error {
  /** 业务错误码（服务端 CommonResult.code；无信封时为 HTTP_<status>） */
  readonly code: string;

  /** HTTP 状态码（本地校验失败为 0） */
  readonly status: number;

  constructor(status: number, code: string, message: string) {
    super(message);
    this.name = 'AiOpenApiError';
    this.status = status;
    this.code = code;
  }
}

/** 本地入参校验失败（不发请求）。 */
export function invalidRequest(message: string): AiOpenApiError {
  return new AiOpenApiError(0, 'INVALID_REQUEST', message);
}

/** CommonResult 信封。 */
export interface CommonResultEnvelope<T> {
  code?: number;
  data?: T;
  msg?: string;
}

/**
 * 解析 CommonResult 信封：
 * <ul>
 *   <li>HTTP 非 2xx → 抛错（有信封用信封的 code/msg，没有则用 `HTTP_<status>`）；</li>
 *   <li>`code !== 0` → 抛错（业务失败不能当成功）；</li>
 *   <li>缺少 `data` → 抛错（成功响应必须带数据，不返回 undefined 让调用方猜）。</li>
 * </ul>
 */
export function parseCommonResult<T>(status: number, payload: unknown): T {
  const envelope =
    typeof payload === 'object' && payload !== null
      ? (payload as CommonResultEnvelope<T>)
      : ({} as CommonResultEnvelope<T>);
  const code = typeof envelope.code === 'number' ? envelope.code : null;
  if (status < 200 || status >= 300) {
    throw new AiOpenApiError(
      status,
      code === null || code === 0 ? `HTTP_${status}` : String(code),
      envelope.msg ?? `请求失败：HTTP ${status}`,
    );
  }
  if (code !== 0) {
    throw new AiOpenApiError(
      status,
      code === null ? 'MALFORMED_RESPONSE' : String(code),
      envelope.msg ?? '响应缺少 CommonResult 信封',
    );
  }
  if (envelope.data === undefined) {
    throw new AiOpenApiError(status, 'EMPTY_DATA', '响应缺少 data');
  }
  return envelope.data;
}

/** seq 去重器：只接受严格递增的序号（重复/乱序事件直接丢弃，不重放、不重执行）。 */
export interface SeqTracker {
  /** 是否接受该序号（接受后 lastSeq 前移）。 */
  accept(seq: number): boolean;
  /** 已确认的最大序号（重连时作为 afterSeq）。 */
  lastSeq(): number;
}

/** 创建 seq 去重器（初始序号用于重连后续接）。 */
export function createSeqTracker(initialSeq = 0): SeqTracker {
  let last = initialSeq;
  return {
    accept: (seq: number) => {
      if (!Number.isInteger(seq) || seq <= last) {
        return false;
      }
      last = seq;
      return true;
    },
    lastSeq: () => last,
  };
}

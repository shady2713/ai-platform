import type { AiWebhookApi } from './api';

/** Webhook 投递页权限码（与 V88 迁移的 system_menu 种子一一对应） */
export const AI_WEBHOOK_PERMISSIONS = {
  delete: 'ai:webhook:delete',
  manage: 'ai:webhook:manage',
  query: 'ai:webhook:query',
  redeliver: 'ai:webhook:redeliver',
  rotate: 'ai:webhook:rotate',
} as const;

/** 事件白名单（与平台 `AiWebhookEventTypes` 一致：只支持运行终态三种事件） */
export const WEBHOOK_EVENT_OPTIONS = [
  { label: '运行成功', value: 'RUN.SUCCEEDED' },
  { label: '运行失败', value: 'RUN.FAILED' },
  { label: '运行已取消', value: 'RUN.CANCELLED' },
] as const;

/** 投递状态选项 */
export const DELIVERY_STATUS_OPTIONS = [
  { label: '待投递', value: 'PENDING' },
  { label: '投递中', value: 'RUNNING' },
  { label: '已送达', value: 'SUCCEEDED' },
  { label: '死信（可人工重投）', value: 'FAILED' },
] as const;

/** 目标状态选项 */
export const TARGET_STATUS_OPTIONS = [
  { label: '启用', value: 'ENABLED' },
  { label: '停用（停发）', value: 'DISABLED' },
] as const;

/** 尝试结论说明 */
const ATTEMPT_OUTCOME_LABELS: Record<string, string> = {
  DELIVERED: '已送达',
  PERMANENT: '确定失败',
  RETRYABLE: '可重试',
};

/**
 * 稳定失败码 → 中文说明（与 `docs/contracts/ai/error-code-map.md` 的 X10 词表一致）。
 * 未知码原样返回，不猜测语义。
 */
const FAILURE_CODE_LABELS: Record<string, string> = {
  '1_003_011_000': '目标不存在（已删除）',
  '1_003_011_001': '目标已停用（停发）',
  '1_003_011_006': '重试预算已耗尽（死信）',
  'connect-failed': '连接失败',
  'http-client-error': '接收端拒绝（4xx，含重复投递）',
  'http-rate-limited': '接收端限流（429）',
  'http-server-error': '接收端错误（5xx）',
  'internal-error': '平台内部错误',
  'private-target-denied': '私网目标未批准',
  'redirect-not-followed': '重定向未跟随（需重新登记地址）',
  'request-invalid': '请求不合规',
  'response-too-large': '响应体超限',
  'signing-key-unavailable': '签名密钥不可用',
  'target-not-allowed': '目标不在出站允许清单',
  timeout: '超时',
};

/** 死信判定：只有 FAILED 可以人工重投（与服务端同一判据） */
export function isDeadLetter(status: string | undefined): boolean {
  return status === 'FAILED';
}

/** 可人工重投判定 */
export function canRedeliver(status: string | undefined): boolean {
  return isDeadLetter(status);
}

/** 投递状态文案 */
export function describeDeliveryStatus(status: string | undefined): string {
  return (
    DELIVERY_STATUS_OPTIONS.find((item) => item.value === status)?.label ??
    status ??
    '未知'
  );
}

/** 目标状态文案（停用即停发） */
export function describeTargetStatus(status: string | undefined): string {
  return (
    TARGET_STATUS_OPTIONS.find((item) => item.value === status)?.label ??
    status ??
    '未知'
  );
}

/** 尝试结论文案 */
export function describeAttemptOutcome(outcome: string | undefined): string {
  return ATTEMPT_OUTCOME_LABELS[outcome ?? ''] ?? outcome ?? '未知';
}

/** 稳定失败码文案 */
export function describeFailureCode(code: string | undefined): string {
  if (!code) {
    return '—';
  }
  return FAILURE_CODE_LABELS[code] ?? code;
}

/** 事件类型文案 */
export function describeEventType(eventType: string | undefined): string {
  return (
    WEBHOOK_EVENT_OPTIONS.find((item) => item.value === eventType)?.label ??
    eventType ??
    '未知'
  );
}

/** 投递计数（按状态汇总；死信单列） */
export function summarizeDeliveries(rows: AiWebhookApi.Delivery[]) {
  return {
    deadLetter: rows.filter((row) => isDeadLetter(row.status)).length,
    delivered: rows.filter((row) => row.status === 'SUCCEEDED').length,
    pending: rows.filter((row) => row.status === 'PENDING').length,
    running: rows.filter((row) => row.status === 'RUNNING').length,
    total: rows.length,
  };
}

/**
 * 投递地址形状校验（与后端登记期规则一致）：
 * 只接受 http/https、必须有主机、**拒绝 URL 里的凭据信息**（`user:pass@` 会把密钥写进日志）。
 * 是否真的可出站由受控出站边界判定，页面不复制允许清单（避免两份真值）。
 */
export function validateTargetUrl(targetUrl: string | undefined): string {
  const value = (targetUrl ?? '').trim();
  if (value.length === 0) {
    return '投递地址不能为空';
  }
  if (value.length > 1024) {
    return '投递地址超过 1024 字符上限';
  }
  let parsed: URL;
  try {
    parsed = new URL(value);
  } catch {
    return '投递地址必须是绝对的 http/https 地址';
  }
  if (parsed.protocol !== 'http:' && parsed.protocol !== 'https:') {
    return '投递地址只支持 http/https';
  }
  if (!parsed.hostname) {
    return '投递地址缺少主机名';
  }
  if (parsed.username || parsed.password) {
    return '投递地址不能包含凭据信息（user:pass@）';
  }
  return '';
}

/** 事件白名单校验（至少一个，且都在白名单内） */
export function validateEventTypes(eventTypes: string[] | undefined): string {
  if (!eventTypes || eventTypes.length === 0) {
    return '至少订阅一个事件';
  }
  const supported = new Set<string>(
    WEBHOOK_EVENT_OPTIONS.map((item) => item.value),
  );
  return eventTypes.every((item) => supported.has(item))
    ? ''
    : '事件类型不在白名单内';
}

/** 签名密钥校验（16-128 位；与服务端写入前校验一致） */
export function validateSecret(secret: string | undefined): string {
  const value = (secret ?? '').trim();
  if (value.length < 16) {
    return '签名密钥至少 16 位';
  }
  if (value.length > 128) {
    return '签名密钥最多 128 位';
  }
  return '';
}

/** 尝试上限校验（1-10；有界重试） */
export function validateMaxAttempts(maxAttempts: number | undefined): string {
  if (maxAttempts === undefined || !Number.isInteger(maxAttempts)) {
    return '尝试次数必须是整数';
  }
  return maxAttempts >= 1 && maxAttempts <= 10
    ? ''
    : '尝试次数必须在 1-10 之间';
}

/** 目标标识校验（小写字母数字与连字符，3-64 位） */
export function validateTargetCode(code: string | undefined): string {
  const value = (code ?? '').trim();
  if (!/^[a-z0-9][a-z0-9-]{2,63}$/.test(value)) {
    return '目标标识只能是小写字母数字与连字符，3-64 位';
  }
  return '';
}

/** 新增目标的入参（标识与地址归一化；密钥只提交不回显） */
export function buildTargetPayload(form: {
  applicationId: number | undefined;
  code?: string;
  eventTypes: string[];
  maxAttempts?: number;
  name?: string;
  secret?: string;
  targetUrl?: string;
}): AiWebhookApi.TargetSavePayload {
  return {
    applicationId: form.applicationId ?? 0,
    code: (form.code ?? '').trim().toLowerCase(),
    eventTypes: [...form.eventTypes],
    maxAttempts: form.maxAttempts ?? 3,
    name: (form.name ?? '').trim(),
    secret: (form.secret ?? '').trim(),
    targetUrl: (form.targetUrl ?? '').trim(),
  };
}

/** 密钥版本提示：轮换后旧密钥立即作废，接收端必须先换密钥 */
export function secretRotationHint(revision = 0): string {
  if (revision === 0) {
    return '未配置签名密钥：目标无法投递（发送前会按“密钥不可用”确定失败）';
  }
  return `已配置密钥（版本 ${revision}）；轮换后旧密钥立即作废，接收端需同步换用新密钥`;
}

/** 投递正文摘要的展示形式（正文本身不返回，只展示摘要便于对账） */
export function shortenDigest(digest?: string): string {
  if (!digest || digest.length <= 12) {
    return digest ?? '—';
  }
  return `${digest.slice(0, 12)}…`;
}

/**
 * 评测控制面展示口径（Q05）：权限码、状态/级别/规则/错误码文案与只读展示辅助。
 *
 * 计算与聚合（verdict 解析、失败分类、运行比较、报告解析、查询参数构造）在 `analysis.ts`，
 * 这里只放稳定文案与最小校验，两个文件都不臆造后端未给的字段。
 */
export const AI_EVAL_PERMISSIONS = {
  manage: 'ai:eval:manage',
  query: 'ai:eval:query',
  review: 'ai:eval:review',
  run: 'ai:eval:run',
} as const;

/** 套件状态：只有 DRAFT 可编辑 */
export const SUITE_STATUS_TEXT: Record<string, string> = {
  DRAFT: '草稿（可编辑）',
  FROZEN: '已冻结（编辑前先创建新修订）',
};

/** 运行状态：RUNNING 的计数仍在变化，不能当最终结果 */
export const RUN_STATUS_TEXT: Record<string, string> = {
  COMPLETED: '已完成',
  FAILED: '失败（未跑完全部样例）',
  RUNNING: '执行中（计数仍在变化）',
};

/** 判定口径：把每种状态计入哪个计数直接写在文案里 */
export const RESULT_STATUS_TEXT: Record<string, string> = {
  ERROR: '错误（未能执行，计入 errorCount）',
  FAILED: '失败（计入 failedCount）',
  PASSED: '通过（仅此状态计入 passedCount）',
  REVIEW_REQUIRED: '待复核（计入 failedCount，复核通过前不算通过）',
};

/** 复核状态：只有 PENDING 可以复核 */
export const REVIEW_STATUS_TEXT: Record<string, string> = {
  APPROVED: '复核通过',
  NOT_REQUIRED: '无需复核',
  PENDING: '等待复核',
  REJECTED: '复核否决',
};

/** 严重级别（闭集） */
export const SEVERITY_TEXT: Record<string, string> = {
  BLOCKER: '阻断级（BLOCKER）',
  MAJOR: '严重（MAJOR）',
  MINOR: '轻微（MINOR）',
};

/** 确定性规则的 kind 文案（与 AiEvalChecks 的支持集合一致） */
export const RULE_KIND_TEXT: Record<string, string> = {
  CITATION: '引用来源',
  DATE: '日期',
  MONEY: '金额',
  NO_SECRET: '无秘密',
  STRUCTURE: '结构',
  VALUE: '取值',
  VERSION: '版本',
};

/** 未能执行时的稳定错误码（其余错误码原样展示，不猜含义） */
export const FAILURE_CODE_TEXT: Record<string, string> = {
  '1003009003': '套件没有样例，不能冻结或执行',
  '1003009013': '未能取到执行租约（执行队列被其它任务占用）',
  AI_EVAL_EXECUTION_FAILED: '执行未完成（未产生判定，不能按通过计）',
};

/** 判定过滤选项（复核队列默认看"待复核"） */
export const RESULT_STATUS_OPTIONS = [
  { label: '待复核（REVIEW_REQUIRED）', value: 'REVIEW_REQUIRED' },
  { label: '失败（FAILED）', value: 'FAILED' },
  { label: '错误（ERROR）', value: 'ERROR' },
  { label: '通过（PASSED）', value: 'PASSED' },
];

/** 期望规则输入框的示例文案（避免在模板属性里转义双引号） */
export const CHECKS_PLACEHOLDER =
  'JSON 数组，例如 [{"kind":"VALUE","path":"answer","expect":"42"}]';

export const SUITE_STATUS_OPTIONS = [
  { label: '草稿（DRAFT）', value: 'DRAFT' },
  { label: '已冻结（FROZEN）', value: 'FROZEN' },
];

export const SEVERITY_OPTIONS = [
  { label: '阻断级（BLOCKER）', value: 'BLOCKER' },
  { label: '严重（MAJOR）', value: 'MAJOR' },
  { label: '轻微（MINOR）', value: 'MINOR' },
];

export const SUBJECT_TYPE_OPTIONS = [
  { label: '应用（APP）', value: 'APP' },
  { label: '用户（USER）', value: 'USER' },
];

export const DATA_LEVEL_OPTIONS = [
  { label: 'L1 公开（L1_PUBLIC）', value: 'L1_PUBLIC' },
  { label: 'L2 内部（L2_INTERNAL）', value: 'L2_INTERNAL' },
];

/**
 * 子面板向上抛的消息（页面顶部统一展示一处提示，避免每个面板各写一份告警）。
 * kind 只有两种：error 是失败（不伪装成功），notice 是已生效的动作结果。
 */
export interface PanelFeedback {
  kind: 'error' | 'notice';
  message: string;
}

/** 套件表单初值（subjectType 必填，服务编号必填） */
export const DEFAULT_SUITE_FORM = {
  code: '',
  dataLevel: 'L2_INTERNAL',
  description: '',
  externalUserId: '',
  name: '',
  serviceId: '',
  subjectType: 'APP',
};

/** 样例表单初值（needsReview 表示"期望通过但需人工确认"） */
export const DEFAULT_CASE_FORM = {
  caseKey: '',
  checksJson: '',
  expectVersion: '',
  needsReview: false,
  question: '',
  severity: 'MAJOR',
  title: '',
};

/** 文本裁断（只在展示层使用，不改动原值） */
export function truncateText(value: string, max: number): string {
  if (value.length <= max) {
    return value;
  }
  return `${value.slice(0, max)}…`;
}

/** 摘要展示：没有摘要时说"未冻结"，不显示空串 */
export function digestText(digest?: null | string): string {
  if (!digest) {
    return '未冻结（无摘要）';
  }
  return truncateText(digest, 12);
}

/** 三态变化文案：null 表示无法比较（不假装"无变化"） */
export function changeText(value: boolean | null): string {
  if (value === null) {
    return '无法比较';
  }
  return value ? '变化' : '无变化';
}

export function describeSuiteStatus(status?: string): string {
  if (!status) {
    return '-';
  }
  return SUITE_STATUS_TEXT[status] ?? status;
}

export function describeRunStatus(status?: string): string {
  if (!status) {
    return '-';
  }
  return RUN_STATUS_TEXT[status] ?? status;
}

export function describeResultStatus(status?: string): string {
  if (!status) {
    return '-';
  }
  return RESULT_STATUS_TEXT[status] ?? status;
}

export function describeReviewStatus(status?: string): string {
  if (!status) {
    return '-';
  }
  return REVIEW_STATUS_TEXT[status] ?? status;
}

export function describeSeverity(severity?: string): string {
  if (!severity) {
    return '-';
  }
  return SEVERITY_TEXT[severity] ?? severity;
}

export function describeRuleKind(kind?: string): string {
  if (!kind) {
    return '-';
  }
  return RULE_KIND_TEXT[kind] ?? `未登记规则类型 ${kind}`;
}

export function describeSubjectType(type?: string): string {
  if (!type) {
    return '-';
  }
  if (type === 'APP') {
    return '应用（APP）';
  }
  if (type === 'USER') {
    return '用户（USER）';
  }
  return type;
}

export function describeDataLevel(level?: string): string {
  if (!level) {
    return '-';
  }
  if (level === 'L1_PUBLIC') {
    return 'L1 公开（L1_PUBLIC）';
  }
  if (level === 'L2_INTERNAL') {
    return 'L2 内部（L2_INTERNAL）';
  }
  // 未知分级原样展示，便于发现后端新增
  return level;
}

export function describeFailureCode(code?: null | string): string {
  if (!code) {
    return '-';
  }
  return FAILURE_CODE_TEXT[code] ?? `未登记错误码 ${code}`;
}

/** 严重级别的标签色 */
export function severityTagType(
  severity?: string,
): 'danger' | 'info' | 'warning' {
  if (severity === 'BLOCKER') {
    return 'danger';
  }
  return severity === 'MAJOR' ? 'warning' : 'info';
}

/** 判定的标签色：待复核不是失败也不是通过，用独立的 warning 提示 */
export function resultTagType(
  status?: string,
): 'danger' | 'info' | 'success' | 'warning' {
  if (status === 'PASSED') {
    return 'success';
  }
  if (status === 'FAILED') {
    return 'danger';
  }
  if (status === 'REVIEW_REQUIRED') {
    return 'warning';
  }
  return 'info';
}

/** 只有草稿套件可编辑（冻结后必须创建新修订） */
export function isSuiteEditable(status?: string): boolean {
  return status === 'DRAFT';
}

/** 只有等待复核的结果可以复核 */
export function isReviewPending(status?: string): boolean {
  return status === 'PENDING';
}

/** 正整数解析（套件表单的服务编号）：非法返回 undefined，由调用方给出提示 */
export function parsePositiveInt(value: string): number | undefined {
  const trimmed = value.trim();
  if (trimmed === '' || !/^\d+$/.test(trimmed)) {
    return undefined;
  }
  const parsed = Number(trimmed);
  return Number.isSafeInteger(parsed) && parsed > 0 ? parsed : undefined;
}

/** 空串转 undefined（避免把空字段发给服务端） */
export function trimmedOrUndefined(value: string): string | undefined {
  const trimmed = value.trim();
  return trimmed === '' ? undefined : trimmed;
}

import type { AiUsageApi } from '#/api/ai/observability/usage';

/** 用量与限额权限码（与 V81 的 system_menu 种子一一对应） */
export const AI_USAGE_PERMISSIONS = {
  query: 'ai:usage:query',
} as const;

/**
 * 计量来源（Q02 的闭集）：
 * - REPORTED 上游报告，是真实用量；
 * - ESTIMATED 平台估算，能看但不等同上游账单；
 * - UNKNOWN 没有用量事实，**不按 0 记账**（AT-060）。
 */
export const USAGE_SOURCE_TEXT: Record<string, string> = {
  ESTIMATED: '估算（平台推算）',
  REPORTED: '上游报告',
  UNKNOWN: '未知（上游未返回）',
};

/** 时间窗选项（天） */
export const WINDOW_DAY_OPTIONS = [
  { label: '最近 1 天', value: 1 },
  { label: '最近 7 天', value: 7 },
  { label: '最近 30 天', value: 30 },
];

/** 计量来源文本（未知来源原样展示，便于发现后端新增来源） */
export function describeUsageSource(source?: string): string {
  if (!source) {
    return '-';
  }
  return USAGE_SOURCE_TEXT[source] ?? source;
}

/** 是否属于"没有用量事实"的来源 */
export function isUnknownUsage(source?: string): boolean {
  return source === 'UNKNOWN';
}

/** token 展示：未知显示"未知"，真实 0 显示 0（不把不知道显示成零） */
export function formatTokens(value?: null | number): string {
  if (value === undefined || value === null) {
    return '未知';
  }
  return String(value);
}

/** 耗时展示：未知显示"未记录" */
export function formatDuration(value?: null | number): string {
  if (value === undefined || value === null) {
    return '未记录';
  }
  return `${value} ms`;
}

/** 本地 `YYYY-MM-DDTHH:mm:ss`（后端 LocalDateTime 直接可解析，不带时区后缀） */
export function toLocalDateTime(value: Date): string {
  const pad = (input: number) => String(input).padStart(2, '0');
  return `${value.getFullYear()}-${pad(value.getMonth() + 1)}-${pad(
    value.getDate(),
  )}T${pad(value.getHours())}:${pad(value.getMinutes())}:${pad(
    value.getSeconds(),
  )}`;
}

/** 最近 N 天的时间窗（含起、不含止；止=当前时刻） */
export function windowRange(
  days: number,
  now: Date = new Date(),
): { from: string; to: string } {
  const safeDays = days > 0 ? days : 1;
  const start = new Date(now.getTime() - safeDays * 24 * 60 * 60 * 1000);
  return { from: toLocalDateTime(start), to: toLocalDateTime(now) };
}

/** 按来源聚合的行（后端已按来源分组，这里只保证顺序稳定：REPORTED → ESTIMATED → UNKNOWN） */
export function summaryRows(
  summary?: AiUsageApi.SourceSummary,
): AiUsageApi.SourceSummaryRow[] {
  const rows = summary?.sources ?? [];
  const order = ['REPORTED', 'ESTIMATED', 'UNKNOWN'];
  return rows.toSorted(
    (left, right) =>
      order.indexOf(left.usageSource) - order.indexOf(right.usageSource),
  );
}

/** 汇总标题：把"来源未知"的条数显式带出来，避免把估算当精确值 */
export function summaryHeadline(summary?: AiUsageApi.SourceSummary): string {
  if (!summary) {
    return '尚未读取汇总';
  }
  const total = summary.sources.reduce(
    (sum, row) => sum + row.invocationCount,
    0,
  );
  return `共 ${total} 次调用，其中来源未知 ${summary.unknownInvocations} 次`;
}

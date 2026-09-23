import type { ChartSpec } from '@vben/ai-contracts';

import type { ChartRow } from './specToRows';

import { hasRenderableValue } from './specToRows';

/**
 * 图表降级判定（R02）：什么情况下不画图、改成表格。
 *
 * 为什么需要降级而不是"硬画"：空数据、超多类目、饼图多系列、数值全是缺失——
 * 硬画出来的图会让人误读（空图看起来像"业务为 0"，饼图多系列会被悄悄丢掉几个系列）。
 * 降级为表格时数据仍然完整可见，且**明确标注降级原因**。
 */
export const MAX_RENDER_CATEGORIES = 60;

export const MAX_PIE_CATEGORIES = 12;

/** 降级原因（稳定标识，便于测试与埋点）。 */
export type FallbackReason =
  | 'EMPTY_CATEGORIES'
  | 'NO_NUMERIC_VALUE'
  | 'PIE_MULTIPLE_SERIES'
  | 'PIE_TOO_MANY_CATEGORIES'
  | 'TOO_MANY_CATEGORIES'
  | null;

/** 判定是否需要降级为表格；返回 null 表示可以直接渲染。 */
export function fallbackReason(
  spec: ChartSpec,
  rows: ChartRow[],
): FallbackReason {
  if (spec.categories.length === 0) {
    return 'EMPTY_CATEGORIES';
  }
  if (!hasRenderableValue(rows)) {
    return 'NO_NUMERIC_VALUE';
  }
  if (spec.type === 'pie') {
    if (spec.series.length > 1) {
      return 'PIE_MULTIPLE_SERIES';
    }
    if (spec.categories.length > MAX_PIE_CATEGORIES) {
      return 'PIE_TOO_MANY_CATEGORIES';
    }
  }
  if (spec.categories.length > MAX_RENDER_CATEGORIES) {
    return 'TOO_MANY_CATEGORIES';
  }
  return null;
}

/** 降级表格模型：类目 + 每个系列一列（保留原始值文本，金额不失真）。 */
export function toTableModel(
  spec: ChartSpec,
  rows: ChartRow[],
): { columns: string[]; rows: Array<Array<string>> } {
  const columns = ['类目', ...spec.series.map((series) => series.name)];
  const byCategory = new Map<number, Map<string, string>>();
  rows.forEach((row) => {
    const entry =
      byCategory.get(row.categoryIndex) ?? new Map<string, string>();
    entry.set(row.series, row.rawValue);
    byCategory.set(row.categoryIndex, entry);
  });
  const tableRows = spec.categories.map((category, index) => {
    const entry = byCategory.get(index) ?? new Map<string, string>();
    return [
      category,
      ...spec.series.map((series) => entry.get(series.name) ?? ''),
    ];
  });
  return { columns, rows: tableRows };
}

/** 降级原因的可读文案（界面直接展示）。 */
export function describeFallback(reason: FallbackReason): string {
  switch (reason) {
    case 'EMPTY_CATEGORIES': {
      return '没有可展示的类目，已改为表格';
    }
    case 'NO_NUMERIC_VALUE': {
      return '数值缺失，已改为表格';
    }
    case 'PIE_MULTIPLE_SERIES': {
      return '饼图只支持单系列，已改为表格';
    }
    case 'PIE_TOO_MANY_CATEGORIES': {
      return '饼图类目过多，已改为表格';
    }
    case 'TOO_MANY_CATEGORIES': {
      return '类目过多，已改为表格';
    }
    default: {
      return '';
    }
  }
}

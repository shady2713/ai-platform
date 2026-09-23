import type { ChartSpec } from '@vben/ai-contracts';

/**
 * ChartSpec → 渲染行（R02）：**厂商无关**的中间表示。
 *
 * 为什么要这一层：G2 的 options 是厂商格式，只允许出现在 `chartOptions.ts` 内；
 * 其余逻辑（缺值处理、大数、长标签、表格降级）都作用在这层行数据上，
 * 换渲染库时不需要动业务与测试。
 *
 * 约定：
 *  - 类目缺失（`categories` 比数据短）补空字符串，不抛异常（上游数据可能参差）；
 *  - 值缺失（`null`/`undefined`/空字符串）保留为 `null`：折线出现断点、柱状留空，
 *    而不是"当成 0"（当成 0 会把缺失说成业务上的 0，属于数据失真）；
 *  - 金额以十进制字符串进入，这里只做**显示用**数值转换，不回写存储（存储保持字符串精度）。
 */
export interface ChartRow {
  category: string;
  /** 类目在 categories 中的下标（长标签截断时仍可回到原文） */
  categoryIndex: number;
  /** 原始值文本（金额等精确值用十进制字符串保留，供 tooltip 与表格展示） */
  rawValue: string;
  series: string;
  /** 显示用数值；缺失为 null */
  value: null | number;
}

/** 把 ChartSpec 展平成行；同一类目下各系列的取值一一对应。 */
export function specToRows(spec: ChartSpec): ChartRow[] {
  const rows: ChartRow[] = [];
  spec.series.forEach((series) => {
    spec.categories.forEach((category, index) => {
      const raw = series.data[index];
      rows.push({
        category,
        categoryIndex: index,
        rawValue: raw === undefined || raw === null ? '' : String(raw),
        series: series.name,
        value: toDisplayNumber(raw),
      });
    });
  });
  return rows;
}

/** 显示用数值：仅接受有限数字；非法/缺失返回 null（不静默补 0）。 */
export function toDisplayNumber(value: unknown): null | number {
  if (value === null || value === undefined || value === '') {
    return null;
  }
  const parsed = typeof value === 'number' ? value : Number(value);
  return Number.isFinite(parsed) ? parsed : null;
}

/** 每个系列是否都有可渲染的数值（决定能否画图，还是降级成表格）。 */
export function hasRenderableValue(rows: ChartRow[]): boolean {
  return rows.some((row) => row.value !== null);
}

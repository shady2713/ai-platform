import type { ChartSpec } from '@vben/ai-contracts';

import type { ChartRow } from './specToRows';
import type { ChartThemeTokens } from './theme';

import { truncateLabel } from './theme';

/**
 * 行数据 + 主题 → G2 渲染选项（R02）：**厂商格式只出现在本文件**。
 *
 * 对外（组件 props/emits、报表存储、模型输出）永远只有 ChartSpec；
 * 换渲染库时替换本文件即可，契约与业务逻辑不受影响（单测断言"转换结果里没有 ChartSpec 之外的字段"）。
 */
export interface VendorChartOptions {
  axis: Record<string, unknown>;
  coordinate?: Record<string, unknown>;
  data: Array<Record<string, unknown>>;
  encode: Record<string, string>;
  legend: boolean | Record<string, unknown>;
  scale: Record<string, unknown>;
  style: Record<string, unknown>;
  theme: Record<string, unknown>;
  type: string;
}

/** 生成厂商选项：column/line/pie 三种类型。 */
export function toVendorOptions(
  spec: ChartSpec,
  rows: ChartRow[],
  tokens: ChartThemeTokens,
): VendorChartOptions {
  const data = rows.map((row) => ({
    category: truncateLabel(row.category),
    fullCategory: row.category,
    series: row.series,
    value: row.value,
  }));
  const base: VendorChartOptions = {
    axis: {
      x: {
        labelFill: tokens.labelColor,
        lineStroke: tokens.axisColor,
        labelFontSize: tokens.labelSize,
      },
      y: {
        labelFill: tokens.labelColor,
        lineStroke: tokens.axisColor,
        labelFontSize: tokens.labelSize,
      },
    },
    data,
    encode: { x: 'category', y: 'value', color: 'series' },
    legend: { itemLabelFill: tokens.labelColor },
    scale: { color: { range: tokens.palette } },
    style: { radiusTopLeft: 2, radiusTopRight: 2 },
    theme: { background: tokens.background },
    type: spec.type === 'line' ? 'line' : 'interval',
  };
  if (spec.type === 'pie') {
    return {
      ...base,
      // 饼图只取第一个系列（多系列在降级判定里已被拦下）
      data: data.filter((row) => row.series === spec.series[0]?.name),
      coordinate: { type: 'theta' },
      encode: { y: 'value', color: 'category' },
      legend: { itemLabelFill: tokens.labelColor, position: 'right' },
      style: {},
    };
  }
  return base;
}

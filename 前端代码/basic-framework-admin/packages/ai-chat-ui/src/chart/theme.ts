/**
 * 图表主题令牌（R02）：自有令牌 → 渲染选项的映射，**不暴露厂商主题名**。
 *
 * 深浅色与窄屏（AT-054）需要同一份令牌：宿主传 `light`/`dark`，这里给出颜色与字号，
 * 换渲染库时只改本文件；令牌取值与 `@vben/ai-contracts` 的主题契约保持同一语义（颜色十六进制、半径 0..24）。
 */
export interface ChartThemeTokens {
  axisColor: string;
  background: string;
  labelColor: string;
  labelSize: number;
  palette: string[];
  titleSize: number;
}

const LIGHT: ChartThemeTokens = {
  axisColor: '#d4d4d8',
  background: '#ffffff',
  labelColor: '#3f3f46',
  labelSize: 12,
  palette: ['#2563eb', '#16a34a', '#f59e0b', '#dc2626', '#7c3aed'],
  titleSize: 14,
};

const DARK: ChartThemeTokens = {
  axisColor: '#3f3f46',
  background: '#18181b',
  labelColor: '#e4e4e7',
  labelSize: 12,
  palette: ['#60a5fa', '#4ade80', '#fbbf24', '#f87171', '#a78bfa'],
  titleSize: 14,
};

/** 主题令牌（未知主题名一律回退到浅色，不抛异常）。 */
export function themeTokens(theme?: string): ChartThemeTokens {
  return theme === 'dark' ? DARK : LIGHT;
}

/** 长标签截断（保留首尾，便于识别且不撑破布局）；短标签原样返回。 */
export function truncateLabel(label: string, maxLength = 12): string {
  if (label.length <= maxLength) {
    return label;
  }
  const head = Math.max(1, Math.floor((maxLength - 1) / 2));
  const tail = Math.max(1, maxLength - 1 - head);
  return `${label.slice(0, head)}…${label.slice(-tail)}`;
}

/** 大数显示：千分位 + 最多两位小数（只影响显示，不影响原始值文本）。 */
export function formatLargeNumber(value: null | number): string {
  if (value === null) {
    return '-';
  }
  return value.toLocaleString('zh-CN', { maximumFractionDigits: 2 });
}

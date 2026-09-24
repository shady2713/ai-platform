/**
 * 主题适配（C04）：同一份主题令牌 → Chat / 图表 / 报表共用的渲染输入。
 *
 * <p>为什么需要这一层：Chat 的气泡与按钮、图表的色板、报表的深浅色如果各自解释主题，
 * 就会出现"聊天里是品牌蓝、图里是默认蓝"这种对不上的结果（AT-054 要求三者一致）。
 * 这里把三者的入口收敛到 {@link ResolvedTheme}：
 *
 * <ul>
 *   <li>Chat 层：{@link themeCssVariables}（在 tokens.ts）产出的 CSS 自定义属性；</li>
 *   <li>图表层：{@link chartTokensOf} 复用既有的图表令牌实现（`chart/theme.ts`），只把主色提到第一序列色；</li>
 *   <li>报表层：{@link reportThemeOf} 给出 `AiReportView` 需要的深浅色名。</li>
 * </ul>
 */
import type { ChartThemeTokens } from '../chart/theme';
import type { ResolvedTheme } from './tokens';

import { themeTokens as chartThemeTokens } from '../chart/theme';
import { ALLOWED_FONT_FAMILIES } from './tokens';

/** 深浅色名（`AiReportView` / 图表库共用）。 */
export type ThemeColorScheme = 'dark' | 'light';

/** 有效主题的深浅色（未声明时按浅色，不猜深色）。 */
export function colorSchemeOf(resolved: ResolvedTheme): ThemeColorScheme {
  return resolved.theme.colorScheme === 'dark' ? 'dark' : 'light';
}

/** 报表渲染器的主题名。 */
export function reportThemeOf(resolved: ResolvedTheme): ThemeColorScheme {
  return colorSchemeOf(resolved);
}

/**
 * 图表令牌：深浅色取主题，**品牌主色作为第一序列色**（其余序列色来自既有图表令牌）。
 *
 * <p>色板长度保持不变：主色已在原色板中时只是提前，避免"多一个颜色"或重复色。
 */
export function chartTokensOf(resolved: ResolvedTheme): ChartThemeTokens {
  const base = chartThemeTokens(colorSchemeOf(resolved));
  const primaryColor = resolved.theme.primaryColor;
  const secondary = base.palette.filter((color) => color !== primaryColor);
  return {
    ...base,
    palette: [primaryColor, ...secondary].slice(0, base.palette.length),
  };
}

/** 字体栈是否受支持（渲染前自检；后端与主题层都用同一份白名单）。 */
export function isAllowedFontFamily(fontFamily: string): boolean {
  return ALLOWED_FONT_FAMILIES.includes(fontFamily);
}

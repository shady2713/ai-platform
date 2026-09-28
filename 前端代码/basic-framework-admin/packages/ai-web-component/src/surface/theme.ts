import type { Theme } from '@vben/ai-contracts';

import { resolveEffectiveTheme, themeCssVariables } from '@vben/ai-chat-ui';

/**
 * 主题令牌（X09）：与 iframe 路径共用同一份 token 与同一套 CSS 变量名。
 *
 * <p>组件的样式边界在 Shadow DOM 里，变量只作用在组件自己的面板元素上，
 * 不会改写宿主页面的样式；反过来宿主的 CSS 也进不来（见 `element/styles.ts`）。
 */

/** 主题 → CSS 自定义属性（值全部来自已校验 token，没有透传的样式文本）。 */
export function themeVariables(
  theme: Theme | undefined,
): Record<string, string> {
  const resolved = resolveEffectiveTheme(
    theme === undefined
      ? {}
      : // 宿主覆盖属于运行时覆盖口径：只允许声明的字段（字体重置会被拒绝）
        { source: 'PLATFORM_DEFAULT', tokensJson: theme },
  );
  return themeCssVariables(resolved);
}

/** 把主题令牌写到元素的内联样式上（子节点通过继承生效）。 */
export function applyThemeVariables(
  element: HTMLElement,
  theme: Theme | undefined,
): void {
  for (const [name, value] of Object.entries(themeVariables(theme))) {
    element.style.setProperty(name, value);
  }
}

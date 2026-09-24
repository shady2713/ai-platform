/**
 * 主题样例（C04）：两份可直接喂给 {@link resolveEffectiveTheme} 的载荷。
 *
 * <p>用途：管理端"主题预览"（C09）与宿主示例（C10）不必自己编数据；测试也用它们固定
 * "浅色 + 深色都通过校验"这一事实。样例不含任何凭据，也不含 CSS 片段。
 */
import type { EffectiveThemePayload } from './tokens';

import { ALLOWED_FONT_FAMILIES } from './tokens';

/** 浅色样例（品牌蓝 + 常规密度）。 */
export const lightThemeSample: EffectiveThemePayload = {
  fingerprint: 'sample-light',
  layoutJson: JSON.stringify({
    density: 'normal',
    fontScale: 'normal',
    minSidebarWidth: 320,
    narrowBreakpoint: 768,
  }),
  publicId: 'thm_sample_light',
  revision: 1,
  source: 'APPLICATION_PUBLISHED',
  tokensJson: JSON.stringify({
    colorScheme: 'light',
    fontFamily: ALLOWED_FONT_FAMILIES[0],
    primaryColor: '#2563eb',
    radius: 4,
  }),
};

/** 深色样例（品牌紫 + 紧凑密度 + 大字号）。 */
export const darkThemeSample: EffectiveThemePayload = {
  fingerprint: 'sample-dark',
  layoutJson: JSON.stringify({
    density: 'compact',
    fontScale: 'large',
    minSidebarWidth: 360,
    narrowBreakpoint: 640,
  }),
  publicId: 'thm_sample_dark',
  revision: 2,
  source: 'APPLICATION_PUBLISHED',
  tokensJson: JSON.stringify({
    colorScheme: 'dark',
    fontFamily: ALLOWED_FONT_FAMILIES[1],
    primaryColor: '#7c3aed',
    radius: 8,
  }),
};

/** 无应用主题时的样例（服务端返回平台默认来源）。 */
export const platformDefaultSample: EffectiveThemePayload = {
  fingerprint: 'sample-platform-default',
  source: 'PLATFORM_DEFAULT',
};

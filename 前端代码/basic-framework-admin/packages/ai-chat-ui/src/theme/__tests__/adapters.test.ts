import { describe, expect, it } from 'vitest';

import { themeTokens as chartTokens } from '../../chart/theme';
import {
  chartTokensOf,
  colorSchemeOf,
  isAllowedFontFamily,
  reportThemeOf,
} from '../adapters';
import {
  darkThemeSample,
  lightThemeSample,
  platformDefaultSample,
} from '../samples';
import { ALLOWED_FONT_FAMILIES, resolveEffectiveTheme } from '../tokens';

describe('主题样例', () => {
  it('浅色与深色样例都能通过校验', () => {
    const light = resolveEffectiveTheme(lightThemeSample);
    expect(light.source).toBe('APPLICATION_PUBLISHED');
    expect(light.theme.colorScheme).toBe('light');
    expect(colorSchemeOf(light)).toBe('light');

    const dark = resolveEffectiveTheme(darkThemeSample);
    expect(dark.theme.colorScheme).toBe('dark');
    expect(dark.layout.density).toBe('compact');
    expect(colorSchemeOf(dark)).toBe('dark');
  });

  it('平台默认样例不带 token 时回落到平台默认', () => {
    const resolved = resolveEffectiveTheme(platformDefaultSample);
    expect(resolved.source).toBe('PLATFORM_DEFAULT');
    expect(resolved.theme.fontFamily).toBe(ALLOWED_FONT_FAMILIES[0]);
  });
});

describe('共用令牌适配', () => {
  it('深浅色与报表渲染器同名', () => {
    const light = resolveEffectiveTheme(lightThemeSample);
    expect(reportThemeOf(light)).toBe('light');
    const dark = resolveEffectiveTheme(darkThemeSample);
    expect(reportThemeOf(dark)).toBe('dark');
  });

  it('图表色板以品牌主色开头且长度不变', () => {
    const resolved = resolveEffectiveTheme(lightThemeSample);
    const tokens = chartTokensOf(resolved);
    const base = chartTokens('light');

    expect(tokens.palette[0]).toBe(resolved.theme.primaryColor);
    expect(tokens.palette).toHaveLength(base.palette.length);
    expect(new Set(tokens.palette).size).toBe(tokens.palette.length);
    expect(tokens.background).toBe(base.background);
    expect(tokens.labelSize).toBe(base.labelSize);
  });

  it('主色已在原色板中时不重复', () => {
    const dark = resolveEffectiveTheme(darkThemeSample);
    const tokens = chartTokensOf(dark);

    expect(tokens.palette[0]).toBe('#7c3aed');
    expect(tokens.palette.filter((color) => color === '#7c3aed')).toHaveLength(
      1,
    );
    expect(tokens.background).toBe(chartTokens('dark').background);
  });

  it('未声明深浅色时按浅色图表令牌', () => {
    const tokens = chartTokensOf(resolveEffectiveTheme({}));
    expect(tokens.background).toBe(chartTokens('light').background);
  });

  it('字体白名单与主题层一致', () => {
    expect(isAllowedFontFamily(ALLOWED_FONT_FAMILIES[2] ?? '')).toBe(true);
    expect(isAllowedFontFamily('Arial')).toBe(false);
  });
});

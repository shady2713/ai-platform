import { describe, expect, it } from 'vitest';

import {
  ALLOWED_FONT_FAMILIES,
  applyRuntimeOverride,
  defaultLayout,
  parseThemeLayout,
  parseThemeTokens,
  platformDefaultTheme,
  resolveEffectiveTheme,
  themeCssVariables,
} from '../tokens';

function tokensJson(overrides: Record<string, unknown> = {}): string {
  return JSON.stringify({
    fontFamily: ALLOWED_FONT_FAMILIES[0],
    primaryColor: '#2563eb',
    radius: 4,
    ...overrides,
  });
}

describe('主题 token 解析', () => {
  it('接受合规 token（字符串与对象两种形态）', () => {
    const fromString = parseThemeTokens(tokensJson());
    expect(fromString.primaryColor).toBe('#2563eb');
    expect(fromString.radius).toBe(4);
    expect(fromString.fontFamily).toBe(ALLOWED_FONT_FAMILIES[0]);

    const fromObject = parseThemeTokens({
      colorScheme: 'dark',
      fontFamily: ALLOWED_FONT_FAMILIES[1],
      primaryColor: '#7c3aed',
      radius: 8,
    });
    expect(fromObject.colorScheme).toBe('dark');
  });

  it('拒绝未知字段、非法色值与白名单外字体', () => {
    expect(() => parseThemeTokens(tokensJson({ background: '#fff' }))).toThrow(
      /主题不合规/u,
    );
    expect(() =>
      parseThemeTokens(tokensJson({ primaryColor: 'red' })),
    ).toThrow();
    expect(() =>
      parseThemeTokens(tokensJson({ primaryColor: 'javascript:alert(1)' })),
    ).toThrow();
    expect(() =>
      parseThemeTokens(tokensJson({ fontFamily: 'Arial, sans-serif' })),
    ).toThrow(/字体不在自托管白名单内/u);
    expect(() =>
      parseThemeTokens(
        tokensJson({ fontFamily: 'url(https://evil.example.com/f.woff)' }),
      ),
    ).toThrow(/字体不在自托管白名单内/u);
    expect(() => parseThemeTokens('not json')).toThrow(/不是合法 JSON/u);
    expect(() => parseThemeTokens([1, 2])).toThrow(/必须是对象/u);
  });

  it('半径越界由冻结契约拦下', () => {
    expect(() => parseThemeTokens(tokensJson({ radius: 25 }))).toThrow();
    expect(() => parseThemeTokens(tokensJson({ radius: -1 }))).toThrow();
  });
});

describe('主题布局解析', () => {
  it('缺省项补齐为平台默认', () => {
    expect(parseThemeLayout(undefined)).toStrictEqual(defaultLayout);
    expect(parseThemeLayout({})).toStrictEqual(defaultLayout);
    expect(parseThemeLayout(null)).toStrictEqual(defaultLayout);
  });

  it('接受受控枚举与区间内的整数', () => {
    expect(
      parseThemeLayout({
        density: 'compact',
        fontScale: 'large',
        minSidebarWidth: 360,
        narrowBreakpoint: 640,
      }),
    ).toStrictEqual({
      density: 'compact',
      fontScale: 'large',
      minSidebarWidth: 360,
      narrowBreakpoint: 640,
    });
  });

  it('拒绝任意样式值', () => {
    expect(() => parseThemeLayout({ padding: '8px' })).toThrow(/未知布局字段/u);
    expect(() => parseThemeLayout({ fontScale: 'huge' })).toThrow(
      /取值不在允许域内/u,
    );
    expect(() => parseThemeLayout({ density: 'dense' })).toThrow(
      /取值不在允许域内/u,
    );
    expect(() => parseThemeLayout({ narrowBreakpoint: 100 })).toThrow(/整数/u);
    expect(() => parseThemeLayout({ narrowBreakpoint: 768.5 })).toThrow(
      /整数/u,
    );
    expect(() => parseThemeLayout({ minSidebarWidth: '320px' })).toThrow(
      /整数/u,
    );
  });
});

describe('有效主题解析（默认 → 应用已发布）', () => {
  it('缺失或来源不明确时使用平台默认', () => {
    const resolved = resolveEffectiveTheme({});
    expect(resolved.source).toBe('PLATFORM_DEFAULT');
    expect(resolved.overridden).toBe(false);
    expect(resolved.theme).toStrictEqual(platformDefaultTheme());
    expect(resolved.layout).toStrictEqual(defaultLayout);

    expect(resolveEffectiveTheme({ source: 'RUNTIME_OVERRIDE' }).source).toBe(
      'PLATFORM_DEFAULT',
    );
    expect(resolveEffectiveTheme(undefined).source).toBe('PLATFORM_DEFAULT');
  });

  it('服务端标记已发布时使用应用主题', () => {
    const resolved = resolveEffectiveTheme({
      fingerprint: 'f'.repeat(64),
      layoutJson: JSON.stringify({ density: 'compact' }),
      publicId: 'thm_x',
      revision: 3,
      source: 'APPLICATION_PUBLISHED',
      tokensJson: tokensJson({ colorScheme: 'dark' }),
    });
    expect(resolved.source).toBe('APPLICATION_PUBLISHED');
    expect(resolved.theme.primaryColor).toBe('#2563eb');
    expect(resolved.theme.colorScheme).toBe('dark');
    expect(resolved.layout.density).toBe('compact');
    expect(resolved.layout.fontScale).toBe('normal');
  });

  it('标记已发布却没有 token 时拒绝（不悄悄回落）', () => {
    expect(() =>
      resolveEffectiveTheme({ source: 'APPLICATION_PUBLISHED' }),
    ).toThrow(/已发布主题缺少 token/u);
  });

  it('载荷是非法 JSON 时拒绝', () => {
    expect(() => resolveEffectiveTheme('{')).toThrow(/不是合法 JSON/u);
  });
});

describe('运行时覆盖（不落库、只影响本次实例）', () => {
  const base = resolveEffectiveTheme({
    source: 'APPLICATION_PUBLISHED',
    tokensJson: tokensJson(),
  });

  it('只允许规定字段，且不改变来源语义', () => {
    const overridden = applyRuntimeOverride(base, {
      colorScheme: 'dark',
      density: 'compact',
      fontScale: 'large',
      primaryColor: '#16a34a',
      radius: 12,
    });
    expect(overridden.overridden).toBe(true);
    expect(overridden.source).toBe('APPLICATION_PUBLISHED');
    expect(overridden.theme.primaryColor).toBe('#16a34a');
    expect(overridden.theme.radius).toBe(12);
    expect(overridden.theme.colorScheme).toBe('dark');
    expect(overridden.layout.density).toBe('compact');
    expect(overridden.layout.fontScale).toBe('large');
    // 未覆盖的布局字段保持不变
    expect(overridden.layout.narrowBreakpoint).toBe(
      base.layout.narrowBreakpoint,
    );
    // 覆盖不污染基准（其它宿主/应用读到的仍是发布配置）
    expect(base.theme.primaryColor).toBe('#2563eb');
    expect(base.overridden).toBe(false);
  });

  it('字体与未知字段一律拒绝（不静默忽略）', () => {
    expect(() =>
      applyRuntimeOverride(base, { fontFamily: ALLOWED_FONT_FAMILIES[1] }),
    ).toThrow(/不允许字段 fontFamily/u);
    expect(() => applyRuntimeOverride(base, { padding: '8px' })).toThrow(
      /不允许字段 padding/u,
    );
  });

  it('空覆盖与 null 覆盖返回原主题', () => {
    expect(applyRuntimeOverride(base, undefined)).toBe(base);
    expect(applyRuntimeOverride(base, null)).toBe(base);
    const empty = applyRuntimeOverride(base, { colorScheme: null });
    expect(empty).toBe(base);
  });

  it('越界值仍按契约拒绝', () => {
    expect(() => applyRuntimeOverride(base, { radius: 99 })).toThrow();
    expect(() => applyRuntimeOverride(base, { density: 'dense' })).toThrow(
      /取值不在允许域内/u,
    );
  });
});

describe('主题 → CSS 自定义属性', () => {
  it('值全部来自已校验 token（不含透传的样式文本）', () => {
    const resolved = resolveEffectiveTheme({
      layoutJson: JSON.stringify({
        density: 'compact',
        fontScale: 'small',
        narrowBreakpoint: 600,
      }),
      source: 'APPLICATION_PUBLISHED',
      tokensJson: tokensJson({ colorScheme: 'dark', radius: 8 }),
    });
    expect(themeCssVariables(resolved)).toStrictEqual({
      '--ai-color-scheme': 'dark',
      '--ai-density-factor': '0.75',
      '--ai-font-family': ALLOWED_FONT_FAMILIES[0],
      '--ai-font-scale-factor': '0.875',
      '--ai-narrow-breakpoint': '600px',
      '--ai-primary-color': '#2563eb',
      '--ai-radius': '8px',
      '--ai-sidebar-min-width': '320px',
    });
  });

  it('未声明深浅色与常规档位给出中性值', () => {
    const variables = themeCssVariables(resolveEffectiveTheme({}));
    expect(variables['--ai-color-scheme']).toBe('light');
    expect(variables['--ai-font-scale-factor']).toBe('1');
    expect(variables['--ai-density-factor']).toBe('1');
    expect(variables['--ai-radius']).toBe('6px');
  });
});

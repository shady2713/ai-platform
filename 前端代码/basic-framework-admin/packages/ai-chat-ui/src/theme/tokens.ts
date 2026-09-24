/**
 * 主题令牌（C04）：默认 → 应用已发布修订 → 允许的宿主运行时覆盖。
 *
 * <p>三件事在这里定死，宿主不需要（也不允许）自己拼样式：
 * <ol>
 *   <li><b>只接受声明的 token</b>：色值必须是十六进制、半径在契约区间、字体必须命中自托管白名单
 *       （与后端 `AiThemeValidator` 同值），未知字段与 CSS 片段一律拒绝——主题不能变成第二套样式表
 *       （验收失败分支："任意 CSS/url/脚本拒绝"）；</li>
 *   <li><b>继承顺序</b>：平台默认（`@vben/ai-contracts` 的 `defaultTheme`）→ 应用已发布修订（服务端
 *       `/ai/theme/effective`）→ 宿主运行时覆盖；覆盖只允许 `primaryColor`/`radius`/`colorScheme`/
 *       `fontScale`/`density` 五个字段，**字体不可覆盖**（字体是应用自托管的品牌资产，
 *       允许运行时替换等于给远程字体开一个口子）；覆盖不落库，也不影响其他应用实例；</li>
 *   <li><b>输出给渲染层的只有令牌</b>：{@link themeCssVariables} 产出的 CSS 自定义属性值全部来自
 *       已校验 token（十六进制色、白名单字体、数值带单位），没有任何字符串是"透传的样式文本"。</li>
 * </ol>
 */
import type { Theme } from '@vben/ai-contracts';

import { defaultTheme, parseTheme } from '@vben/ai-contracts';

/** 允许的字体栈（与后端 `AiThemeValidator.ALLOWED_FONT_FAMILIES` 同值）。 */
export const ALLOWED_FONT_FAMILIES: string[] = [
  'system-ui, -apple-system, "PingFang SC", "Microsoft YaHei", sans-serif',
  '"PingFang SC", "Microsoft YaHei", system-ui, sans-serif',
  'Georgia, "Songti SC", "SimSun", serif',
  'ui-monospace, SFMono-Regular, Menlo, "Courier New", monospace',
];

/** 布局与排版选项（受控枚举 + 受限数值）。 */
export interface ThemeLayout {
  density: 'compact' | 'normal';
  fontScale: 'large' | 'normal' | 'small';
  minSidebarWidth: number;
  narrowBreakpoint: number;
}

/** 布局缺省值（与后端 `AiThemeValidator.DEFAULT_LAYOUT` 同值）。 */
export const defaultLayout: ThemeLayout = {
  density: 'normal',
  fontScale: 'normal',
  minSidebarWidth: 320,
  narrowBreakpoint: 768,
};

/** 有效主题的来源：平台默认 / 应用已发布修订。 */
export type ThemeSource = 'APPLICATION_PUBLISHED' | 'PLATFORM_DEFAULT';

/** 解析后的有效主题（渲染层的唯一输入）。 */
export interface ResolvedTheme {
  /** 布局与排版选项 */
  layout: ThemeLayout;
  /** 是否应用过宿主运行时覆盖（覆盖不改变 {@link source} 的语义） */
  overridden: boolean;
  /** 来源 */
  source: ThemeSource;
  /** ThemeTokens（已校验） */
  theme: Theme;
}

/** 服务端 `/ai/theme/effective`（或嵌入握手 INIT）的主题载荷。 */
export interface EffectiveThemePayload {
  fingerprint?: string;
  layoutJson?: unknown;
  publicId?: string;
  revision?: number;
  source?: string;
  tokensJson?: unknown;
}

const FONT_SCALES = new Set(['large', 'normal', 'small']);
const DENSITIES = new Set(['compact', 'normal']);
const LAYOUT_KEYS = new Set([
  'density',
  'fontScale',
  'minSidebarWidth',
  'narrowBreakpoint',
]);

/** 运行时覆盖允许的字段（字体不在其中，见文件头说明）。 */
const OVERRIDE_TOKEN_KEYS = new Set(['colorScheme', 'primaryColor', 'radius']);
const OVERRIDE_LAYOUT_KEYS = new Set(['density', 'fontScale']);

const BREAKPOINT_MIN = 240;
const BREAKPOINT_MAX = 1440;
const SIDEBAR_MIN = 240;
const SIDEBAR_MAX = 720;

function fail(message: string): never {
  throw new Error(`主题不合规：${message}`);
}

function isPlainObject(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

/** JSON 文本或对象都接受（服务端返回字符串，宿主可能已解析）。 */
function asObject(value: unknown, label: string): Record<string, unknown> {
  if (typeof value === 'string') {
    try {
      return asObject(JSON.parse(value) as unknown, label);
    } catch {
      return fail(`${label} 不是合法 JSON`);
    }
  }
  if (!isPlainObject(value)) {
    return fail(`${label} 必须是对象`);
  }
  return value;
}

/**
 * 解析主题 token：先走冻结契约（色值格式/半径区间），再校验平台字体白名单。
 *
 * <p>缺省项回落到平台默认（`parseTheme` 的口径），未知字段直接拒绝。
 */
export function parseThemeTokens(input: unknown): Theme {
  const node = asObject(input, '主题 token');
  for (const key of Object.keys(node)) {
    if (
      !['colorScheme', 'fontFamily', 'primaryColor', 'radius'].includes(key)
    ) {
      return fail(`未知主题字段 ${key}`);
    }
  }
  // 与冻结契约的 required 一致：三个基础字段必须显式给出，不用"缺省补默认"掩盖漏配
  for (const key of ['fontFamily', 'primaryColor', 'radius']) {
    if (!(key in node)) {
      return fail(`缺少主题字段 ${key}`);
    }
  }
  let theme: Theme;
  try {
    theme = parseTheme(node);
  } catch {
    // zod 的报错细节不外泄给调用方：这里只给稳定结论
    return fail('token 不符合主题契约');
  }
  if (!ALLOWED_FONT_FAMILIES.includes(theme.fontFamily)) {
    return fail('字体不在自托管白名单内');
  }
  return theme;
}

/** 解析布局选项：受控枚举与数值区间，缺省项用平台默认补齐。 */
export function parseThemeLayout(input: unknown): ThemeLayout {
  const node =
    input === undefined || input === null ? {} : asObject(input, '主题布局');
  for (const key of Object.keys(node)) {
    if (!LAYOUT_KEYS.has(key)) {
      return fail(`未知布局字段 ${key}`);
    }
  }
  return {
    density: enumOf(node.density, DENSITIES, defaultLayout.density, '密度'),
    fontScale: enumOf(
      node.fontScale,
      FONT_SCALES,
      defaultLayout.fontScale,
      '字号档位',
    ),
    minSidebarWidth: integerOf(
      node.minSidebarWidth,
      SIDEBAR_MIN,
      SIDEBAR_MAX,
      defaultLayout.minSidebarWidth,
      '侧栏最小宽度',
    ),
    narrowBreakpoint: integerOf(
      node.narrowBreakpoint,
      BREAKPOINT_MIN,
      BREAKPOINT_MAX,
      defaultLayout.narrowBreakpoint,
      '窄屏断点',
    ),
  };
}

function enumOf<T extends string>(
  value: unknown,
  allowed: Set<string>,
  fallback: T,
  label: string,
): T {
  if (value === undefined || value === null) {
    return fallback;
  }
  if (typeof value !== 'string' || !allowed.has(value)) {
    return fail(`${label}取值不在允许域内`);
  }
  return value as T;
}

function integerOf(
  value: unknown,
  min: number,
  max: number,
  fallback: number,
  label: string,
): number {
  if (value === undefined || value === null) {
    return fallback;
  }
  if (
    typeof value !== 'number' ||
    !Number.isInteger(value) ||
    value < min ||
    value > max
  ) {
    return fail(`${label}必须是 ${min}..${max} 的整数`);
  }
  return value;
}

/** 平台默认主题（与后端 `AiThemeValidator.defaultTokensJson()` 同值）。 */
export function platformDefaultTheme(): Theme {
  return parseThemeTokens({
    fontFamily: ALLOWED_FONT_FAMILIES[0],
    primaryColor: defaultTheme.primaryColor,
    radius: defaultTheme.radius,
  });
}

/**
 * 解析服务端返回的有效主题；`tokensJson` 缺失时使用平台默认。
 *
 * <p>来源标记不猜：只有服务端明确写 `APPLICATION_PUBLISHED` 才算已发布主题，
 * 其余（含字段缺失、拼写错误）一律按平台默认处理，避免"以为在用应用主题"。
 */
export function resolveEffectiveTheme(payload: unknown): ResolvedTheme {
  const node =
    payload === undefined || payload === null
      ? {}
      : asObject(payload, '有效主题');
  const source: ThemeSource =
    node.source === 'APPLICATION_PUBLISHED'
      ? 'APPLICATION_PUBLISHED'
      : 'PLATFORM_DEFAULT';
  if (node.tokensJson === undefined && source === 'APPLICATION_PUBLISHED') {
    return fail('已发布主题缺少 token');
  }
  return {
    layout: parseThemeLayout(node.layoutJson),
    overridden: false,
    source,
    theme:
      node.tokensJson === undefined
        ? platformDefaultTheme()
        : parseThemeTokens(node.tokensJson),
  };
}

/**
 * 应用宿主运行时覆盖（优先级最低、作用范围仅本次实例）。
 *
 * <p>只允许品牌主色、半径、深浅色、字号档位与密度；**字体与布局数值不在覆盖范围**，
 * 覆盖后的结果不写回服务端，也不影响其他应用。
 */
export function applyRuntimeOverride(
  base: ResolvedTheme,
  override: unknown,
): ResolvedTheme {
  if (override === undefined || override === null) {
    return base;
  }
  const node = asObject(override, '运行时覆盖');
  const tokens: Record<string, unknown> = {};
  const layout: Record<string, unknown> = {};
  let applied = false;
  for (const [key, value] of Object.entries(node)) {
    if (value === undefined || value === null) {
      continue;
    }
    if (OVERRIDE_TOKEN_KEYS.has(key)) {
      tokens[key] = value;
      applied = true;
      continue;
    }
    if (OVERRIDE_LAYOUT_KEYS.has(key)) {
      layout[key] = value;
      applied = true;
      continue;
    }
    // 未知字段（含 fontFamily）不是"忽略"而是拒绝：静默忽略会让宿主以为覆盖生效了
    return fail(`运行时覆盖不允许字段 ${key}`);
  }
  if (!applied) {
    return base;
  }
  const theme =
    Object.keys(tokens).length === 0
      ? base.theme
      : parseThemeTokens({ ...base.theme, ...tokens });
  const mergedLayout =
    Object.keys(layout).length === 0
      ? base.layout
      : parseThemeLayout({ ...base.layout, ...layout });
  return {
    layout: mergedLayout,
    overridden: true,
    source: base.source,
    theme,
  };
}

/** 字号档位 → 缩放系数。 */
function fontScaleFactor(scale: ThemeLayout['fontScale']): string {
  switch (scale) {
    case 'large': {
      return '1.125';
    }
    case 'small': {
      return '0.875';
    }
    default: {
      return '1';
    }
  }
}

/** 密度档位 → 间距系数。 */
function densityFactor(density: ThemeLayout['density']): string {
  return density === 'compact' ? '0.75' : '1';
}

/**
 * 主题 → CSS 自定义属性（宿主把返回值赋给容器 `style`）。
 *
 * <p>值全部来自已校验 token：颜色是十六进制、字体来自白名单、数值由本函数补单位或系数，
 * 因此"把主题写进 DOM"这条路不会带上任意 CSS 文本。
 */
export function themeCssVariables(
  resolved: ResolvedTheme,
): Record<string, string> {
  return {
    '--ai-color-scheme': resolved.theme.colorScheme ?? 'light',
    '--ai-density-factor': densityFactor(resolved.layout.density),
    '--ai-font-family': resolved.theme.fontFamily,
    '--ai-font-scale-factor': fontScaleFactor(resolved.layout.fontScale),
    '--ai-narrow-breakpoint': `${resolved.layout.narrowBreakpoint}px`,
    '--ai-primary-color': resolved.theme.primaryColor,
    '--ai-radius': `${resolved.theme.radius}px`,
    '--ai-sidebar-min-width': `${resolved.layout.minSidebarWidth}px`,
  };
}

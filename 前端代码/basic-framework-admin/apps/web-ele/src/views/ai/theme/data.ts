/**
 * 主题管理页面纯逻辑（C09）：状态文案、可发布/可回退判定、修订摘要与预览数据。
 *
 * <p>与渲染分离的原因：这些判定是"哪些按钮该出现"的依据，必须能逐条测试——
 * 把已发布修订的发布按钮留在界面上，会诱导运营点一个必然失败的按钮。
 */

function isPlainObject(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

/** 发布状态文案 */
export const PUBLICATION_STATE_TEXT: Record<string, string> = {
  DRAFT: '草稿',
  PUBLISHED: '当前生效',
  SUPERSEDED: '已被取代',
};

export function publicationText(state: string): string {
  return PUBLICATION_STATE_TEXT[state] ?? '未知状态';
}

/** 可否发布/回退：当前生效的修订不能再"发布"，其余（草稿/已被取代）都可以。 */
export function canPublish(state: string): boolean {
  return state !== 'PUBLISHED';
}

/** 发布按钮文案：已被取代的修订重新发布即"回退"。 */
export function publishActionText(state: string): string {
  return state === 'SUPERSEDED' ? '回退到此版本' : '发布';
}

/** 主题 token 的展示摘要（只读展示，不参与校验） */
export function tokenSummary(tokensJson: string): string {
  try {
    const parsed: unknown = JSON.parse(tokensJson);
    if (!isPlainObject(parsed)) {
      return '—';
    }
    const tokens = parsed as Record<string, unknown>;
    const parts: string[] = [];
    if (typeof tokens.primaryColor === 'string') {
      parts.push(`主色 ${tokens.primaryColor}`);
    }
    if (typeof tokens.radius === 'number') {
      parts.push(`圆角 ${tokens.radius}px`);
    }
    if (typeof tokens.colorScheme === 'string') {
      parts.push(tokens.colorScheme === 'dark' ? '深色' : '浅色');
    }
    return parts.length === 0 ? '—' : parts.join(' · ');
  } catch {
    return '—';
  }
}

/** 布局摘要：字号档位 / 密度 / 窄屏断点 */
export function layoutSummary(layoutJson: string): string {
  try {
    const parsed: unknown = JSON.parse(layoutJson);
    if (!isPlainObject(parsed)) {
      return '—';
    }
    const scale =
      typeof parsed.fontScale === 'string' ? parsed.fontScale : 'normal';
    const density =
      typeof parsed.density === 'string' ? parsed.density : 'normal';
    const narrow =
      typeof parsed.narrowBreakpoint === 'number'
        ? parsed.narrowBreakpoint
        : 768;
    return `字号 ${scale} · 密度 ${density} · 窄屏 ${narrow}px`;
  } catch {
    return '—';
  }
}

/** 页面菜单权限码（与 V79 菜单种子一致；仅用于菜单可见性） */
export const AI_THEME_PERMISSIONS = {
  create: 'ai:theme:create',
  publish: 'ai:theme:publish',
  query: 'ai:theme:query',
} as const;

/** 默认主题表单值（与平台默认一致；发布的是**新修订**，不会改动历史修订） */
export const DEFAULT_THEME_FORM = {
  colorScheme: 'light',
  fontFamily:
    'system-ui, -apple-system, "PingFang SC", "Microsoft YaHei", sans-serif',
  primaryColor: '#1677ff',
  radius: 6,
} as const;

/** 表单 → 提交用的规范化 JSON（键序固定，便于人工比对与审计） */
export function toTokensJson(form: {
  colorScheme?: string;
  fontFamily: string;
  primaryColor: string;
  radius: number;
}): string {
  const tokens: Record<string, unknown> = {
    primaryColor: form.primaryColor,
    radius: form.radius,
    fontFamily: form.fontFamily,
  };
  if (form.colorScheme) {
    tokens.colorScheme = form.colorScheme;
  }
  return JSON.stringify(tokens);
}

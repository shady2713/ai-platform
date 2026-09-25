/**
 * Vue 宿主的纯逻辑（C10）：与 HTML 宿主同一套流程，便于单测。
 *
 * 宿主只决定三件事：登记哪些路由、什么时候换票、切用户/切主题时做什么。
 * 桥协议与生命周期都由 `@vben/ai-embed-sdk` 提供，示例不重复实现。
 */
import type {
  ChatMount,
  ChatMountLayout,
  ChatMountOptions,
  Theme,
} from '@vben/ai-embed-sdk';

import {
  createBusinessContextStore,
  createHostEventHandlers,
} from '@vben/ai-embed-sdk';

/** 宿主登记的业务路由（未登记的路由名会被拒绝）。 */
export const VUE_HOST_ROUTES = {
  'order.detail': { params: { id: 'string' } },
  'report.list': { params: { page: 'number' } },
} as const;

/** 主题覆盖只允许规定字段（字体不在可覆盖范围内，见 C04）。 */
export const THEME_PRESETS: Record<
  'dark' | 'light',
  Pick<Theme, 'primaryColor' | 'radius'>
> = {
  dark: { primaryColor: '#7c3aed', radius: 8 },
  light: { primaryColor: '#1677ff', radius: 6 },
};

/** 组装挂载选项（允许域来自平台 Origin；iframe 指向平台自托管入口）。 */
export function buildVueHostOptions(input: {
  appCode: string;
  container: HTMLElement;
  embedBasePath: string;
  fetchTicket: ChatMountOptions['getAccessToken'];
  instanceId: string;
  layout: ChatMountLayout;
}): ChatMountOptions {
  const origin = new URL(input.embedBasePath).origin;
  return {
    allowedOrigins: [origin],
    appCode: input.appCode,
    container: input.container,
    frame: {
      create() {
        const frame = document.createElement('iframe');
        frame.src = `${input.embedBasePath}/app-api/ai/v1/embed/${input.appCode}`;
        frame.setAttribute('referrerpolicy', 'no-referrer');
        return frame;
      },
      destroy() {},
    },
    getAccessToken: input.fetchTicket,
    instanceId: input.instanceId,
    layout: input.layout,
    theme: { fontFamily: 'system-ui', ...THEME_PRESETS.light },
  } as ChatMountOptions;
}

/** 宿主侧状态：上下文仓库 + 事件校验器（两个宿主示例共用同一口径）。 */
export function createVueHostState(options: {
  onNavigate?: (
    route: string,
    params: Record<string, boolean | number | string>,
  ) => void;
  onReportCreated?: (reportId: string, version: number) => void;
}) {
  const contexts = createBusinessContextStore({ page: 'crm/order' });
  const events = createHostEventHandlers({
    onNavigate: ({ params, route }) => options.onNavigate?.(route, params),
    onReportCreated: ({ reportId, version }) =>
      options.onReportCreated?.(reportId, version),
    routes: { ...VUE_HOST_ROUTES },
  });
  return { contexts, events };
}

/** 切主题：只改外壳令牌，不重建实例、不丢会话与滚动（AT-054）。 */
export function applyTheme(
  mount: ChatMount,
  preset: keyof typeof THEME_PRESETS,
): void {
  const tokens = THEME_PRESETS[preset] ?? THEME_PRESETS.light;
  mount.updateTheme({ fontFamily: 'system-ui', ...tokens });
}

/** 切用户：销毁旧实例并清空上下文（旧响应在 SDK 内按代次丢弃）。 */
export function switchUser(
  mount: ChatMount,
  contexts: { clear: () => void },
): void {
  mount.destroy();
  contexts.clear();
}

import type { AiChatElement, GetAccessToken } from '@vben/ai-web-component';

import { THEME_PRESETS, VUE_HOST_ROUTES } from './host';

/**
 * Vue 宿主的组件路径（X09）：与 iframe 路径同一套宿主决定（路由表、换票、主题 tokens），
 * 差别只在承载方式——元素把 ChatUI 挂进 Shadow DOM，宿主不建 iframe。
 *
 * 组件产物与样式由示例的 dev/preview 服务器按固定命名从包产物目录提供
 * （见 `vite.config.mts` 的 `serveComponentArtifacts`）；生产部署时把同一目录托管在宿主的
 * 静态路径下，并用宿主自己的网关转发 `/app-api`（见接入指南）。
 */

/** 版本化组件产物（与 iframe SDK 同一口径：固定命名、自托管）。 */
export const COMPONENT_ARTIFACT_PATH = '/component/ai-web-component-5.6.0.js';

/** 组件路径的应用端基址：宿主自己的网关（dev 由 vite 代理到平台）。 */
export const COMPONENT_API_BASE = '/app-api';

/**
 * 组件路径的主题字体：组件会按平台白名单校验主题（字体必须自托管），
 * 与 iframe 路径"只写外壳令牌"的宽松口径不同——这是组件路径**更严格**的地方。
 */
export const COMPONENT_FONT_FAMILY =
  'system-ui, -apple-system, "PingFang SC", "Microsoft YaHei", sans-serif';

export interface ComponentHostAttributesOptions {
  apiBase?: string;
  appCode: string;
  instanceId: string;
  routes?: Record<string, unknown>;
  serviceId?: string;
  theme?: { primaryColor: string; radius: number };
}

/** 组装元素属性（与 HTML 示例同一口径；凭据只经换票回调进入组件内存）。 */
export function buildComponentAttributes({
  apiBase = COMPONENT_API_BASE,
  appCode,
  instanceId,
  routes = VUE_HOST_ROUTES,
  serviceId,
  theme = THEME_PRESETS.light,
}: ComponentHostAttributesOptions): Record<string, string> {
  return {
    'api-base-url': apiBase,
    'app-code': appCode,
    'instance-id': instanceId,
    ...(serviceId === undefined ? {} : { 'service-id': serviceId }),
    routes: JSON.stringify(routes),
    theme: JSON.stringify({ fontFamily: COMPONENT_FONT_FAMILY, ...theme }),
  };
}

export interface ComponentArtifactModule {
  defineAiChatElement(): void;
}

/** 加载版本化组件产物并取出注册函数（产物形状不符即明确失败，不静默降级）。 */
export async function loadComponentArtifact(
  importer: (path: string) => Promise<unknown> = (path) =>
    import(/* @vite-ignore */ path),
  path: string = COMPONENT_ARTIFACT_PATH,
): Promise<ComponentArtifactModule> {
  const loaded = (await importer(path)) as {
    defineAiChatElement?: unknown;
  };
  const define = loaded.defineAiChatElement;
  if (typeof define !== 'function') {
    throw new TypeError('组件产物未导出 defineAiChatElement（版本不匹配？）');
  }
  return { defineAiChatElement: define as () => void };
}

/** 换票回调：与 iframe 路径同一个签名，只向宿主自己的后端要票。 */
export function createComponentTicketProvider(
  fetchImpl: typeof fetch,
  endpoint = '/your-backend/ai-ticket',
): GetAccessToken {
  return async () => {
    const response = await fetchImpl(endpoint, { method: 'POST' });
    if (!response.ok) {
      throw new Error(`ticket ${response.status}`);
    }
    return response.json();
  };
}

/** 切用户：换代并重建界面（旧代次的响应被丢弃），与 iframe 路径的销毁+重建同口径。 */
export function switchComponentUser(element: AiChatElement): void {
  element.resetSession();
}

/**
 * `@vben/ai-web-component`：组件级 Chat 集成包（X09）。
 *
 * <p>与 iframe 嵌入（`@vben/ai-embed-sdk` 的 `createChatMount`）**同一份宿主契约**：
 * 应用标识 + 换票回调 + 主题 tokens + 登记路由与安全事件桥；差别只在承载方式——
 * 组件把现有 ChatUI 直接挂进宿主的 Shadow DOM（样式边界由组件负责）。
 *
 * <p>典型用法（宿主页面加载版本化产物后）：
 * ```ts
 * import { AiChatElement, defineAiChatElement } from '@vben/ai-web-component';
 * defineAiChatElement();
 * const chat = document.createElement('ai-chat-component');
 * chat.setAttribute('app-code', 'crm-portal');
 * chat.setAttribute('api-base-url', '/your-backend/app-api');
 * chat.setAttribute('routes', JSON.stringify({ 'order.detail': { params: { id: 'string' } } }));
 * chat.getAccessToken = () => fetch('/your-backend/ai-ticket', { method: 'POST' }).then((r) => r.json());
 * document.body.append(chat);
 * chat.open();
 * ```
 */
export { createComponentChatController } from './component/controller';
export type {
  ComponentChatController,
  ComponentChatControllerOptions,
} from './component/controller';
export {
  AI_CHAT_ELEMENT_TAG,
  DEFAULT_COMPONENT_MODE,
  generateInstanceId,
  parseAllowOrigins,
  parseComponentConfig,
  parseRoutes,
} from './config';
export type {
  ComponentChatConfig,
  ComponentChatMode,
  ComponentConfigErrorCode,
  ComponentConfigFailure,
  ComponentConfigInput,
  ComponentConfigOk,
  ComponentConfigResult,
} from './config';
export { AiChatElement, defineAiChatElement } from './element/ai-chat-element';
export type { GetAccessToken } from './element/ai-chat-element';
export {
  COMPONENT_STYLES,
  deriveSurfaceStylesUrl,
  installStyles,
  SURFACE_STYLE_LINK_TESTID,
  SURFACE_STYLE_TEXT_TESTID,
} from './element/styles';
export { createLocalPort } from './protocol/local-port';
export type {
  LocalPort,
  LocalPortEvent,
  LocalPortOptions,
  LocalPortTransport,
} from './protocol/local-port';
export {
  createComponentConversationApi,
  createComponentRunApi,
  numericId,
} from './runtime/ai-ports';
export { mountChatSurface } from './surface/mount-surface';
export type { ChatSurface, ChatSurfaceOptions } from './surface/mount-surface';
export { applyThemeVariables, themeVariables } from './surface/theme';

import type { ConversationApi, ConversationRunApi } from '@vben/ai-chat-ui';
import type { Theme } from '@vben/ai-contracts';

import { createApp } from 'vue';

import ChatSurface from './ChatSurface.vue';
import { applyThemeVariables } from './theme';

/**
 * 组件界面挂载（X09）：把一个 Vue 应用挂进 Shadow DOM 里的容器。
 *
 * <p>两条与 iframe 路径对齐的生命周期口径：
 * <ul>
 *   <li>界面**不随 open/close 重建**（关闭再打开保留会话与滚动位置，与 `createChatMount` 一致）；</li>
 *   <li>`destroy()` 幂等且彻底：卸载 Vue 应用（含其内部监听器与图表实例）、清空容器。</li>
 * </ul>
 */

export interface ChatSurface {
  destroy(): void;
  setTheme(theme: Theme | undefined): void;
}

export interface ChatSurfaceOptions {
  api: ConversationApi;
  container: HTMLElement;
  runApi: ConversationRunApi | null;
  serviceId: string;
  theme: Theme | undefined;
}

export function mountChatSurface(options: ChatSurfaceOptions): ChatSurface {
  applyThemeVariables(options.container, options.theme);
  const app = createApp(ChatSurface, {
    api: options.api,
    runApi: options.runApi,
    serviceId: options.serviceId,
  });
  app.mount(options.container);
  let destroyed = false;

  return {
    destroy(): void {
      if (destroyed) {
        return;
      }
      destroyed = true;
      app.unmount();
      options.container.replaceChildren();
    },
    setTheme(theme: Theme | undefined): void {
      if (destroyed) {
        return;
      }
      applyThemeVariables(options.container, theme);
    },
  };
}

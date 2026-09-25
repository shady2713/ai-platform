import type { BridgeMessage, Theme } from '@vben/ai-contracts';

import { createHostBridge } from '../bridge/host-bridge';

/**
 * Chat 展示形态与生命周期（C07）。
 *
 * <p>三种形态共用**一个** iframe 实例：`inline`（宿主页面里的一块）、`drawer`（侧栏）、`dialog`（弹窗）。
 * 形态切换只重排外壳，不重建 iframe——这是"主题切换与形态切换保留会话与滚动位置"（AT-054）的前提：
 * 一旦重建，会话、滚动位置与图表实例都会丢。
 *
 * <p>可访问性与生命周期（设计契约 8.2）：
 * <ul>
 *   <li>drawer/dialog 是**模态外壳**：打开时把焦点移入面板并限制 Tab 在内部循环，Esc 关闭；
 *       关闭后把焦点还给打开前的元素（AT-054 的"键盘可用"）；</li>
 *   <li>窄屏（视口宽度小于主题的 `narrowBreakpoint`）时 drawer/dialog 直接铺满视口，
 *       不做"半屏抽屉里再塞一个小面板"（AT-054 的窄屏口径）；</li>
 *   <li>高度**受限协商**：面板高度 = min(宿主给定上限, 视口可用高度)，超出部分由面板内部滚动，
 *       不把宿主的布局撑破；</li>
 *   <li>`destroy()` 清理外壳、监听器、iframe 与桥实例；重复 destroy 幂等，destroy 后可重新 mount
 *       （AT-055：无监听器/流/图表泄漏）。</li>
 * </ul>
 */

export type ChatDisplayMode = 'dialog' | 'drawer' | 'inline';

/** 主题里的布局令牌（与 C04 的 `ThemeLayout` 同值；只取本模块用到的两项）。 */
export interface ChatMountLayout {
  /** 窄屏之外的侧栏最小宽度（px） */
  minSidebarWidth: number;
  narrowBreakpoint: number;
}

/** iframe 端口：宿主提供真实实现（创建/销毁 iframe 与精确 targetOrigin 的 postMessage）。 */
export interface ChatFramePort {
  /** 创建并返回 iframe 元素（同一 mount 只调用一次）。 */
  create(): HTMLIFrameElement;
  destroy(): void;
}

export interface ChatMountOptions {
  allowedOrigins: string[];
  appCode: string;
  /** 承载面板的容器（inline 用它做布局上下文；drawer/dialog 把 overlay 追加到它里面）。 */
  container: HTMLElement;
  /** 注入 document（默认全局）；便于测试与多文档宿主。 */
  documentRef?: Document;
  frame: ChatFramePort;
  getAccessToken: () => Promise<{ expiresAt: string; token: string }>;
  instanceId: string;
  layout?: ChatMountLayout;
  /** 面板高度上限（px）；缺省为视口可用高度。 */
  maxHeight?: number;
  mode?: ChatDisplayMode;
  onError?: (error: { errorCode: string; message: string }) => void;
  serviceId?: null | string;
  theme?: Theme;
  /** 视口尺寸（默认取 window.innerWidth/innerHeight；测试可注入）。 */
  viewport?: () => { height: number; width: number };
}

export interface ChatMount {
  close(): void;
  destroy(): void;
  isOpen(): boolean;
  mode(): ChatDisplayMode;
  open(): void;
  setMode(mode: ChatDisplayMode): void;
  updateTheme(theme: Theme): void;
}

const DEFAULT_LAYOUT: ChatMountLayout = {
  minSidebarWidth: 360,
  narrowBreakpoint: 768,
};

/** 面板留白（模态形态的外边距，px）。 */
const MODAL_MARGIN = 16;

export function createChatMount(options: ChatMountOptions): ChatMount {
  const documentRef = options.documentRef ?? document;
  const layout = options.layout ?? DEFAULT_LAYOUT;
  const viewportOf =
    options.viewport ??
    (() => ({
      height: window.innerHeight,
      width: window.innerWidth,
    }));

  let mode: ChatDisplayMode = options.mode ?? 'inline';
  let open = false;
  let destroyed = false;
  let previouslyFocused: HTMLElement | null = null;
  let frame: HTMLIFrameElement | null = null;
  let overlay: HTMLElement | null = null;
  let panel: HTMLElement | null = null;
  let scrollHost: HTMLElement | null = null;

  const onKeyDown = (event: KeyboardEvent) => {
    if (!open) {
      return;
    }
    if (event.key === 'Escape') {
      event.preventDefault();
      close();
      return;
    }
    if (event.key === 'Tab') {
      trapFocus(event);
    }
  };

  function focusables(): HTMLElement[] {
    if (panel === null) {
      return [];
    }
    const selector =
      'a[href], button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])';
    return [...panel.querySelectorAll<HTMLElement>(selector)].filter(
      (element) => element.offsetParent !== null || element === panel,
    );
  }

  function trapFocus(event: KeyboardEvent): void {
    const candidates = focusables();
    if (candidates.length === 0) {
      // 没有可聚焦子元素时把焦点保持在面板本身，不让 Tab 逃到宿主页面
      event.preventDefault();
      panel?.focus();
      return;
    }
    const first = candidates[0] as HTMLElement;
    const last = candidates.at(-1) as HTMLElement;
    const active = documentRef.activeElement;
    if (event.shiftKey && (active === first || active === panel)) {
      event.preventDefault();
      last.focus();
      return;
    }
    if (!event.shiftKey && active === last) {
      event.preventDefault();
      first.focus();
    }
  }

  function applyChrome(): void {
    if (panel === null) {
      return;
    }
    const { height, width } = viewportOf();
    const narrow = mode !== 'inline' && width < layout.narrowBreakpoint;
    const available = Math.max(240, height - MODAL_MARGIN * 2);
    const panelHeight = Math.min(options.maxHeight ?? available, available);
    panel.dataset.mode = mode;
    panel.dataset.narrow = narrow ? 'true' : 'false';
    const drawerWidth = Math.max(
      layout.minSidebarWidth,
      Math.round(width * 0.4),
    );
    const dialogWidth = Math.min(width - MODAL_MARGIN * 2, 960);
    if (narrow) {
      panel.style.width = '100%';
    } else if (mode === 'drawer') {
      panel.style.width = `${drawerWidth}px`;
    } else {
      panel.style.width = `${dialogWidth}px`;
    }
    panel.style.height = narrow ? '100%' : `${panelHeight}px`;
    panel.style.maxHeight = narrow ? '100%' : `${panelHeight}px`;
    // 主题只作用于外壳的展示令牌（内部观感由 iframe 内的主题层负责）
    if (options.theme !== undefined) {
      panel.style.setProperty('--ai-primary-color', options.theme.primaryColor);
      panel.style.setProperty('--ai-radius', `${options.theme.radius}px`);
      panel.style.setProperty('--ai-font-family', options.theme.fontFamily);
    }
  }

  function ensureShell(): void {
    if (panel !== null) {
      return;
    }
    overlay = documentRef.createElement('div');
    overlay.className = `ai-chat-overlay ai-chat-overlay--${mode}`;
    overlay.dataset.testid = 'ai-chat-overlay';
    panel = documentRef.createElement('section');
    panel.className = `ai-chat-panel ai-chat-panel--${mode}`;
    panel.dataset.testid = 'ai-chat-panel';
    panel.tabIndex = -1;
    if (mode === 'inline') {
      panel.setAttribute('role', 'region');
      panel.setAttribute('aria-label', 'AI 助手');
    } else {
      panel.setAttribute('role', 'dialog');
      panel.setAttribute('aria-modal', 'true');
      panel.setAttribute('aria-label', 'AI 助手');
    }
    if (frame === null) {
      frame = options.frame.create();
      frame.setAttribute('title', 'AI 助手');
      frame.dataset.testid = 'ai-chat-frame';
    }
    scrollHost = documentRef.createElement('div');
    scrollHost.className = 'ai-chat-scroll';
    scrollHost.dataset.testid = 'ai-chat-scroll';
    scrollHost.style.overflow = 'auto';
    scrollHost.append(frame);
    panel.append(scrollHost);
    overlay.append(panel);
    options.container.append(overlay);
    documentRef.addEventListener('keydown', onKeyDown);
  }

  function openShell(): void {
    ensureShell();
    if (open) {
      return;
    }
    if (mode !== 'inline') {
      previouslyFocused = documentRef.activeElement as HTMLElement | null;
    }
    open = true;
    overlay?.removeAttribute('hidden');
    // 首次打开即开始握手（start 只在 CREATED 生效，重复打开不会重放 HELLO）
    bridge.start();
    applyChrome();
    if (mode !== 'inline') {
      // 模态形态：焦点移入面板（首个可聚焦元素，没有则面板本身）
      const candidates = focusables();
      (candidates[0] ?? panel)?.focus();
    }
  }

  function close(): void {
    if (!open || destroyed) {
      return;
    }
    open = false;
    overlay?.setAttribute('hidden', '');
    if (mode !== 'inline' && previouslyFocused !== null) {
      previouslyFocused.focus();
      previouslyFocused = null;
    }
  }

  // 视口变化时重算尺寸（有界高度协商与窄屏切换都依赖它）；销毁时解绑（AT-055）
  const onResize = () => applyChrome();
  const globalWindow: undefined | Window = documentRef.defaultView ?? undefined;
  globalWindow?.addEventListener('resize', onResize);

  const bridge = createHostBridge({
    allowedOrigins: options.allowedOrigins,
    appCode: options.appCode,
    getAccessToken: options.getAccessToken,
    instanceId: options.instanceId,
    onError: options.onError,
    serviceId: options.serviceId,
    theme: options.theme,
    transport: {
      destroy: () => options.frame.destroy(),
      post: (message: BridgeMessage) => {
        const origin = new URL(options.allowedOrigins[0] ?? 'https://localhost')
          .origin;
        // 精确 targetOrigin：绝不使用 '*'（宿主页面里可能有第三方内容）
        frame?.contentWindow?.postMessage(message, origin);
      },
      source: () => frame?.contentWindow ?? null,
    },
  });

  return {
    close,
    destroy(): void {
      if (destroyed) {
        return;
      }
      close();
      destroyed = true;
      globalWindow?.removeEventListener('resize', onResize);
      documentRef.removeEventListener('keydown', onKeyDown);
      bridge.destroy();
      overlay?.remove();
      overlay = null;
      panel = null;
      scrollHost = null;
      frame = null;
      previouslyFocused = null;
    },
    isOpen: () => open,
    mode: () => mode,
    open: openShell,
    setMode(next: ChatDisplayMode): void {
      if (destroyed || next === mode) {
        return;
      }
      mode = next;
      if (panel !== null) {
        panel.className = `ai-chat-panel ai-chat-panel--${mode}`;
        if (overlay !== null) {
          overlay.className = `ai-chat-overlay ai-chat-overlay--${mode}`;
        }
        panel.setAttribute('role', mode === 'inline' ? 'region' : 'dialog');
        if (mode === 'inline') {
          panel.removeAttribute('aria-modal');
        } else {
          panel.setAttribute('aria-modal', 'true');
        }
      }
      applyChrome();
    },
    updateTheme(theme: Theme): void {
      if (destroyed) {
        return;
      }
      options.theme = theme;
      // 只更新外观令牌：不重建 iframe、不重置滚动位置（AT-054）
      applyChrome();
    },
  };
}

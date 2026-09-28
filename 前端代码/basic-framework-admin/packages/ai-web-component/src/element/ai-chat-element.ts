import type { BusinessContext, Theme } from '@vben/ai-contracts';
import type { BridgeState, HostRouteRegistry } from '@vben/ai-embed-sdk';

import type { ComponentChatController } from '../component/controller';
import type { ComponentChatConfig, ComponentChatMode } from '../config';

import { parseThemeTokens } from '@vben/ai-chat-ui';

import { createComponentChatController } from '../component/controller';
import {
  AI_CHAT_ELEMENT_TAG,
  DEFAULT_COMPONENT_MODE,
  parseComponentConfig,
  parseRoutes,
} from '../config';
import { applyThemeVariables } from '../surface/theme';
import {
  COMPONENT_STYLES,
  deriveSurfaceStylesUrl,
  installStyles,
  SURFACE_STYLE_LINK_TESTID,
  SURFACE_STYLE_TEXT_TESTID,
} from './styles';

/**
 * `<ai-chat-component>`：组件级 Chat 集成的宿主入口（X09）。
 *
 * <p>宿主契约与 iframe 路径同一套（`ChatMountOptions` 的口径）：
 * <ul>
 *   <li><b>身份</b>：`app-code`（应用标识）+ `instance-id`（同页多实例隔离）；</li>
 *   <li><b>换票</b>：`getAccessToken` 属性（函数，向宿主自己的后端换票；票据只在内存）；</li>
 *   <li><b>主题</b>：`theme` 属性，tokens 与 iframe 路径同源（`Theme` 契约 + `--ai-*` 变量）；</li>
 *   <li><b>形态与生命周期</b>：`open/close/setMode/updateTheme/destroy`，destroy 幂等且是单向终态；</li>
 *   <li><b>安全事件桥</b>：`requestNavigate` 走宿主登记的**路由名 + 类型化参数**校验，
 *       未登记一律拒绝（`ai-rejected`）；上报事件是元素上的 DOM 事件（不占用 window）。</li>
 * </ul>
 *
 * <p>样式边界：组件自带 Shadow DOM，宿主 CSS 不会破坏组件内部交互（详见 `styles.ts`）。
 */
export type GetAccessToken = () => Promise<{
  expiresAt: string;
  token: string;
}>;

/** 已连接判定的视口宽度（与主题布局令牌的 narrowBreakpoint 同值） */
const NARROW_BREAKPOINT = 768;

const OBSERVED_ATTRIBUTES = [
  'allow-origins',
  'api-base-url',
  'app-code',
  'instance-id',
  'max-height',
  'mode',
  'open',
  'routes',
  'service-id',
  'styles-url',
  'theme',
] as const;

export class AiChatElement extends HTMLElement {
  static readonly tagName = AI_CHAT_ELEMENT_TAG;

  static get observedAttributes(): string[] {
    return [...OBSERVED_ATTRIBUTES];
  }

  get appCode(): string {
    return this.getAttribute('app-code') ?? '';
  }

  /** 宿主换票回调（与 iframe 路径同一签名）。 */
  get getAccessToken(): GetAccessToken | undefined {
    return this.accessTokenProvider;
  }

  set getAccessToken(provider: GetAccessToken | undefined) {
    this.accessTokenProvider = provider;
  }

  get instanceId(): string {
    return this.getAttribute('instance-id') ?? '';
  }

  /** 已登记的业务路由（对象形式；就地更新即生效）。 */
  get routes(): HostRouteRegistry | undefined {
    return this.routesOverride;
  }

  set routes(registry: HostRouteRegistry | undefined) {
    this.routesOverride = registry;
    this.syncRoutes();
  }

  get serviceId(): null | string {
    return this.getAttribute('service-id');
  }

  /**
   * ChatUI 的样式文本（源码导入的宿主用它把样式装进 Shadow DOM）。
   *
   * <p>产物加载（推荐）时不需要设置：元素会按 `import.meta.url` 找到同目录的版本化 `.css`。
   * 源码导入（被打进宿主自己的包）时样式不会自动出现在 Shadow DOM 里，宿主可以：
   * 用 `styles-url` 指向自托管的 CSS，或直接把 CSS 文本赋给本属性。
   */
  get surfaceStyles(): string | undefined {
    return this.surfaceStylesText;
  }

  set surfaceStyles(value: string | undefined) {
    this.surfaceStylesText = value;
    this.installSurfaceStyles();
  }

  /** 主题令牌（对象形式；可在连接后更新，只改观感）。 */
  get theme(): Theme | undefined {
    return this.themeOverride;
  }

  set theme(value: Theme | undefined) {
    this.themeOverride = value;
    this.applyTheme();
  }

  private accessTokenProvider: GetAccessToken | undefined;

  private container: HTMLElement | undefined;

  private controller: ComponentChatController | undefined;

  /** 宿主显式 destroy：此后不再自动初始化（重新挂载必须新建元素） */
  private destroyedByHost = false;

  private mode: ComponentChatMode = DEFAULT_COMPONENT_MODE;

  private openState = false;

  private panel: HTMLElement | undefined;

  private previouslyFocused: HTMLElement | null = null;

  private routesOverride: HostRouteRegistry | undefined;

  /** 活的路由登记表：`routes` 属性/属性更新就地生效（控制器持有同一个对象） */
  private readonly routesRegistry: HostRouteRegistry = {};

  private statusNode: HTMLElement | undefined;

  private styleLink: HTMLLinkElement | undefined;

  private styleText: HTMLStyleElement | undefined;

  private surfaceContainer: HTMLElement | undefined;

  private surfaceStylesText: string | undefined;

  private themeOverride: Theme | undefined;

  attributeChangedCallback(
    name: string,
    _oldValue: null | string,
    newValue: null | string,
  ): void {
    if (name === 'mode') {
      this.mode = isMode(newValue) ? newValue : DEFAULT_COMPONENT_MODE;
      this.syncChrome();
      return;
    }
    if (name === 'open') {
      this.openState = newValue !== null;
      this.syncOpen();
      return;
    }
    if (name === 'theme') {
      this.themeOverride = undefined;
      this.applyTheme();
      return;
    }
    if (name === 'routes') {
      this.routesOverride = undefined;
      this.syncRoutes();
      return;
    }
    if (name === 'styles-url') {
      this.styleLink?.remove();
      this.styleLink = undefined;
      this.installSurfaceStyles();
      return;
    }
    // 身份/基址/服务等参数变化：已初始化的实例必须重建（不半边换配置）
    if (this.controller !== undefined) {
      this.teardown();
      if (this.openState) {
        this.initialize();
        this.controller?.start();
      }
    }
  }

  close(): void {
    this.removeAttribute('open');
  }

  connectedCallback(): void {
    if (this.destroyedByHost) {
      return;
    }
    this.ensureShell();
    if (this.openState) {
      this.initialize();
      this.controller?.start();
    }
    this.syncChrome();
  }

  destroy(): void {
    if (this.destroyedByHost) {
      return;
    }
    this.destroyedByHost = true;
    this.teardown();
    this.setStatus('已销毁', 'info');
    this.dispatch('ai-destroyed', { instanceId: this.instanceId });
  }

  disconnectedCallback(): void {
    // 元素被移出文档（或页面卸载）：释放监听器、桥与 Vue 应用；未显式 destroy 时允许重新连接
    this.teardown();
  }

  isOpen(): boolean {
    return this.openState;
  }

  notifyReportCreated(event: {
    reportId: string;
    title?: string;
    version: number;
  }): void {
    this.controller?.notifyReportCreated(event);
  }

  open(): void {
    this.setAttribute('open', '');
  }

  requestNavigate(
    route: string,
    params?: Record<string, boolean | number | string>,
  ): void {
    this.controller?.requestNavigate(route, params);
  }

  /** 宿主切用户：换代并重建界面（旧代次的响应一律丢弃）。 */
  resetSession(): void {
    this.controller?.resetSession();
  }

  setMode(mode: ComponentChatMode): void {
    this.setAttribute('mode', mode);
  }

  state(): BridgeState {
    return this.controller?.state() ?? 'CREATED';
  }

  updateContext(input: unknown): BusinessContext | null {
    return this.controller?.updateContext(input) ?? null;
  }

  updateTheme(theme: Theme | undefined): void {
    this.theme = theme;
  }

  /**
   * 当前焦点元素：焦点在 Shadow DOM 内时，`document.activeElement` 只会给出宿主元素，
   * 必须读 `shadowRoot.activeElement` 才能拿到真正的内部焦点（否则焦点陷阱会失效）。
   */
  private activeElement(): Element | null {
    return this.shadowRoot?.activeElement ?? this.ownerDocument.activeElement;
  }

  private applyTheme(): void {
    const wrapper = this.container;
    if (wrapper === undefined) {
      return;
    }
    let theme: Theme | undefined;
    if (this.themeOverride === undefined) {
      const raw = this.getAttribute('theme');
      if (raw !== null && raw.trim() !== '') {
        try {
          const parsed: unknown = JSON.parse(raw);
          theme = parseThemeTokens(parsed);
        } catch {
          // 非法主题：明确报错，不把未校验的值写进样式
          this.dispatch('ai-error', {
            errorCode: 'THEME_INVALID',
            message: '主题不是合法的 token 对象',
          });
          this.setStatus('主题不合规', 'error');
          return;
        }
      }
    } else {
      theme = this.themeOverride;
    }
    applyThemeVariables(wrapper, theme);
    this.controller?.updateTheme(theme ?? undefined);
  }

  private dispatch(name: string, detail: unknown): void {
    this.dispatchEvent(
      new CustomEvent(name, { bubbles: true, composed: true, detail }),
    );
  }

  private ensureShell(): void {
    if (this.container !== undefined) {
      return;
    }
    const root = this.shadowRoot ?? this.attachShadow({ mode: 'open' });
    installStyles(root, COMPONENT_STYLES);

    const wrapper = this.ownerDocument.createElement('div');
    wrapper.className = 'ai-web-component';
    wrapper.dataset.testid = AI_CHAT_ELEMENT_TAG;

    const panel = this.ownerDocument.createElement('section');
    panel.className = 'ai-web-component__panel';
    panel.dataset.testid = `${AI_CHAT_ELEMENT_TAG}-panel`;
    panel.tabIndex = -1;

    const header = this.ownerDocument.createElement('header');
    header.className = 'ai-web-component__header';
    const title = this.ownerDocument.createElement('span');
    title.className = 'ai-web-component__title';
    title.textContent = 'AI 助手';
    const status = this.ownerDocument.createElement('span');
    status.className = 'ai-web-component__status';
    status.dataset.testid = `${AI_CHAT_ELEMENT_TAG}-status`;
    status.setAttribute('role', 'status');
    const close = this.ownerDocument.createElement('button');
    close.className = 'ai-web-component__close';
    close.dataset.testid = `${AI_CHAT_ELEMENT_TAG}-close`;
    close.setAttribute('aria-label', '关闭');
    close.setAttribute('type', 'button');
    close.textContent = '关闭';
    close.addEventListener('click', () => this.close());
    header.append(title, status, close);

    const surface = this.ownerDocument.createElement('div');
    surface.className = 'ai-web-component__surface';
    surface.dataset.testid = `${AI_CHAT_ELEMENT_TAG}-surface`;

    panel.append(header, surface);
    wrapper.append(panel);
    root.append(wrapper);

    this.container = wrapper;
    this.panel = panel;
    this.surfaceContainer = surface;
    this.statusNode = status;
    this.syncRoutes();
    this.installSurfaceStyles();
    this.applyTheme();
    this.setStatus('未连接', 'info');
    this.syncChrome();
  }

  private focusables(): HTMLElement[] {
    const panel = this.panel;
    if (panel === undefined) {
      return [];
    }
    const selector =
      'a[href], button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])';
    const inner = [...panel.querySelectorAll<HTMLElement>(selector)].filter(
      (element) => isVisible(element),
    );
    // 没有可聚焦子元素时用面板自身兜底：Tab 不许逃到宿主页面
    return inner.length > 0 ? inner : [panel];
  }

  private handleKeydown(event: KeyboardEvent): void {
    if (!this.openState || this.mode === 'inline') {
      return;
    }
    if (event.key === 'Escape') {
      event.preventDefault();
      this.close();
      return;
    }
    if (event.key === 'Tab') {
      this.trapFocus(event);
    }
  }

  private initialize(): void {
    if (this.controller !== undefined || this.destroyedByHost) {
      return;
    }
    const result = parseComponentConfig({
      allowOrigins: this.getAttribute('allow-origins'),
      apiBaseUrl: this.getAttribute('api-base-url'),
      appCode: this.getAttribute('app-code'),
      instanceId: this.getAttribute('instance-id'),
      maxHeight: this.getAttribute('max-height'),
      mode: this.mode,
      routes: this.routesOverride ?? this.getAttribute('routes'),
      serviceId: this.getAttribute('service-id'),
      theme: this.themeOverride ?? this.getAttribute('theme'),
    });
    if (!result.ok) {
      // 参数不可用即明确失败：不半启动、不用默认值掩盖漏配
      this.setStatus(result.message, 'error');
      this.dispatch('ai-error', {
        errorCode: result.errorCode,
        message: result.message,
      });
      return;
    }
    const config: ComponentChatConfig = {
      ...result.config,
      // 路由表用元素持有的活对象：`routes` 属性/属性更新就地生效
      routes: this.routesRegistry,
    };
    this.syncRoutes();
    const container = this.surfaceContainer;
    if (container === undefined) {
      return;
    }
    this.controller = createComponentChatController({
      config,
      container,
      getAccessToken:
        this.accessTokenProvider ??
        (() =>
          Promise.reject(new Error('宿主未提供 getAccessToken（换票回调）'))),
      onDestroyed: () => {
        this.setStatus('已断开', 'info');
      },
      onError: (error) => {
        this.setStatus(error.message, 'error');
        this.dispatch('ai-error', error);
      },
      onNavigateAccepted: (event) => {
        this.setStatus(`导航请求：${event.route}`, 'info');
        this.dispatch('ai-navigate-request', {
          params: event.params,
          route: event.route,
        });
      },
      onReady: (payload) => {
        this.setStatus('已连接', 'info');
        this.dispatch('ai-ready', payload);
      },
      onRejected: (reason) => {
        this.dispatch('ai-rejected', { reason });
      },
      onReportCreated: (event) => {
        this.dispatch('ai-report-created', event);
      },
      onTheme: (theme) => {
        this.themeOverride = theme;
      },
    });
    this.setStatus('连接中…', 'info');
  }

  /**
   * 把 ChatUI 的样式装进 Shadow DOM（宿主文档级样式进不来，必须自己带）。
   *
   * <p>优先级：`surfaceStyles` 文本（源码导入的宿主）> `styles-url` 属性（自托管地址）
   * > 版本化产物同目录同名 `.css`（`import.meta.url` 推导）。三者都没有时不注入：
   * 不会把宿主的样式表误当成组件样式。
   */
  private installSurfaceStyles(): void {
    const root = this.shadowRoot;
    if (root === null) {
      return;
    }
    const text = this.surfaceStylesText?.trim();
    if (text !== undefined && text !== '') {
      // 文本路径用 <style> 元素：可替换、可断言（严格 CSP 的宿主请用 styles-url/产物路径）
      this.styleText?.remove();
      const style = this.ownerDocument.createElement('style');
      style.dataset.testid = SURFACE_STYLE_TEXT_TESTID;
      // 只赋文本（不解析 HTML），装载内容完全由宿主给出的字符串决定
      style.textContent = text;
      this.styleText = style;
      root.append(style);
      return;
    }
    // 文本被清空：移除旧的文本节点，回到链接/产物路径
    this.styleText?.remove();
    this.styleText = undefined;
    if (this.styleLink !== undefined) {
      return;
    }
    const explicit = this.getAttribute('styles-url')?.trim();
    const href =
      explicit === undefined || explicit === ''
        ? deriveSurfaceStylesUrl(import.meta.url)
        : explicit;
    if (href === undefined) {
      return;
    }
    const link = this.ownerDocument.createElement('link');
    link.rel = 'stylesheet';
    link.href = href;
    link.dataset.testid = SURFACE_STYLE_LINK_TESTID;
    // 样式资源加载失败不该让组件不可用：移除链接，交互与外壳样式照常
    link.addEventListener('error', () => {
      link.remove();
      if (this.styleLink === link) {
        this.styleLink = undefined;
      }
    });
    this.styleLink = link;
    root.append(link);
  }

  private readonly onKeydown = (event: KeyboardEvent): void => {
    this.handleKeydown(event);
  };

  private readonly onResize = (): void => {
    this.syncChrome();
  };

  private setStatus(text: string, tone: 'error' | 'info'): void {
    if (this.statusNode === undefined) {
      return;
    }
    this.statusNode.textContent = text;
    this.statusNode.dataset.tone = tone;
  }

  private syncChrome(): void {
    const wrapper = this.container;
    const panel = this.panel;
    if (wrapper === undefined || panel === undefined) {
      return;
    }
    wrapper.dataset.mode = this.mode;
    wrapper.dataset.open = String(this.openState);
    const maxHeight = this.getAttribute('max-height');
    if (maxHeight !== null && /^\d{1,4}$/u.test(maxHeight.trim())) {
      // 只接受纯数值：不把任意文本写进样式（校验器随后还会按区间再判一次）
      panel.style.setProperty('--ai-max-height', `${maxHeight.trim()}px`);
    }
    const narrow =
      this.mode !== 'inline' &&
      (this.ownerDocument.defaultView?.innerWidth ?? NARROW_BREAKPOINT + 1) <
        NARROW_BREAKPOINT;
    wrapper.dataset.narrow = String(narrow);
    if (this.mode === 'inline') {
      panel.setAttribute('role', 'region');
      panel.removeAttribute('aria-modal');
    } else {
      panel.setAttribute('role', 'dialog');
      panel.setAttribute('aria-modal', 'true');
    }
  }

  private syncOpen(): void {
    this.syncChrome();
    if (this.destroyedByHost) {
      this.openState = false;
      this.removeAttribute('open');
      return;
    }
    if (this.openState) {
      this.initialize();
      this.controller?.start();
    }
    this.toggleGlobalListeners(this.openState && this.mode !== 'inline');
    if (this.openState && this.mode !== 'inline') {
      this.previouslyFocused = this.ownerDocument
        .activeElement as HTMLElement | null;
      // 模态形态：焦点移入面板（首个可聚焦元素，没有则面板本身）
      (this.focusables()[0] ?? this.panel)?.focus();
      return;
    }
    if (!this.openState && this.previouslyFocused !== null) {
      this.previouslyFocused.focus();
      this.previouslyFocused = null;
    }
  }

  private syncRoutes(): void {
    for (const key of Object.keys(this.routesRegistry)) {
      Reflect.deleteProperty(this.routesRegistry, key);
    }
    if (this.routesOverride !== undefined) {
      for (const [route, definition] of Object.entries(this.routesOverride)) {
        this.routesRegistry[route] = definition;
      }
      return;
    }
    const raw = this.getAttribute('routes');
    if (raw === null || raw.trim() === '') {
      return;
    }
    const parsed = parseRoutes(raw);
    if (!parsed.ok) {
      this.dispatch('ai-error', {
        errorCode: parsed.errorCode,
        message: parsed.message,
      });
      return;
    }
    for (const [route, definition] of Object.entries(parsed.routes)) {
      this.routesRegistry[route] = definition;
    }
  }

  private teardown(): void {
    this.toggleGlobalListeners(false);
    this.controller?.destroy();
    this.controller = undefined;
    this.previouslyFocused = null;
  }

  private toggleGlobalListeners(active: boolean): void {
    const target = this.ownerDocument;
    if (active) {
      target.addEventListener('keydown', this.onKeydown);
      this.ownerDocument.defaultView?.addEventListener('resize', this.onResize);
      return;
    }
    target.removeEventListener('keydown', this.onKeydown);
    this.ownerDocument.defaultView?.removeEventListener(
      'resize',
      this.onResize,
    );
  }

  private trapFocus(event: KeyboardEvent): void {
    const candidates = this.focusables();
    if (candidates.length === 0) {
      event.preventDefault();
      this.panel?.focus();
      return;
    }
    const first = candidates[0] as HTMLElement;
    const last = candidates.at(-1) as HTMLElement;
    const active = this.activeElement();
    if (event.shiftKey && (active === first || active === this.panel)) {
      event.preventDefault();
      last.focus();
      return;
    }
    if (!event.shiftKey && active === last) {
      event.preventDefault();
      first.focus();
    }
  }
}

/** 元素是否可获得焦点（可见性口径与 `createChatMount` 相同：有布局盒或非空客户区）。 */
export function isVisible(element: HTMLElement): boolean {
  return element.offsetParent !== null || element.getClientRects().length > 0;
}

function isMode(value: null | string): value is ComponentChatMode {
  return value === 'dialog' || value === 'drawer' || value === 'inline';
}

/** 注册自定义元素（重复调用幂等；不同标签名可注册多份）。 */
export function defineAiChatElement(
  tagName: string = AI_CHAT_ELEMENT_TAG,
): void {
  if (customElements.get(tagName) === undefined) {
    customElements.define(tagName, AiChatElement);
  }
}

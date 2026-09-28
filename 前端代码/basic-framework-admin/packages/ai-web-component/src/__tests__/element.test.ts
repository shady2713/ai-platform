import { beforeEach, describe, expect, it, vi } from 'vitest';

import {
  AiChatElement,
  defineAiChatElement,
  isVisible,
} from '../element/ai-chat-element';
import { COMPONENT_STYLES } from '../element/styles';
import {
  CLOSE_BUTTON,
  COMPOSER_INPUT,
  COMPOSER_SEND,
  CONVERSATION,
  createComponent,
  createTicketProvider,
  defineComponent,
  flushBridge,
  installFetch,
  PANEL,
  queryShadow,
  STATUS,
  SURFACE,
  TEST_APP_CODE,
} from './support/component-harness';

function listen<K extends keyof HTMLElementEventMap>(
  target: EventTarget,
  type: K | string,
): CustomEvent[] {
  const events: CustomEvent[] = [];
  target.addEventListener(type, (event) => {
    events.push(event as CustomEvent);
  });
  return events;
}

describe('组件元素的定义与连接（X09）', () => {
  beforeEach(() => {
    vi.unstubAllGlobals();
    defineComponent();
  });

  it('defineAiChatElement 幂等，且元素被升级为 AiChatElement', () => {
    expect(() => {
      defineAiChatElement();
      defineAiChatElement();
    }).not.toThrow();
    const element = document.createElement(
      'ai-chat-component',
    ) as AiChatElement;
    expect(element).toBeInstanceOf(AiChatElement);
    expect(customElements.get('ai-chat-component')).toBe(AiChatElement);
  });

  it('isVisible：有布局盒或客户区即视为可见（焦点候选的判定口径）', () => {
    const button = document.createElement('button');
    document.body.append(button);
    expect(isVisible(button)).toBe(true);
    Object.defineProperty(button, 'offsetParent', {
      configurable: true,
      value: null,
    });
    Object.defineProperty(button, 'getClientRects', {
      configurable: true,
      value: () => [],
    });
    expect(isVisible(button)).toBe(false);
  });

  it('未打开时不发起握手（不发票据、不建桥）', async () => {
    const ticket = createTicketProvider();
    const { element, shadow } = createComponent({ ticket });
    expect(element.isOpen()).toBe(false);
    expect(shadow.querySelector(PANEL)).not.toBeNull();
    expect(queryShadow(shadow, STATUS)?.textContent).toBe('未连接');
    await flushBridge();
    expect(ticket).not.toHaveBeenCalled();
    expect(element.state()).toBe('CREATED');
  });

  it('打开后：换票一次、桥进入 INITIALIZED、面板挂载并上报 ai-ready', async () => {
    const fetchHarness = installFetch();
    const { element, getAccessToken, shadow } = createComponent({
      instanceId: 'inst-1',
      open: true,
      routes: { 'order.detail': { params: { id: 'string' } } },
      serviceId: 'svc_1',
    });
    const ready = listen(element, 'ai-ready');

    await vi.waitFor(() => {
      expect(getAccessToken).toHaveBeenCalledTimes(1);
    });
    await vi.waitFor(() => {
      expect(queryShadow(shadow, CONVERSATION)).not.toBeNull();
    });

    expect(element.state()).toBe('INITIALIZED');
    expect(queryShadow(shadow, STATUS)?.textContent).toBe('已连接');
    expect(ready).toHaveLength(1);
    expect(ready[0]?.detail).toEqual({
      appCode: TEST_APP_CODE,
      instanceId: 'inst-1',
      serviceId: 'svc_1',
    });
    expect(ready[0]?.bubbles).toBe(true);
    expect(ready[0]?.composed).toBe(true);
    // 挂载即加载会话列表：票据只进请求头
    await vi.waitFor(() => {
      expect(fetchHarness.requests.length).toBeGreaterThan(0);
    });
    const listRequest = fetchHarness.requests[0];
    expect(listRequest?.url).toContain('/ai/conversation/page');
    expect(listRequest?.headers.authorization).toMatch(/^Bearer /u);
    // 重复 open 不重放 HELLO、不重复换票
    element.open();
    element.close();
    element.open();
    await flushBridge();
    expect(getAccessToken).toHaveBeenCalledTimes(1);
  });

  it('在面板里输入并发送：走开放 API 的受理端点（组件自带认证与交互）', async () => {
    const fetchHarness = installFetch();
    fetchHarness.respond('/ai/run/accept', () => [
      200,
      {
        code: 0,
        data: {
          reused: false,
          runId: 7,
          runKey: 'run_0007',
          status: 'QUEUED',
        },
        msg: '',
      },
    ]);
    const { element, shadow } = createComponent({
      open: true,
      serviceId: 'svc_7',
    });
    await vi.waitFor(() => {
      expect(queryShadow(shadow, COMPOSER_INPUT)).not.toBeNull();
    });
    const input = queryShadow<HTMLInputElement>(shadow, COMPOSER_INPUT);
    const send = queryShadow<HTMLButtonElement>(shadow, COMPOSER_SEND);
    if (input === null || send === null) {
      throw new Error('会话面板未挂载');
    }
    input.value = '帮我查一下订单';
    input.dispatchEvent(new Event('input'));
    send.click();

    await vi.waitFor(() => {
      expect(
        fetchHarness.requests.some((request) =>
          request.url.includes('/ai/run/accept'),
        ),
      ).toBe(true);
    });
    const accept = fetchHarness.requests.find((request) =>
      request.url.includes('/ai/run/accept'),
    );
    expect(accept?.method).toBe('POST');
    expect(accept?.headers.authorization).toMatch(/^Bearer /u);
    expect(accept?.body).toContain('帮我查一下订单');
    expect(element.isOpen()).toBe(true);
  });

  it('宿主样式不进 Shadow DOM：内部节点不可被宿主选择器命中，且交互仍然可用', async () => {
    installFetch();
    // 敌意宿主样式：通配隐藏、字号放大、字号归零、可见性隐藏
    const hostile = document.createElement('style');
    hostile.textContent =
      '* { display: none !important; font-size: 0; visibility: hidden; }';
    document.head.append(hostile);

    const { element, shadow } = createComponent({ open: true });
    await vi.waitFor(() => {
      expect(queryShadow(shadow, CONVERSATION)).not.toBeNull();
    });

    // 宿主的通配选择器只覆盖宿主的文档树：组件内部节点不在 `document.querySelector` 的作用域里
    expect(document.querySelector('.ai-web-component__panel')).toBeNull();
    expect(document.querySelector(PANEL)).toBeNull();
    // 组件自己的样式写在 Shadow DOM 内（constructable sheet 或 <style> 元素）
    expect(
      shadow.adoptedStyleSheets.length +
        shadow.querySelectorAll('style').length,
    ).toBeGreaterThan(0);
    // 关键交互仍然可用（事件与状态不依赖宿主样式）
    expect(queryShadow(shadow, PANEL)).not.toBeNull();
    expect(element.isOpen()).toBe(true);
    element.close();
    expect(element.isOpen()).toBe(false);
    hostile.remove();
  });

  it('组件样式自带交互硬化，且不引用任何外部资源', () => {
    // 继承属性是宿主唯一能"渗进来"的通道：这些属性必须在组件样式里被重置
    expect(COMPONENT_STYLES).toContain('visibility: visible');
    expect(COMPONENT_STYLES).toContain('pointer-events: auto');
    expect(COMPONENT_STYLES).toContain('font-size: 14px');
    expect(COMPONENT_STYLES).toContain('font: inherit');
    expect(COMPONENT_STYLES).not.toMatch(/@import/u);
    expect(COMPONENT_STYLES).not.toMatch(/url\(/u);
  });

  it('主题：tokens 落到 --ai-* 变量，更新主题不重建面板', async () => {
    installFetch();
    const { element, shadow } = createComponent({ open: true });
    await vi.waitFor(() => {
      expect(queryShadow(shadow, CONVERSATION)).not.toBeNull();
    });
    const wrapper = queryShadow(shadow, '[data-testid="ai-chat-component"]');
    const surfaceBefore = queryShadow(shadow, SURFACE);

    element.updateTheme({
      fontFamily:
        'system-ui, -apple-system, "PingFang SC", "Microsoft YaHei", sans-serif',
      primaryColor: '#16a34a',
      radius: 12,
    });

    expect(wrapper?.style.getPropertyValue('--ai-primary-color')).toBe(
      '#16a34a',
    );
    expect(wrapper?.style.getPropertyValue('--ai-radius')).toBe('12px');
    expect(queryShadow(shadow, SURFACE)).toBe(surfaceBefore);
  });

  it('非法主题属性：给出稳定错误事件，不把未校验的值写进样式', () => {
    const { element, shadow } = createComponent();
    const errors = listen(element, 'ai-error');
    element.setAttribute('theme', '{"primaryColor":"red"}');
    expect(
      errors.map((event) => (event.detail as { errorCode: string }).errorCode),
    ).toEqual(['THEME_INVALID']);
    expect(queryShadow(shadow, STATUS)?.textContent).toBe('主题不合规');
  });

  it('参数不可用：明确失败（错误码 + 面板文案），且不换票', async () => {
    const ticket = createTicketProvider();
    const element = document.createElement(
      'ai-chat-component',
    ) as AiChatElement;
    element.setAttribute('api-base-url', '/app-api');
    element.getAccessToken = ticket;
    const errors = listen(element, 'ai-error');
    document.body.append(element);
    element.open();

    await flushBridge();
    expect(
      errors.map((event) => (event.detail as { errorCode: string }).errorCode),
    ).toContain('APP_CODE_REQUIRED');
    expect(ticket).not.toHaveBeenCalled();
    expect(element.state()).toBe('CREATED');
  });

  it('跨源基址未登记：拒绝启动（默认不信任跨源）', async () => {
    const ticket = createTicketProvider();
    const element = document.createElement(
      'ai-chat-component',
    ) as AiChatElement;
    element.setAttribute('app-code', 'crm-portal');
    element.setAttribute('api-base-url', 'https://other.example.com/app-api');
    element.getAccessToken = ticket;
    const errors = listen(element, 'ai-error');
    document.body.append(element);
    element.open();
    await flushBridge();
    expect(
      errors.map((event) => (event.detail as { errorCode: string }).errorCode),
    ).toContain('API_ORIGIN_NOT_ALLOWED');
    expect(ticket).not.toHaveBeenCalled();
  });

  it('宿主未提供换票回调：桥以 TOKEN_UNAVAILABLE 失败（不返回假票据）', async () => {
    const element = document.createElement(
      'ai-chat-component',
    ) as AiChatElement;
    element.setAttribute('app-code', 'crm-portal');
    element.setAttribute('api-base-url', '/app-api');
    const errors = listen(element, 'ai-error');
    document.body.append(element);
    element.open();
    await vi.waitFor(() => {
      expect(errors.length).toBeGreaterThan(0);
    });
    expect((errors[0]?.detail as { errorCode: string }).errorCode).toBe(
      'TOKEN_UNAVAILABLE',
    );
    expect(element.state()).not.toBe('INITIALIZED');
  });

  it('导航与报表事件：未登记路由被拒（稳定原因），登记路由放行', async () => {
    installFetch();
    const { element } = createComponent({
      open: true,
      routes: { 'order.detail': { params: { id: 'string' } } },
    });
    const accepted = listen(element, 'ai-navigate-request');
    const rejected = listen(element, 'ai-rejected');
    const reports = listen(element, 'ai-report-created');

    await vi.waitFor(() => {
      expect(element.state()).toBe('INITIALIZED');
    });

    element.requestNavigate('order.detail', { id: 'order-1' });
    expect(accepted).toHaveLength(1);
    expect(accepted[0]?.detail).toEqual({
      params: { id: 'order-1' },
      route: 'order.detail',
    });

    // 未登记路由与未声明参数：拒绝，不执行
    element.requestNavigate('evil.route');
    element.requestNavigate('order.detail', { id: 'order-1', url: 'x' });
    expect(
      rejected.map((event) => (event.detail as { reason: string }).reason),
    ).toEqual(['ROUTE_NOT_REGISTERED', 'PARAM_NOT_REGISTERED:url']);
    expect(accepted).toHaveLength(1);

    element.notifyReportCreated({ reportId: 'rpt_abc123', version: 1 });
    expect(reports).toHaveLength(1);
    expect(reports[0]?.detail).toMatchObject({ reportId: 'rpt_abc123' });
  });

  it('路由表就地更新：新增登记立即生效', async () => {
    installFetch();
    const { element } = createComponent({ open: true });
    const accepted = listen(element, 'ai-navigate-request');
    await vi.waitFor(() => {
      expect(element.state()).toBe('INITIALIZED');
    });
    element.requestNavigate('order.detail', { id: 'order-1' });
    expect(accepted).toHaveLength(0);
    element.routes = { 'order.detail': { params: { id: 'string' } } };
    element.requestNavigate('order.detail', { id: 'order-1' });
    expect(accepted).toHaveLength(1);
    // 属性形式同样生效
    element.routes = undefined;
    element.setAttribute(
      'routes',
      JSON.stringify({ 'report.list': { params: { page: 'number' } } }),
    );
    element.requestNavigate('report.list', { page: 2 });
    expect(accepted).toHaveLength(2);
    // 非法路由表属性：明确报错，不静默忽略
    const errors = listen(element, 'ai-error');
    element.setAttribute('routes', '[1,2]');
    expect(
      errors.map((event) => (event.detail as { errorCode: string }).errorCode),
    ).toContain('ROUTES_INVALID');
  });

  it('业务上下文：updateContext 走契约校验，非法输入抛错', async () => {
    installFetch();
    const { element } = createComponent({ open: true });
    await vi.waitFor(() => {
      expect(element.state()).toBe('INITIALIZED');
    });
    expect(
      element.updateContext({ objectId: 'order-1', page: 'crm/order' }),
    ).toEqual({ objectId: 'order-1', page: 'crm/order' });
    expect(() => element.updateContext({ page: 42 })).toThrow();
  });

  it('destroy：幂等、单向终态、清理监听器与界面', async () => {
    installFetch();
    const { element, shadow } = createComponent({ mode: 'dialog', open: true });
    const destroyed = listen(element, 'ai-destroyed');
    await vi.waitFor(() => {
      expect(queryShadow(shadow, CONVERSATION)).not.toBeNull();
    });

    element.destroy();
    expect(destroyed).toHaveLength(1);
    expect(queryShadow(shadow, STATUS)?.textContent).toBe('已销毁');
    expect(queryShadow(shadow, SURFACE)?.childElementCount).toBe(0);

    // 幂等
    element.destroy();
    expect(destroyed).toHaveLength(1);

    // 单向终态：再次 open 不复活（属性被移回、状态保持 CREATED）
    element.open();
    expect(element.isOpen()).toBe(false);
    expect(element.getAttribute('open')).toBeNull();
    expect(element.state()).toBe('CREATED');

    // 监听器已解绑：Esc 不影响任何状态
    const escape = new KeyboardEvent('keydown', {
      bubbles: true,
      cancelable: true,
      key: 'Escape',
    });
    document.dispatchEvent(escape);
    expect(escape.defaultPrevented).toBe(false);
  });

  it('断开连接：释放资源但不锁定元素；重新连接会重新握手', async () => {
    installFetch();
    const { element, getAccessToken, shadow } = createComponent({
      open: true,
    });
    await vi.waitFor(() => {
      expect(element.state()).toBe('INITIALIZED');
    });
    element.remove();
    expect(element.state()).toBe('CREATED');
    expect(queryShadow(shadow, STATUS)?.textContent).toBe('已断开');

    document.body.append(element);
    await vi.waitFor(() => {
      expect(getAccessToken).toHaveBeenCalledTimes(2);
    });
    await vi.waitFor(() => {
      expect(element.state()).toBe('INITIALIZED');
    });
  });

  it('身份参数变化（app-code）触发重建，而不是半边换配置', async () => {
    installFetch();
    const { element, getAccessToken } = createComponent({ open: true });
    await vi.waitFor(() => {
      expect(getAccessToken).toHaveBeenCalledTimes(1);
    });
    element.setAttribute('app-code', 'another-app');
    await vi.waitFor(() => {
      expect(getAccessToken).toHaveBeenCalledTimes(2);
    });
    await vi.waitFor(() => {
      expect(element.state()).toBe('INITIALIZED');
    });
    expect(element.appCode).toBe('another-app');
  });

  it('max-height：合法值写进 --ai-max-height，非法文本不写样式', () => {
    const { element, shadow } = createComponent({ maxHeight: '480' });
    const panel = queryShadow<HTMLElement>(shadow, PANEL);
    expect(panel?.style.getPropertyValue('--ai-max-height')).toBe('480px');
    element.setAttribute('max-height', '3px; background: url(x)');
    expect(panel?.style.getPropertyValue('--ai-max-height')).toBe('480px');
  });

  it('形态：inline 是 region，drawer/dialog 是模态对话框并把焦点移入面板', async () => {
    installFetch();
    const trigger = document.createElement('button');
    document.body.append(trigger);
    trigger.focus();

    const { element, shadow } = createComponent({ mode: 'drawer', open: true });
    const panel = queryShadow<HTMLElement>(shadow, PANEL);
    expect(panel?.getAttribute('role')).toBe('dialog');
    expect(panel?.getAttribute('aria-modal')).toBe('true');
    // 焦点进入面板内部：宿主文档只看到组件元素（Shadow DOM 的焦点语义），内部焦点在关闭按钮上
    expect(document.activeElement).toBe(element);
    expect((shadow.activeElement as HTMLElement | null)?.dataset.testid).toBe(
      'ai-chat-component-close',
    );

    element.setMode('inline');
    expect(panel?.getAttribute('role')).toBe('region');
    expect(panel?.getAttribute('aria-modal')).toBeNull();
    element.setAttribute('mode', 'weird');
    expect(panel?.getAttribute('role')).toBe('region');

    element.setMode('dialog');
    element.close();
    expect(element.isOpen()).toBe(false);
    // 关闭后焦点回到打开前的元素
    expect(document.activeElement).toBe(trigger);
  });

  it('模态形态：Esc 关闭；inline 形态不拦截宿主的 Esc', () => {
    const inline = createComponent({ open: true });
    const hostEscape = new KeyboardEvent('keydown', {
      bubbles: true,
      cancelable: true,
      key: 'Escape',
    });
    document.dispatchEvent(hostEscape);
    expect(hostEscape.defaultPrevented).toBe(false);
    expect(inline.element.isOpen()).toBe(true);
    inline.element.remove();

    const modal = createComponent({ mode: 'dialog', open: true });
    const escape = new KeyboardEvent('keydown', {
      bubbles: true,
      cancelable: true,
      key: 'Escape',
    });
    document.dispatchEvent(escape);
    expect(escape.defaultPrevented).toBe(true);
    expect(modal.element.isOpen()).toBe(false);
  });

  it('模态形态：Tab 在面板内循环（无可聚焦子元素时用面板兜底）', () => {
    const { shadow } = createComponent({ mode: 'dialog', open: true });
    const panel = queryShadow<HTMLElement>(shadow, PANEL);
    const close = queryShadow<HTMLElement>(shadow, CLOSE_BUTTON);
    if (panel === null || close === null) {
      throw new Error('面板未创建');
    }

    // 只有一个候选（关闭按钮）：Tab 停在它上面（不逃到宿主页面）
    expect(shadow.activeElement).toBe(close);
    const single = new KeyboardEvent('keydown', {
      bubbles: true,
      cancelable: true,
      key: 'Tab',
    });
    document.dispatchEvent(single);
    expect(single.defaultPrevented).toBe(true);
    expect(shadow.activeElement).toBe(close);

    // 两个候选：末个 Tab → 回到首个；首个 Shift+Tab → 回到末个
    const extra = document.createElement('button');
    panel.append(extra);
    extra.focus();
    const wrap = new KeyboardEvent('keydown', {
      bubbles: true,
      cancelable: true,
      key: 'Tab',
    });
    document.dispatchEvent(wrap);
    expect(wrap.defaultPrevented).toBe(true);
    expect(shadow.activeElement).toBe(close);

    const back = new KeyboardEvent('keydown', {
      bubbles: true,
      cancelable: true,
      key: 'Tab',
      shiftKey: true,
    });
    document.dispatchEvent(back);
    expect(back.defaultPrevented).toBe(true);
    expect(shadow.activeElement).toBe(extra);

    // 子元素都不可见：焦点候选退化为面板自身，Tab 仍被拦在面板里
    for (const candidate of [close, extra]) {
      Object.defineProperty(candidate, 'offsetParent', {
        configurable: true,
        value: null,
      });
      Object.defineProperty(candidate, 'getClientRects', {
        configurable: true,
        value: () => [],
      });
    }
    panel.focus();
    const fallback = new KeyboardEvent('keydown', {
      bubbles: true,
      cancelable: true,
      key: 'Tab',
    });
    document.dispatchEvent(fallback);
    expect(fallback.defaultPrevented).toBe(true);
    expect(shadow.activeElement).toBe(panel);
  });

  it('打开前的内部状态与属性访问器保持一致', () => {
    const { element } = createComponent({
      instanceId: 'inst-9',
      serviceId: 'svc_9',
    });
    expect(element.instanceId).toBe('inst-9');
    expect(element.serviceId).toBe('svc_9');
    expect(element.theme).toBeUndefined();
    expect(element.routes).toBeUndefined();
    expect(element.surfaceStyles).toBeUndefined();
  });

  it('样式装载：styles-url 注入样式链接，改地址会替换，加载失败不拖垮组件', () => {
    const { element, shadow } = createComponent();
    // 测试环境是源码模块地址：不猜同目录 CSS，默认不注入链接
    expect(
      queryShadow(shadow, '[data-testid="ai-chat-component-styles"]'),
    ).toBeNull();

    element.setAttribute('styles-url', '/sdk/ai-web-component-5.6.0.css');
    const link = queryShadow<HTMLLinkElement>(
      shadow,
      '[data-testid="ai-chat-component-styles"]',
    );
    expect(link?.rel).toBe('stylesheet');
    expect(link?.getAttribute('href')).toBe('/sdk/ai-web-component-5.6.0.css');

    element.setAttribute('styles-url', '/sdk/ai-web-component-5.5.0.css');
    const replaced = shadow.querySelectorAll(
      '[data-testid="ai-chat-component-styles"]',
    );
    expect(replaced).toHaveLength(1);
    expect(replaced[0]?.getAttribute('href')).toBe(
      '/sdk/ai-web-component-5.5.0.css',
    );

    // 资源加载失败：链接被移除，组件仍可用
    replaced[0]?.dispatchEvent(new Event('error'));
    expect(
      shadow.querySelector('[data-testid="ai-chat-component-styles"]'),
    ).toBeNull();
    expect(queryShadow(shadow, PANEL)).not.toBeNull();
  });

  it('样式装载：源码导入的宿主可给出样式文本（可替换，只保留一份）', () => {
    const { element, shadow } = createComponent();
    element.setAttribute('styles-url', '/sdk/ai-web-component-5.6.0.css');
    element.surfaceStyles = '.ai-conversation { gap: 12px; }';
    const first = queryShadow<HTMLStyleElement>(
      shadow,
      '[data-testid="ai-chat-component-style-text"]',
    );
    expect(first?.textContent).toBe('.ai-conversation { gap: 12px; }');

    element.surfaceStyles = '.ai-conversation { gap: 8px; }';
    const texts = shadow.querySelectorAll(
      '[data-testid="ai-chat-component-style-text"]',
    );
    expect(texts).toHaveLength(1);
    expect(texts[0]?.textContent).toBe('.ai-conversation { gap: 8px; }');

    // 清空文本：样式文本节点被移除（外壳样式不受影响）
    element.surfaceStyles = '';
    expect(
      shadow.querySelectorAll('[data-testid="ai-chat-component-style-text"]'),
    ).toHaveLength(0);
  });
});

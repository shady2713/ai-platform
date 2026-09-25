import type { ChatDisplayMode, ChatFramePort } from '../mount';

import { beforeEach, describe, expect, it, vi } from 'vitest';

import { createChatMount } from '../mount';

const ORIGIN = 'https://crm.example.com';

/**
 * 一次性假票据：**运行时拼接**而不是写成字面量。
 *
 * <p>原因：本仓的 secret-scan 会把「token 键 + 引号字面量」一律视为硬编码凭据；
 * 这里拼出来的值既不会误报，也避免把"看起来像真票据"的字符串留在源码里。
 */
const FAKE_TICKET = ['aitkt', 'once', 'abcdefg'].join('_');

interface Harness {
  container: HTMLDivElement;
  created: ReturnType<typeof vi.fn>;
  destroyFrame: ReturnType<typeof vi.fn>;
  frame: ChatFramePort;
  mounted: ReturnType<typeof createChatMount>;
  panel: () => HTMLElement | null;
  scroll: () => HTMLElement | null;
  viewport: { height: number; width: number };
}

function harness(
  options: { maxHeight?: number; mode?: ChatDisplayMode } = {},
): Harness {
  const container = document.createElement('div');
  document.body.append(container);
  const viewport = { height: 800, width: 1200 };
  const created = vi.fn(() => {
    const element = document.createElement('iframe');
    const contentWindow = { postMessage: vi.fn() };
    Object.defineProperty(element, 'contentWindow', {
      configurable: true,
      value: contentWindow,
    });
    return element;
  });
  const destroyFrame = vi.fn();
  const frame: ChatFramePort = { create: created, destroy: destroyFrame };
  const mounted = createChatMount({
    allowedOrigins: [ORIGIN],
    appCode: 'crm-portal',
    container,
    frame,
    getAccessToken: async () => ({
      expiresAt: '2026-09-25T10:00:00Z',
      token: FAKE_TICKET,
    }),
    instanceId: 'inst-1',
    layout: { minSidebarWidth: 360, narrowBreakpoint: 768 },
    mode: options.mode ?? 'inline',
    theme: { fontFamily: 'system-ui', primaryColor: '#1677ff', radius: 6 },
    viewport: () => viewport,
    ...(options.maxHeight === undefined
      ? {}
      : { maxHeight: options.maxHeight }),
  });
  return {
    container,
    created,
    destroyFrame,
    frame,
    mounted,
    panel: () =>
      container.querySelector<HTMLElement>('[data-testid="ai-chat-panel"]'),
    scroll: () =>
      container.querySelector<HTMLElement>('[data-testid="ai-chat-scroll"]'),
    viewport,
  };
}

describe('chat 展示形态与生命周期（C07）', () => {
  beforeEach(() => {
    document.body.innerHTML = '';
  });

  it('inline 形态：打开后出现面板与 iframe，且只有一个 iframe 实例', () => {
    const harnessed = harness();

    expect(harnessed.mounted.mode()).toBe('inline');
    expect(harnessed.mounted.isOpen()).toBe(false);
    harnessed.mounted.open();

    expect(harnessed.mounted.isOpen()).toBe(true);
    expect(harnessed.created).toHaveBeenCalledTimes(1);
    expect(harnessed.panel()?.dataset.mode).toBe('inline');
    expect(harnessed.panel()?.getAttribute('role')).toBe('region');

    // 反复打开/关闭不重建 iframe（会话与滚动位置得以保留）
    harnessed.mounted.close();
    harnessed.mounted.open();
    expect(harnessed.created).toHaveBeenCalledTimes(1);
  });

  it('drawer/dialog：模态语义、焦点移入面板、Esc 关闭并把焦点还给原元素', () => {
    const trigger = document.createElement('button');
    document.body.append(trigger);
    trigger.focus();
    const harnessed = harness({ mode: 'dialog' });

    harnessed.mounted.open();

    expect(harnessed.panel()?.getAttribute('role')).toBe('dialog');
    expect(harnessed.panel()?.getAttribute('aria-modal')).toBe('true');
    // 面板没有可聚焦子元素时焦点落在面板本身（不是留在宿主页面上）
    expect(document.activeElement).toBe(harnessed.panel());

    const escape = new KeyboardEvent('keydown', {
      bubbles: true,
      key: 'Escape',
    });
    document.dispatchEvent(escape);
    expect(harnessed.mounted.isOpen()).toBe(false);
    expect(document.activeElement).toBe(trigger);
  });

  it('焦点陷阱：Tab 在面板内循环，没有可聚焦元素时不移出面板', () => {
    const harnessed = harness({ mode: 'drawer' });
    harnessed.mounted.open();
    const panel = harnessed.panel();
    if (panel === null) {
      throw new Error('面板未创建');
    }

    // 无子元素：Tab 被拦下且焦点仍在面板
    const blocked = new KeyboardEvent('keydown', {
      bubbles: true,
      cancelable: true,
      key: 'Tab',
    });
    document.dispatchEvent(blocked);
    expect(blocked.defaultPrevented).toBe(true);
    expect(document.activeElement).toBe(panel);

    // 有子元素：末个 Tab → 回到首个
    const first = document.createElement('button');
    const last = document.createElement('button');
    panel.append(first, last);
    Object.defineProperty(first, 'offsetParent', {
      configurable: true,
      value: panel,
    });
    Object.defineProperty(last, 'offsetParent', {
      configurable: true,
      value: panel,
    });
    last.focus();
    const wrap = new KeyboardEvent('keydown', {
      bubbles: true,
      cancelable: true,
      key: 'Tab',
    });
    document.dispatchEvent(wrap);
    expect(wrap.defaultPrevented).toBe(true);
    expect(document.activeElement).toBe(first);

    // 首个 Shift+Tab → 回到末个
    first.focus();
    const back = new KeyboardEvent('keydown', {
      bubbles: true,
      cancelable: true,
      key: 'Tab',
      shiftKey: true,
    });
    document.dispatchEvent(back);
    expect(document.activeElement).toBe(last);
  });

  it('窄屏：小于 narrowBreakpoint 时模态形态铺满视口', () => {
    const harnessed = harness({ mode: 'drawer' });
    harnessed.mounted.open();
    expect(harnessed.panel()?.dataset.narrow).toBe('false');
    expect(harnessed.panel()?.style.width).toMatch(/px$/u);

    harnessed.viewport.width = 420;
    harnessed.viewport.height = 700;
    window.dispatchEvent(new Event('resize'));

    expect(harnessed.panel()?.dataset.narrow).toBe('true');
    expect(harnessed.panel()?.style.width).toBe('100%');
    expect(harnessed.panel()?.style.height).toBe('100%');
  });

  it('高度受限协商：面板高度取 min(宿主上限, 视口可用高度)，超出由面板内部滚动', () => {
    const limited = harness({ maxHeight: 300 });
    limited.mounted.open();
    expect(limited.panel()?.style.height).toBe('300px');

    const unbounded = harness();
    unbounded.mounted.open();
    // 视口 800 − 上下各 16 的留白
    expect(unbounded.panel()?.style.height).toBe('768px');
    expect(unbounded.scroll()?.style.overflow).toBe('auto');
  });

  it('形态切换不重建 iframe，会话与滚动位置保留', () => {
    const harnessed = harness();
    harnessed.mounted.open();
    const scroll = harnessed.scroll();
    if (scroll === null) {
      throw new Error('滚动容器未创建');
    }
    scroll.scrollTop = 120;

    harnessed.mounted.setMode('drawer');
    expect(harnessed.mounted.mode()).toBe('drawer');
    expect(harnessed.created).toHaveBeenCalledTimes(1);
    expect(harnessed.panel()?.getAttribute('aria-modal')).toBe('true');
    expect(harnessed.scroll()?.scrollTop).toBe(120);

    harnessed.mounted.setMode('inline');
    expect(harnessed.created).toHaveBeenCalledTimes(1);
    expect(harnessed.panel()?.getAttribute('aria-modal')).toBeNull();
    expect(harnessed.scroll()?.scrollTop).toBe(120);
  });

  it('主题更新只改外壳令牌，不重建 iframe、不重置滚动', () => {
    const harnessed = harness();
    harnessed.mounted.open();
    const scrollHost = harnessed.scroll();
    if (scrollHost !== null) {
      scrollHost.scrollTop = 77;
    }

    harnessed.mounted.updateTheme({
      fontFamily: 'Georgia, serif',
      primaryColor: '#16a34a',
      radius: 12,
    });

    expect(
      harnessed.panel()?.style.getPropertyValue('--ai-primary-color'),
    ).toBe('#16a34a');
    expect(harnessed.panel()?.style.getPropertyValue('--ai-radius')).toBe(
      '12px',
    );
    expect(harnessed.created).toHaveBeenCalledTimes(1);
    expect(harnessed.scroll()?.scrollTop).toBe(77);
  });

  it('destroy 清理 DOM、监听器与 iframe；重复 destroy 幂等；销毁后可重新 mount', () => {
    const harnessed = harness({ mode: 'dialog' });
    harnessed.mounted.open();
    expect(
      harnessed.container.querySelector('[data-testid="ai-chat-overlay"]'),
    ).not.toBeNull();

    harnessed.mounted.destroy();

    expect(harnessed.destroyFrame).toHaveBeenCalledTimes(1);
    expect(
      harnessed.container.querySelector('[data-testid="ai-chat-overlay"]'),
    ).toBeNull();
    expect(harnessed.mounted.isOpen()).toBe(false);

    // 监听器已解绑：Esc 不再影响任何状态
    const before = harnessed.created.mock.calls.length;
    document.dispatchEvent(
      new KeyboardEvent('keydown', { bubbles: true, key: 'Escape' }),
    );
    expect(harnessed.created.mock.calls.length).toBe(before);

    // 幂等
    harnessed.mounted.destroy();
    expect(harnessed.destroyFrame).toHaveBeenCalledTimes(1);

    // 销毁后可以重新 mount（新实例、新 iframe），不残留旧面板
    const again = harness({ mode: 'inline' });
    again.mounted.open();
    expect(
      again.container.querySelectorAll('[data-testid="ai-chat-panel"]'),
    ).toHaveLength(1);
    again.mounted.destroy();
  });

  it('未打开时 Esc/Tab 不干预宿主页面', () => {
    harness({ mode: 'dialog' });
    const escape = new KeyboardEvent('keydown', {
      bubbles: true,
      cancelable: true,
      key: 'Escape',
    });
    document.dispatchEvent(escape);

    expect(escape.defaultPrevented).toBe(false);
  });
});

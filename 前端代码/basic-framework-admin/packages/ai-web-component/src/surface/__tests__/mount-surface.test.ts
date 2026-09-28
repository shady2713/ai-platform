import type { ConversationApi, ConversationSummary } from '@vben/ai-chat-ui';

import { beforeEach, describe, expect, it } from 'vitest';

import { mountChatSurface } from '../mount-surface';
import { applyThemeVariables, themeVariables } from '../theme';

/**
 * 界面挂载与主题令牌（X09）：界面不随 open/close 重建；destroy 幂等且彻底；
 * 主题只写 CSS 变量（值全部来自已校验 token）。
 */
const ALLOWED_FONT =
  'system-ui, -apple-system, "PingFang SC", "Microsoft YaHei", sans-serif';

function conversationApi(): ConversationApi {
  return {
    create: async (title?: string): Promise<ConversationSummary> => ({
      conversationKey: 'conv_1',
      id: 1,
      title: title ?? '新会话',
    }),
    list: async () => [],
    remove: async () => undefined,
    rename: async () => undefined,
  };
}

describe('组件界面挂载（X09）', () => {
  beforeEach(() => {
    document.body.innerHTML = '';
  });

  it('挂载会话面板并应用主题变量；destroy 后再调用是安全的空操作', () => {
    const container = document.createElement('div');
    document.body.append(container);
    const surface = mountChatSurface({
      api: conversationApi(),
      container,
      runApi: null,
      serviceId: '',
      theme: { fontFamily: ALLOWED_FONT, primaryColor: '#16a34a', radius: 10 },
    });
    expect(
      container.querySelector('[data-testid="ai-conversation"]'),
    ).not.toBeNull();
    expect(container.style.getPropertyValue('--ai-radius')).toBe('10px');
    expect(container.style.getPropertyValue('--ai-primary-color')).toBe(
      '#16a34a',
    );

    // 主题更新只改令牌（不重建面板：节点身份不变）
    const panel = container.querySelector('[data-testid="ai-conversation"]');
    surface.setTheme(undefined);
    expect(container.style.getPropertyValue('--ai-primary-color')).toBe(
      '#1677ff',
    );
    expect(container.querySelector('[data-testid="ai-conversation"]')).toBe(
      panel,
    );

    surface.destroy();
    surface.destroy();
    surface.setTheme({
      fontFamily: ALLOWED_FONT,
      primaryColor: '#dc2626',
      radius: 4,
    });
    expect(container.childElementCount).toBe(0);
    expect(container.style.getPropertyValue('--ai-primary-color')).toBe(
      '#1677ff',
    );
  });

  it('主题变量：未知 token 由解析层拒绝（不退化成"任意 CSS 文本"）', () => {
    const container = document.createElement('div');
    applyThemeVariables(container, undefined);
    expect(container.style.getPropertyValue('--ai-narrow-breakpoint')).toBe(
      '768px',
    );
    expect(() =>
      themeVariables({
        fontFamily: 'unknown-font, sans-serif',
        primaryColor: '#1677ff',
        radius: 6,
      }),
    ).toThrow();
  });

  it('卸载时清理面板节点（不留 DOM 残骸）', () => {
    const container = document.createElement('div');
    document.body.append(container);
    const surface = mountChatSurface({
      api: conversationApi(),
      container,
      runApi: null,
      serviceId: 'svc_1',
      theme: undefined,
    });
    const panel = container.querySelector('[data-testid="ai-conversation"]');
    expect(panel).not.toBeNull();
    surface.destroy();
    expect(document.body.contains(panel)).toBe(false);
    expect(container.childElementCount).toBe(0);
  });
});

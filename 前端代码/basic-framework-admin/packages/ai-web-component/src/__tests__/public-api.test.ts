import { describe, expect, it } from 'vitest';

import * as publicApi from '../index';

/**
 * 包的公开面（X09）：入口只做导出，**没有副作用**（不自动注册元素、不占 window 监听器）。
 *
 * <p>这条用例同时锁住"宿主拿到的 API 形状"：宿主只依赖这里导出的名字。
 */
describe('@vben/ai-web-component 的公开面（X09）', () => {
  it('导入入口不注册自定义元素（显式 define，避免与宿主已有标签冲突）', () => {
    expect(customElements.get(publicApi.AI_CHAT_ELEMENT_TAG)).toBeUndefined();
    publicApi.defineAiChatElement();
    expect(customElements.get(publicApi.AI_CHAT_ELEMENT_TAG)).toBeDefined();
  });

  it('导出宿主需要的全部能力', () => {
    expect(publicApi.AiChatElement).toBeTypeOf('function');
    expect(publicApi.defineAiChatElement).toBeTypeOf('function');
    expect(publicApi.createComponentChatController).toBeTypeOf('function');
    expect(publicApi.parseComponentConfig).toBeTypeOf('function');
    expect(publicApi.parseRoutes).toBeTypeOf('function');
    expect(publicApi.parseAllowOrigins).toBeTypeOf('function');
    expect(publicApi.generateInstanceId).toBeTypeOf('function');
    expect(publicApi.createLocalPort).toBeTypeOf('function');
    expect(publicApi.createComponentConversationApi).toBeTypeOf('function');
    expect(publicApi.createComponentRunApi).toBeTypeOf('function');
    expect(publicApi.numericId).toBeTypeOf('function');
    expect(publicApi.mountChatSurface).toBeTypeOf('function');
    expect(publicApi.applyThemeVariables).toBeTypeOf('function');
    expect(publicApi.themeVariables).toBeTypeOf('function');
    expect(publicApi.installStyles).toBeTypeOf('function');
    expect(publicApi.COMPONENT_STYLES).toContain('.ai-web-component__panel');
    expect(publicApi.DEFAULT_COMPONENT_MODE).toBe('inline');
    expect(publicApi.AI_CHAT_ELEMENT_TAG).toBe('ai-chat-component');
  });

  it('主题令牌：与 iframe 路径同名的 --ai-* 变量', () => {
    const variables = publicApi.themeVariables(undefined);
    expect(Object.keys(variables).toSorted()).toEqual([
      '--ai-color-scheme',
      '--ai-density-factor',
      '--ai-font-family',
      '--ai-font-scale-factor',
      '--ai-narrow-breakpoint',
      '--ai-primary-color',
      '--ai-radius',
      '--ai-sidebar-min-width',
    ]);
    expect(variables['--ai-primary-color']).toBe('#1677ff');
  });
});

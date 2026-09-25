import { flushPromises, mount } from '@vue/test-utils';

import { beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('@vben/common-ui', async () => {
  const { defineComponent, h } = await import('vue');
  return {
    Page: defineComponent({
      name: 'PageStub',
      setup(_props, { slots }) {
        return () => h('main', { 'data-testid': 'page' }, slots.default?.());
      },
    }),
  };
});

const { default: ChatIntegrationIndex } = await import('./index.vue');

describe('chat 集成页（C09）', () => {
  beforeEach(() => {
    Object.defineProperty(navigator, 'clipboard', {
      configurable: true,
      value: { writeText: vi.fn(async () => undefined) },
    });
  });

  it('展示接入代码且其中不含真实票据', async () => {
    const wrapper = mount(ChatIntegrationIndex);
    await flushPromises();

    const snippet = wrapper
      .get('[data-testid="ai-chat-integration-snippet"]')
      .text();
    expect(snippet).toContain('<TICKET>');
    expect(snippet).toContain('/app-api/ai/v1/embed/your-app-code');
    expect(snippet).not.toMatch(/aitkt_[A-Za-z0-9]{8,}/u);
  });

  it('展示后端换票说明与 SDK 能力版本', async () => {
    const wrapper = mount(ChatIntegrationIndex);
    await flushPromises();
    const text = wrapper.text();

    expect(text).toContain('/app-api/ai/auth/ticket');
    expect(text).toContain('appSecret');
    expect(text).toContain('嵌入桥协议 v1.0');
    expect(text).toContain('自建 UI 路线');
  });

  it('复制失败时提示手动复制而不是伪装成功', async () => {
    Object.defineProperty(navigator, 'clipboard', {
      configurable: true,
      value: {
        writeText: vi.fn(async () => {
          throw new Error('denied');
        }),
      },
    });
    const wrapper = mount(ChatIntegrationIndex);
    await flushPromises();

    const copy = wrapper
      .findAll('button')
      .find((button) => button.text() === '复制接入代码');
    await copy?.trigger('click');
    await flushPromises();

    expect(wrapper.text()).toContain('请手动选择代码块复制');
  });
});

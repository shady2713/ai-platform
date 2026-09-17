import { mount } from '@vue/test-utils';

import { describe, expect, it, vi } from 'vitest';

import App from '../App.vue';

// 面板依赖图表库与浏览器 canvas，App 层只验证装配与状态呈现，面板行为由自身测试覆盖
vi.mock('@vben/ai-chat-ui', () => ({
  AiChatPanel: {
    name: 'AiChatPanel',
    props: ['disabled', 'messages'],
    template: '<div data-testid="panel">{{ messages.length }}</div>',
  },
}));

describe('app', () => {
  it('未配置 API 基址时仍渲染面板与就绪状态', () => {
    const wrapper = mount(App);

    expect(wrapper.get('h1').text()).toBe('AI 助手');
    expect(wrapper.get('[data-testid="ai-chat-status"]').text()).toBe('就绪');
    expect(wrapper.find('[data-testid="panel"]').exists()).toBe(true);
  });
});

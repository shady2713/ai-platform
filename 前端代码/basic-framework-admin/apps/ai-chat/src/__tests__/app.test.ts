import { mount } from '@vue/test-utils';

import { describe, expect, it, vi } from 'vitest';

import App from '../App.vue';

// 面板行为由自身测试覆盖；App 层只验证装配与状态呈现
vi.mock('@vben/ai-chat-ui', () => ({
  ConversationPanel: {
    name: 'ConversationPanel',
    props: ['api', 'runApi', 'serviceId'],
    template: '<div data-testid="panel">{{ serviceId }}</div>',
  },
}));

describe('app', () => {
  it('未配置 API 基址时渲染面板并给出明确状态（不静默失败）', () => {
    const wrapper = mount(App);

    expect(wrapper.get('h1').text()).toBe('AI 助手');
    expect(wrapper.get('[data-testid="ai-chat-status"]').text()).toBe(
      '未配置 AI 服务地址',
    );
    expect(wrapper.find('[data-testid="panel"]').exists()).toBe(true);
  });
});

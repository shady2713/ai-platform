import { mount } from '@vue/test-utils';

import { describe, expect, it, vi } from 'vitest';

import { AiChatPanel } from '../index';

vi.mock('../components/ChartRenderer.vue', () => ({
  default: {
    name: 'ChartRenderer',
    props: ['spec'],
    template: '<div data-testid="chart-stub">{{ spec.title }}</div>',
  },
}));

const messages = [
  {
    id: 'm1',
    role: 'user' as const,
    blocks: [{ kind: 'text' as const, text: '画一张图' }],
  },
  {
    id: 'm2',
    role: 'assistant' as const,
    blocks: [
      { kind: 'text' as const, text: '这是结果' },
      {
        kind: 'chart' as const,
        spec: {
          type: 'bar' as const,
          categories: ['a'],
          series: [{ name: 's', data: [1] }],
        },
      },
      { kind: 'error' as const, message: '上游超时' },
    ],
  },
];

describe('aiChatPanel', () => {
  it('按块类型渲染文本、图表与错误', () => {
    const wrapper = mount(AiChatPanel, { props: { messages } });

    const items = wrapper.findAll('[data-role]');
    expect(items).toHaveLength(2);
    expect(items[0]?.attributes('data-role')).toBe('user');
    expect(wrapper.text()).toContain('这是结果');
    expect(wrapper.find('[data-testid="chart-stub"]').exists()).toBe(true);
    expect(wrapper.get('[role="alert"]').text()).toContain('上游超时');
  });

  it('提交时向宿主发出裁剪后的文本并清空输入', async () => {
    const wrapper = mount(AiChatPanel, { props: { messages: [] } });
    const input = wrapper.get('input');

    await input.setValue('  你好  ');
    await wrapper.get('form').trigger('submit');

    expect(wrapper.emitted('send')).toEqual([['你好']]);
    expect((input.element as HTMLInputElement).value).toBe('');
  });

  it('空白输入与禁用状态不发送', async () => {
    const wrapper = mount(AiChatPanel, { props: { messages: [] } });
    await wrapper.get('input').setValue('   ');
    await wrapper.get('form').trigger('submit');
    expect(wrapper.emitted('send')).toBeUndefined();
    expect(
      wrapper.get('[data-testid="ai-chat-send"]').attributes('disabled'),
    ).toBeDefined();

    await wrapper.setProps({ disabled: true });
    await wrapper.get('input').setValue('正常文本');
    await wrapper.get('form').trigger('submit');
    expect(wrapper.emitted('send')).toBeUndefined();
  });
});

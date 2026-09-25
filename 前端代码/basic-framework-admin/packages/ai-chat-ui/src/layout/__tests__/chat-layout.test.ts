import { mount } from '@vue/test-utils';

import { describe, expect, it } from 'vitest';

import ChatLayout from '../ChatLayout.vue';

describe('chat 布局外壳（C07）', () => {
  it('内嵌形态是普通区域且不抢 Esc', async () => {
    const wrapper = mount(ChatLayout, { props: { mode: 'inline' } });

    expect(wrapper.attributes('role')).toBe('region');
    expect(wrapper.attributes('aria-modal')).toBeUndefined();
    expect(wrapper.attributes('data-mode')).toBe('inline');

    await wrapper.trigger('keydown', { key: 'Escape' });
    expect(wrapper.emitted('close')).toBeUndefined();
  });

  it('弹窗与侧栏是模态区域，Esc 与关闭按钮都触发 close', async () => {
    for (const mode of ['dialog', 'drawer'] as const) {
      const wrapper = mount(ChatLayout, { props: { mode } });

      expect(wrapper.attributes('role')).toBe('dialog');
      expect(wrapper.attributes('aria-modal')).toBe('true');
      expect(wrapper.attributes('data-mode')).toBe(mode);

      await wrapper.trigger('keydown', { key: 'Escape' });
      expect(wrapper.emitted('close')).toHaveLength(1);
    }

    const withButton = mount(ChatLayout, { props: { mode: 'drawer' } });
    await withButton
      .get('[data-testid="ai-chat-layout-close"]')
      .trigger('click');
    expect(withButton.emitted('close')).toHaveLength(1);
  });

  it('窄屏按传入断点判定并可随宽度重算', async () => {
    const wrapper = mount(ChatLayout, { props: { narrowBreakpoint: 768 } });

    wrapper.vm.evaluateNarrow(420);
    await wrapper.vm.$nextTick();
    expect(wrapper.attributes('data-narrow')).toBe('true');

    wrapper.vm.evaluateNarrow(1200);
    await wrapper.vm.$nextTick();
    expect(wrapper.attributes('data-narrow')).toBe('false');
  });

  it('渲染标题与内容插槽，并把标题作为可读标签', () => {
    const wrapper = mount(ChatLayout, {
      props: { title: '销售助手' },
      slots: { default: '<p data-testid="content">内容</p>' },
    });

    expect(wrapper.get('h2').text()).toBe('销售助手');
    expect(wrapper.attributes('aria-label')).toBe('销售助手');
    expect(wrapper.get('[data-testid="content"]').text()).toBe('内容');
    expect(
      wrapper.get('[data-testid="ai-chat-layout-body"]').element,
    ).toBeDefined();
  });
});

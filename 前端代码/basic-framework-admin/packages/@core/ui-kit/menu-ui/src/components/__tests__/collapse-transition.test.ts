import { mount } from '@vue/test-utils';
import { defineComponent, h, nextTick, ref } from 'vue';

import { describe, expect, it } from 'vitest';

import CollapseTransition from '../collapse-transition.vue';

const ToggleBox = defineComponent({
  components: { CollapseTransition },
  setup() {
    const show = ref(true);
    return () =>
      h('div', [
        h('button', { onClick: () => (show.value = !show.value) }, 'toggle'),
        h(CollapseTransition, null, () =>
          show.value
            ? h('div', { class: 'box', style: { height: '50px' } }, 'box')
            : null,
        ),
      ]);
  },
});

describe('collapseTransition', () => {
  it('renders the slotted content while visible', () => {
    const wrapper = mount(ToggleBox);
    expect(wrapper.find('.box').exists()).toBe(true);
    expect(wrapper.text()).toContain('box');
  });

  it('runs the leave hooks and removes the content after hiding', async () => {
    const wrapper = mount(ToggleBox);
    await wrapper.find('button').trigger('click');
    await nextTick();
    expect(wrapper.find('.box').exists()).toBe(false);
  });

  it('runs the enter hooks and restores the content after showing again', async () => {
    const wrapper = mount(ToggleBox);
    await wrapper.find('button').trigger('click');
    await wrapper.find('button').trigger('click');
    await nextTick();
    const box = wrapper.find('.box');
    expect(box.exists()).toBe(true);
    // the original element height is preserved after the animation reset
    expect(box.attributes('style')).toContain('height: 50px');
    expect(box.attributes('style')).not.toContain('max-height: 0px');
  });
});

import { mount } from '@vue/test-utils';
import { defineComponent, h, nextTick } from 'vue';

import { describe, expect, it, vi } from 'vitest';

import { useDescription } from './use-description';

vi.mock('./description', () => ({
  default: defineComponent({
    name: 'DescriptionStub',
    inheritAttrs: false,
    props: {
      title: { default: '', type: String },
    },
    setup(props, { attrs, slots }) {
      return () =>
        h('section', attrs, [h('h2', props.title), slots.default?.()]);
    },
  }),
}));

describe('use description', () => {
  it('merges caller attributes and applies reactive property updates', async () => {
    const [Description, api] = useDescription({ title: '初始标题' });
    const wrapper = mount(Description, {
      attrs: { id: 'job-detail' },
      slots: { default: () => '详情内容' },
    });

    expect(wrapper.get('section').attributes('id')).toBe('job-detail');
    expect(wrapper.get('h2').text()).toBe('初始标题');
    expect(wrapper.text()).toContain('详情内容');

    api.setDescProps({ title: '更新标题' });
    await nextTick();

    expect(wrapper.get('h2').text()).toBe('更新标题');
  });
});

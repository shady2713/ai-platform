import { mount } from '@vue/test-utils';
import { defineComponent, h } from 'vue';

import { describe, expect, it, vi } from 'vitest';

import Description from './description';

vi.mock('element-plus', async () => {
  const { defineComponent, h } =
    await vi.importActual<typeof import('vue')>('vue');
  return {
    ElDescriptions: defineComponent({
      inheritAttrs: false,
      props: { title: { default: '', type: String } },
      setup(props, { attrs, slots }) {
        return () =>
          h('section', attrs, [
            h('h2', props.title),
            h('aside', slots.extra?.()),
            slots.default?.(),
          ]);
      },
    }),
    ElDescriptionsItem: defineComponent({
      props: { span: { default: 1, type: Number } },
      setup(props, { slots }) {
        return () =>
          h('article', { 'data-span': props.span }, [
            h('dt', slots.label?.()),
            h('dd', slots.default?.()),
          ]);
      },
    }),
  };
});

describe('description', () => {
  it('renders visible, nested, custom and slotted values', () => {
    const wrapper = mount(Description, {
      props: {
        data: {
          custom: 'slot source',
          hidden: 'secret',
          profile: { name: 'Alice' },
          score: 7,
        },
        schema: [
          {
            contentMinWidth: 160,
            field: 'profile.name',
            label: '姓名',
            labelMinWidth: 120,
            span: 2,
          },
          {
            field: 'hidden',
            label: '隐藏字段',
            show: () => false,
          },
          {
            field: 'score',
            label: '分数',
            render: (value) => `#${value}`,
          },
          { field: 'custom', label: '自定义', slot: 'custom' },
        ],
        title: '详情',
      },
      slots: {
        custom: ({ data }) => `slot:${data.custom}`,
        extra: () => '扩展操作',
      },
    });

    expect(wrapper.get('h2').text()).toBe('详情');
    expect(wrapper.text()).toContain('Alice');
    expect(wrapper.text()).toContain('#7');
    expect(wrapper.text()).toContain('slot:slot source');
    expect(wrapper.text()).toContain('扩展操作');
    expect(wrapper.text()).not.toContain('secret');
    expect(wrapper.get('article').attributes('data-span')).toBe('2');
    expect(wrapper.get('dt div').attributes('style')).toContain(
      'min-width: 120px',
    );
    expect(wrapper.get('dd div').attributes('style')).toContain(
      'min-width: 160px',
    );
  });

  it('preserves label styles without generating an invalid width', () => {
    const StyledLabel = defineComponent({
      setup: () => () => h('strong', '标签'),
    });
    const wrapper = mount(Description, {
      props: {
        data: { value: '内容' },
        schema: [
          {
            field: 'value',
            label: h(StyledLabel),
            labelStyle: { color: 'red' },
          },
        ],
      },
    });

    expect(wrapper.get('dt div').attributes('style')).toContain('color: red');
    expect(wrapper.get('dt div').attributes('style')).not.toContain(
      'undefined',
    );
  });
});

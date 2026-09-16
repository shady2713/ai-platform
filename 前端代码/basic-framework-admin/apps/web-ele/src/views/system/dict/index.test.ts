import { mount } from '@vue/test-utils';

import { describe, expect, it, vi } from 'vitest';

import DictIndex from './index.vue';

const state = vi.hoisted(() => ({
  dataGridDictType: undefined as string | undefined,
}));

vi.mock('@vben/common-ui', async () => {
  const { defineComponent, h } = await import('vue');
  return {
    Page: defineComponent({
      name: 'PageStub',
      setup(_props, { slots }) {
        return () => h('main', slots.default?.());
      },
    }),
  };
});

vi.mock('./modules/type-grid.vue', async () => {
  const { defineComponent, h } = await import('vue');
  return {
    default: defineComponent({
      name: 'TypeGridStub',
      emits: ['select'],
      setup(_props, { emit }) {
        return () =>
          h(
            'button',
            {
              'data-test': 'type-select',
              onClick: () => emit('select', 'sex'),
            },
            '字典类型',
          );
      },
    }),
  };
});

vi.mock('./modules/data-grid.vue', async () => {
  const { defineComponent, h, watch } = await import('vue');
  return {
    default: defineComponent({
      name: 'DataGridStub',
      props: {
        dictType: { type: String, default: undefined },
      },
      setup(props) {
        watch(
          () => props.dictType,
          (value) => {
            state.dataGridDictType = value;
          },
          { immediate: true },
        );
        return () => h('div', { 'data-test': 'data-grid' });
      },
    }),
  };
});

describe('dict page', () => {
  it('passes the selected dict type from the type grid to the data grid', async () => {
    const wrapper = mount(DictIndex);

    expect(state.dataGridDictType).toBeUndefined();

    await wrapper.find('[data-test="type-select"]').trigger('click');

    expect(state.dataGridDictType).toBe('sex');
    expect(wrapper.find('[data-test="type-select"]').exists()).toBe(true);
  });
});

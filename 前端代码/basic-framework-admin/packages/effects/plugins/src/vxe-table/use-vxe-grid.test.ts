import { mount } from '@vue/test-utils';
import { defineComponent, h, nextTick } from 'vue';

import { describe, expect, it, vi } from 'vitest';

import { useVbenVxeGrid } from './use-vxe-grid';

vi.mock('./use-vxe-grid.vue', () => ({
  default: defineComponent({
    name: 'TestGrid',
    setup:
      (_, { slots }) =>
      () =>
        h('div', slots.default?.()),
  }),
}));

interface Row {
  id: number;
}

describe('useVbenVxeGrid', () => {
  it('creates an API with a reactive store selector', async () => {
    const [, api] = useVbenVxeGrid<Row>({ showSearchForm: true });
    const showSearchForm = api.useStore((state) => state.showSearchForm);

    expect(showSearchForm.value).toBe(true);
    api.toggleSearchForm(false);
    await nextTick();

    expect(showSearchForm.value).toBe(false);
  });

  it('forwards component attributes and clears the API on unmount', () => {
    const [Grid, api] = useVbenVxeGrid<Row>({});
    const unmount = vi.spyOn(api, 'unmount');
    const wrapper = mount(Grid, {
      attrs: {
        gridClass: 'orders-grid',
        showSearchForm: false,
      },
    });

    expect(api.state).toMatchObject({
      gridClass: 'orders-grid',
      showSearchForm: false,
    });
    wrapper.unmount();
    expect(unmount).toHaveBeenCalledOnce();
  });
});

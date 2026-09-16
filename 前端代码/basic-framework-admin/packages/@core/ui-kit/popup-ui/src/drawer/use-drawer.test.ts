import type { ExtendedDrawerApi } from './drawer';

import { mount } from '@vue/test-utils';
import { defineComponent, h, nextTick } from 'vue';

import { describe, expect, it, vi } from 'vitest';

import { useVbenDrawer } from './use-drawer';

describe('useVbenDrawer', () => {
  it('connects the parent API to the drawer created by the child component', async () => {
    const onOpenChange = vi.fn();
    let childApi: ExtendedDrawerApi | undefined;
    const ConnectedDrawer = defineComponent({
      setup() {
        const [, api] = useVbenDrawer();
        childApi = api;
        return () => h('div', { 'data-test': 'connected-drawer' });
      },
    });
    const [ParentDrawer, parentApi] = useVbenDrawer({
      connectedComponent: ConnectedDrawer,
      onOpenChange,
    });

    const wrapper = mount(ParentDrawer);
    await nextTick();

    expect(wrapper.find('[data-test="connected-drawer"]').exists()).toBe(true);
    expect(childApi).toBeDefined();

    parentApi.open();

    expect(childApi?.store.state.isOpen).toBe(true);
    expect(onOpenChange).toHaveBeenCalledWith(true);
  });
});

import type { ExtendedModalApi } from './modal';

import { mount } from '@vue/test-utils';
import { defineComponent, h, nextTick } from 'vue';

import { describe, expect, it, vi } from 'vitest';

import { useVbenModal } from './use-modal';

describe('useVbenModal', () => {
  it('connects the parent API to the modal created by the child component', async () => {
    const onOpenChange = vi.fn();
    let childApi: ExtendedModalApi | undefined;
    const ConnectedModal = defineComponent({
      setup() {
        const [, api] = useVbenModal();
        childApi = api;
        return () => h('div', { 'data-test': 'connected-modal' });
      },
    });
    const [ParentModal, parentApi] = useVbenModal({
      connectedComponent: ConnectedModal,
      onOpenChange,
    });

    const wrapper = mount(ParentModal);
    await nextTick();

    expect(wrapper.find('[data-test="connected-modal"]').exists()).toBe(true);
    expect(childApi).toBeDefined();

    parentApi.open();

    expect(childApi?.store.state.isOpen).toBe(true);
    expect(onOpenChange).toHaveBeenCalledWith(true);
  });
});

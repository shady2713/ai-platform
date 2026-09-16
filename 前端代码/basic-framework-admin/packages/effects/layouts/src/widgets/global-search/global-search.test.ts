import { mount } from '@vue/test-utils';
import { nextTick } from 'vue';

import { beforeEach, describe, expect, it, vi } from 'vitest';

import GlobalSearch from './global-search.vue';

const modal = vi.hoisted(() => ({
  close: vi.fn(),
  open: undefined as undefined | { value: boolean | undefined },
  openModal: vi.fn(),
}));

vi.mock('@vben-core/popup-ui', async () => {
  const { defineComponent, ref } = await import('vue');
  modal.open = ref(false);
  const ModalStub = defineComponent({
    name: 'ModalStub',
    template:
      '<section><slot name="title" /><slot /><slot name="footer" /></section>',
  });
  return {
    useVbenModal: () => [
      ModalStub,
      {
        close: () => {
          modal.close();
          if (modal.open) modal.open.value = false;
        },
        open: () => {
          modal.openModal();
          if (modal.open) modal.open.value = true;
        },
        useStore: () => modal.open,
      },
    ],
  };
});

vi.mock('@vben/locales', () => ({ $t: (key: string) => key }));

vi.mock('@vben/utils', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@vben/utils')>();
  return { ...actual, isWindowsOs: () => true };
});

vi.mock('@vben/icons', async () => {
  const { defineComponent } = await import('vue');
  const IconStub = defineComponent({ template: '<i />' });
  return {
    ArrowDown: IconStub,
    ArrowUp: IconStub,
    CornerDownLeft: IconStub,
    MdiKeyboardEsc: IconStub,
    Search: IconStub,
  };
});

function modalOpen() {
  if (!modal.open) {
    throw new Error('Modal state is not initialized');
  }
  return modal.open;
}

describe('global search', () => {
  beforeEach(() => {
    modal.close.mockReset();
    modal.openModal.mockReset();
    modalOpen().value = false;
  });

  it('uses an accessible trigger and focuses search after opening', async () => {
    const wrapper = mount(GlobalSearch, {
      attachTo: document.body,
      global: { stubs: { SearchPanel: true } },
    });

    const trigger = wrapper.get('button[aria-label="ui.widgets.search.title"]');
    expect(trigger.attributes('type')).toBe('button');
    await trigger.trigger('click');
    await nextTick();

    expect(modal.openModal).toHaveBeenCalledOnce();
    expect(document.activeElement).toBe(
      wrapper.get('input[type="search"]').element,
    );
    expect(wrapper.getComponent({ name: 'SearchPanel' }).props('active')).toBe(
      true,
    );
    wrapper.unmount();
  });

  it('registers and removes the browser shortcut interceptor with the setting', async () => {
    const wrapper = mount(GlobalSearch, {
      global: { stubs: { SearchPanel: true } },
    });
    const enabledEvent = new KeyboardEvent('keydown', {
      cancelable: true,
      ctrlKey: true,
      key: 'k',
    });
    window.dispatchEvent(enabledEvent);
    expect(enabledEvent.defaultPrevented).toBe(true);

    await wrapper.setProps({ enableShortcutKey: false });
    await nextTick();
    const disabledEvent = new KeyboardEvent('keydown', {
      cancelable: true,
      ctrlKey: true,
      key: 'k',
    });
    window.dispatchEvent(disabledEvent);
    expect(disabledEvent.defaultPrevented).toBe(false);
    wrapper.unmount();
  });
});

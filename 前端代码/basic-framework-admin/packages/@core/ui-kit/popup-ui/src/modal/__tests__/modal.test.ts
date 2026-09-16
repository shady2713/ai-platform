import type { ExtendedModalApi, ModalState } from '../modal';

import { flushPromises, mount } from '@vue/test-utils';
import { nextTick, ref } from 'vue';

import { afterEach, describe, expect, it, vi } from 'vitest';

import Modal from '../modal.vue';

vi.mock('vue', async (importOriginal) => {
  const actual = await importOriginal<typeof import('vue')>();
  return { ...actual, useId: () => 'fixed-modal-id' };
});

if (typeof globalThis.requestAnimationFrame !== 'function') {
  globalThis.requestAnimationFrame = (cb: FrameRequestCallback) =>
    setTimeout(() => cb(0), 0) as unknown as number;
}

vi.mock('@vben-core/icons', () => ({
  Expand: { name: 'ExpandStub', template: '<span class="expand-icon" />' },
  Shrink: { name: 'ShrinkStub', template: '<span class="shrink-icon" />' },
}));

vi.mock('@vben-core/shadcn-ui', () => {
  const panelStub = (name: string, kebabName: string) => ({
    name,
    template: `<div class="${kebabName}-stub"><slot /></div>`,
  });
  return {
    Dialog: panelStub('Dialog', 'dialog'),
    DialogContent: {
      name: 'DialogContentStub',
      props: ['open'],
      methods: {
        getContentRef() {
          return this;
        },
      },
      template: '<div v-if="open" class="dialog-content-stub"><slot /></div>',
    },
    DialogDescription: panelStub('DialogDescription', 'dialog-description'),
    DialogFooter: panelStub('DialogFooter', 'dialog-footer'),
    DialogHeader: panelStub('DialogHeader', 'dialog-header'),
    DialogTitle: panelStub('DialogTitle', 'dialog-title'),
    VbenButton: {
      name: 'VbenButtonStub',
      template:
        '<button type="button" class="vben-button-stub"><slot /></button>',
    },
    VbenHelpTooltip: {
      name: 'VbenHelpTooltipStub',
      template: '<span class="help-tooltip-stub"><slot /></span>',
    },
    VbenIconButton: {
      name: 'VbenIconButtonStub',
      template:
        '<button type="button" class="vben-icon-button-stub"><slot /></button>',
    },
    VbenLoading: {
      name: 'VbenLoadingStub',
      template: '<div class="loading-stub" />',
    },
    VisuallyHidden: {
      name: 'VisuallyHiddenStub',
      template: '<span class="visually-hidden-stub"><slot /></span>',
    },
  };
});

// mirrors the defaults from modal-api.ts so dialogs render like production
const mockDefaultState: ModalState = {
  animationType: 'slide',
  bordered: true,
  centered: false,
  closable: true,
  closeOnClickModal: true,
  closeOnPressEscape: true,
  confirmDisabled: false,
  confirmLoading: false,
  destroyOnClose: false,
  draggable: false,
  footer: true,
  fullscreen: false,
  fullscreenButton: true,
  header: true,
  isOpen: false,
  loading: false,
  modal: true,
  openAutoFocus: false,
  showCancelButton: true,
  showConfirmButton: true,
};

type Store = ReturnType<typeof createModalApi>;

function createModalApi(stateOverrides: Partial<ModalState> = {}) {
  const state = ref<ModalState>({
    ...mockDefaultState,
    ...stateOverrides,
  });
  const close = vi.fn();
  const onCancel = vi.fn();
  const onClosed = vi.fn();
  const onConfirm = vi.fn();
  const onOpened = vi.fn();
  const setState = vi.fn(
    (
      updater:
        | ((prev: ModalState) => Partial<ModalState>)
        | Partial<ModalState>,
    ) => {
      state.value =
        typeof updater === 'function'
          ? { ...state.value, ...updater(state.value) }
          : { ...state.value, ...updater };
    },
  );
  const api = {
    close,
    onCancel,
    onClosed,
    onConfirm,
    onOpened,
    setState,
    useStore: () => state,
  } as unknown as ExtendedModalApi;
  return { api, close, onCancel, onClosed, onConfirm, onOpened, setState };
}

function mountModal(
  store: Store,
  props: Record<string, unknown> = {},
  slots: Record<string, string> = {},
) {
  return mount(Modal, {
    slots,
    props: { modalApi: store.api, ...props },
  });
}

const dispatchInit: EventInit = { bubbles: true, cancelable: true };

function dispatchOnContent(
  wrapper: ReturnType<typeof mount>,
  eventName: string,
  init: EventInit = dispatchInit,
) {
  const event = new Event(eventName, init);
  wrapper.get('.dialog-content-stub').element.dispatchEvent(event);
  return event;
}

afterEach(() => {
  Reflect.deleteProperty(document.documentElement, 'clientWidth');
  Reflect.deleteProperty(document.documentElement, 'clientHeight');
});

describe('vbenModal interactions', () => {
  it('triggers the cancel and confirm actions from the footer buttons', async () => {
    const store = createModalApi({ isOpen: true });
    const wrapper = mountModal(store);
    await flushPromises();

    const [cancel, confirm] = wrapper.findAll('.vben-button-stub');
    await cancel?.trigger('click');
    expect(store.onCancel).toHaveBeenCalledTimes(1);
    await confirm?.trigger('click');
    expect(store.onConfirm).toHaveBeenCalledTimes(1);
  });

  it('toggles fullscreen through the dedicated button', async () => {
    const store = createModalApi({ fullscreen: true, isOpen: true });
    const wrapper = mountModal(store, { centered: true });
    await flushPromises();

    const content = wrapper.get('.dialog-content-stub');
    expect(content.classes()).toContain('sm:rounded-none');
    expect(content.classes()).toContain('size-full');
    expect(content.classes()).not.toContain('top-1/2');
    expect(wrapper.find('.shrink-icon').exists()).toBe(true);

    await wrapper.get('.vben-icon-button-stub').trigger('click');
    await nextTick();
    expect(store.setState).toHaveBeenCalledTimes(1);
    expect(wrapper.find('.expand-icon').exists()).toBe(true);
    expect(content.classes()).not.toContain('size-full');
  });

  it('locks the dialog while submitting and blocks manual closing', async () => {
    const store = createModalApi({ isOpen: true, submitting: true });
    const wrapper = mountModal(store, { bordered: false });
    await flushPromises();

    const content = wrapper.get('.dialog-content-stub');
    expect(content.attributes('close-disabled')).toBe('true');
    expect(content.classes()).toContain('shadow-3xl');
    expect(wrapper.get('.dialog-content-stub .relative').classes()).toContain(
      'pointer-events-none',
    );
    expect(wrapper.find('.loading-stub').exists()).toBe(true);
    expect(
      wrapper.get('.vben-button-stub').element.hasAttribute('disabled'),
    ).toBe(true);

    const dialog = wrapper.findComponent({ name: 'Dialog' });
    await dialog.vm.$emit('update:open', false);
    expect(store.close).not.toHaveBeenCalled();
    expect(dispatchOnContent(wrapper, 'escape-key-down').defaultPrevented).toBe(
      true,
    );
    expect(
      dispatchOnContent(wrapper, 'interact-outside').defaultPrevented,
    ).toBe(true);

    const lockChild = document.createElement('span');
    lockChild.dataset.dismissableModal = 'fixed-modal-id';
    wrapper.get('.dialog-content-stub').element.append(lockChild);
    const lockEvent = new Event('pointer-down-outside', {
      bubbles: true,
      cancelable: true,
    });
    lockChild.dispatchEvent(lockEvent);
    expect(lockEvent.defaultPrevented).toBe(true);
  });

  it('closes the dialog through the open state update', async () => {
    const store = createModalApi({ isOpen: true });
    const wrapper = mountModal(store);

    const dialog = wrapper.findComponent({ name: 'Dialog' });
    await dialog.vm.$emit('update:open', false);
    expect(store.close).toHaveBeenCalledTimes(1);
  });

  it('prevents outside interactions when configured', async () => {
    const store = createModalApi({ isOpen: true });
    const wrapper = mountModal(store, {
      closeOnClickModal: false,
      closeOnPressEscape: false,
      openAutoFocus: false,
    });
    await flushPromises();

    expect(dispatchOnContent(wrapper, 'escape-key-down').defaultPrevented).toBe(
      true,
    );
    expect(
      dispatchOnContent(wrapper, 'interact-outside').defaultPrevented,
    ).toBe(true);
    expect(dispatchOnContent(wrapper, 'open-auto-focus').defaultPrevented).toBe(
      true,
    );
    expect(
      dispatchOnContent(wrapper, 'pointer-down-outside').defaultPrevented,
    ).toBe(true);
  });

  it('allows interactions with default settings and always blocks focus moves', async () => {
    const store = createModalApi({ isOpen: true });
    const wrapper = mountModal(store);
    await flushPromises();

    expect(dispatchOnContent(wrapper, 'escape-key-down').defaultPrevented).toBe(
      false,
    );
    expect(
      dispatchOnContent(wrapper, 'interact-outside').defaultPrevented,
    ).toBe(false);
    expect(dispatchOnContent(wrapper, 'focus-outside').defaultPrevented).toBe(
      true,
    );
    expect(
      dispatchOnContent(wrapper, 'close-auto-focus').defaultPrevented,
    ).toBe(true);

    const child = document.createElement('span');
    child.dataset.dismissableModal = 'another-modal';
    wrapper.get('.dialog-content-stub').element.append(child);
    const event = new Event('pointer-down-outside', {
      bubbles: true,
      cancelable: true,
    });
    child.dispatchEvent(event);
    expect(event.defaultPrevented).toBe(true);

    // a target marked with the modal's own id is not blocked
    const ownChild = document.createElement('span');
    ownChild.dataset.dismissableModal = 'fixed-modal-id';
    wrapper.get('.dialog-content-stub').element.append(ownChild);
    const ownEvent = new Event('pointer-down-outside', {
      bubbles: true,
      cancelable: true,
    });
    ownChild.dispatchEvent(ownEvent);
    expect(ownEvent.defaultPrevented).toBe(false);
  });

  it('fires the open and close lifecycle callbacks', async () => {
    const store = createModalApi({ isOpen: true });
    const wrapper = mountModal(store);
    await flushPromises();

    expect(dispatchOnContent(wrapper, 'closed').defaultPrevented).toBe(false);
    expect(store.onClosed).toHaveBeenCalledTimes(1);
    await nextTick();
    expect(wrapper.get('.dialog-content-stub').classes()).toContain('hidden');

    dispatchOnContent(wrapper, 'opened');
    await vi.waitFor(() => {
      expect(store.onOpened).toHaveBeenCalledTimes(1);
    });
  });

  it('closes the modal when deactivated unless it is appended to main', async () => {
    const Host = {
      components: { Modal },
      props: {
        appendToMain: { type: Boolean, default: false },
        modalApi: { type: Object, required: true },
      },
      data() {
        return { show: true };
      },
      template:
        '<KeepAlive><Modal v-if="show" :append-to-main="appendToMain" :modal-api="modalApi" /></KeepAlive>',
    };

    const detached = createModalApi({ isOpen: true });
    const host = mount(Host, { props: { modalApi: detached.api } });
    await host.setData({ show: false });
    expect(detached.close).toHaveBeenCalledTimes(1);

    const attached = createModalApi({ isOpen: true });
    const host2 = mount(Host, {
      props: { appendToMain: true, modalApi: attached.api },
    });
    await host2.setData({ show: false });
    expect(attached.close).not.toHaveBeenCalled();
  });

  it('supports dragging the modal by its header', async () => {
    // happy-dom reports a zero viewport, which would clamp the drag to zero
    Object.defineProperty(document.documentElement, 'clientWidth', {
      configurable: true,
      value: 1024,
    });
    Object.defineProperty(document.documentElement, 'clientHeight', {
      configurable: true,
      value: 768,
    });
    const store = createModalApi({ isOpen: true });
    const wrapper = mountModal(store, { draggable: true, title: 'Draggable' });
    await flushPromises();

    const header = wrapper.get('.dialog-header-stub');
    expect(header.classes()).toContain('cursor-move');
    const content = wrapper.get('.dialog-content-stub');
    expect(content.classes()).toContain('duration-300');

    header.element.dispatchEvent(
      new MouseEvent('mousedown', { bubbles: true, clientX: 10, clientY: 10 }),
    );
    document.dispatchEvent(
      new MouseEvent('mousemove', { clientX: 60, clientY: 30 }),
    );
    await nextTick();
    expect(content.classes()).not.toContain('duration-300');
    expect(content.attributes('style')).toContain('translate(50px, 20px)');

    document.dispatchEvent(new MouseEvent('mouseup'));
    await nextTick();
    expect(content.classes()).toContain('duration-300');
  });
});

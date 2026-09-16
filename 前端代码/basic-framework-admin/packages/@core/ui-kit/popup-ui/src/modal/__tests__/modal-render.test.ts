import type { ExtendedModalApi, ModalState } from '../modal';

import { flushPromises, mount } from '@vue/test-utils';
import { ref } from 'vue';

import { globalShareState } from '@vben-core/shared/global-state';

import { afterEach, describe, expect, it, vi } from 'vitest';

import Modal from '../modal.vue';

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

afterEach(() => {
  globalShareState.setComponents({});
});

describe('vbenModal rendering', () => {
  it('keeps content hidden while closed and renders the default layout when opened', async () => {
    const closed = mountModal(createModalApi());
    await flushPromises();
    expect(closed.find('.dialog-content-stub').exists()).toBe(false);

    const store = createModalApi({ isOpen: true });
    const wrapper = mountModal(store);
    await flushPromises();
    const content = wrapper.get('.dialog-content-stub');
    expect(content.attributes('style')).toContain('translate(0px, 0px)');
    expect(content.attributes('force-mount')).toBe('true');
    expect(content.attributes('append-to')).toBeUndefined();
    expect(content.attributes('close-disabled')).toBeUndefined();
    expect(content.classes()).toContain('border-border');
    expect(content.classes()).toContain('duration-300');
    expect(content.classes()).not.toContain('hidden');
    expect(wrapper.find('.visually-hidden-stub').exists()).toBe(true);
  });

  it('renders an opened dialog with every configured section', async () => {
    const store = createModalApi({ isOpen: true });
    const wrapper = mountModal(
      store,
      {
        animationType: 'scale',
        appendToMain: true,
        bordered: true,
        cancelText: 'Abort',
        centered: true,
        class: 'my-modal',
        closable: true,
        confirmDisabled: true,
        confirmLoading: true,
        confirmText: 'Go',
        contentClass: 'my-content',
        description: 'A description',
        footerClass: 'my-footer',
        headerClass: 'my-header',
        modal: true,
        openAutoFocus: true,
        overlayBlur: 3,
        showCancelButton: true,
        showConfirmButton: true,
        title: 'A title',
        titleTooltip: 'A tip',
        zIndex: 88,
      },
      { default: '<div class="slot-body">body</div>' },
    );
    await flushPromises();

    const content = wrapper.get('.dialog-content-stub');
    expect(content.attributes('style')).toContain(
      'translate(0px, calc(-50% + 0px))',
    );
    expect(content.attributes('append-to')).toContain(
      '#__vben_main_content>div:not(.absolute)>div',
    );
    expect(content.attributes('show-close')).toBe('true');
    expect(content.attributes('animation-type')).toBe('scale');
    expect(content.attributes('z-index')).toBe('88');
    expect(content.attributes('overlay-blur')).toBe('3');
    expect(content.attributes('modal')).toBe('true');
    expect(content.classes()).toContain('top-1/2');
    expect(content.classes()).toContain('my-modal');

    const header = wrapper.get('.dialog-header-stub');
    expect(header.classes()).toContain('border-b');
    expect(header.classes()).toContain('my-header');
    expect(header.classes()).not.toContain('cursor-move');
    expect(wrapper.get('.dialog-title-stub').text()).toContain('A title');
    expect(wrapper.get('.dialog-description-stub').text()).toContain(
      'A description',
    );
    expect(wrapper.get('.help-tooltip-stub').text()).toContain('A tip');
    expect(wrapper.find('.visually-hidden-stub').exists()).toBe(false);
    expect(wrapper.get('.slot-body').text()).toBe('body');
    expect(wrapper.get('.dialog-content-stub .relative').classes()).toContain(
      'my-content',
    );

    const footer = wrapper.get('.dialog-footer-stub');
    expect(footer.classes()).toContain('border-t');
    expect(footer.classes()).toContain('my-footer');
    const [cancel, confirm] = wrapper.findAll('.vben-button-stub');
    expect(cancel?.text()).toBe('Abort');
    expect(confirm?.text()).toBe('Go');
    expect(confirm?.attributes('disabled')).toBeDefined();
    expect(confirm?.attributes('loading')).toBeDefined();
  });

  it('hides the header and renders visually hidden dialogs for missing fields', async () => {
    const store = createModalApi({
      description: 'Only a description',
      isOpen: true,
    });
    const wrapper = mountModal(store, {
      header: false,
      showCancelButton: false,
      showConfirmButton: false,
      title: '',
    });
    await flushPromises();

    expect(wrapper.get('.dialog-header-stub').classes()).toContain('hidden');
    const hidden = wrapper.get('.visually-hidden-stub');
    expect(hidden.find('.dialog-title-stub').exists()).toBe(true);
    expect(hidden.find('.dialog-description-stub').exists()).toBe(false);
    expect(wrapper.find('.vben-button-stub').exists()).toBe(false);

    const onlyTitle = createModalApi({ isOpen: true, title: 'Only title' });
    const wrapper2 = mountModal(onlyTitle);
    await flushPromises();
    const hidden2 = wrapper2.get('.visually-hidden-stub');
    expect(hidden2.find('.dialog-title-stub').exists()).toBe(false);
    expect(hidden2.find('.dialog-description-stub').exists()).toBe(true);
  });

  it('hides the footer or renders its slots on demand', async () => {
    const noFooter = createModalApi({ isOpen: true });
    const wrapper = mountModal(noFooter, {
      footer: false,
      showCancelButton: true,
      showConfirmButton: false,
    });
    await flushPromises();
    expect(wrapper.find('.dialog-footer-stub').exists()).toBe(false);
    expect(wrapper.findAll('.vben-button-stub')).toHaveLength(0);

    const store = createModalApi({ isOpen: true });
    const withSlots = mountModal(
      store,
      { showCancelButton: false, showConfirmButton: false },
      {
        'append-footer': '<span class="append-slot">post</span>',
        'center-footer': '<span class="center-slot">mid</span>',
        'prepend-footer': '<span class="prepend-slot">pre</span>',
      },
    );
    await flushPromises();
    expect(withSlots.get('.prepend-slot').text()).toBe('pre');
    expect(withSlots.get('.center-slot').text()).toBe('mid');
    expect(withSlots.get('.append-slot').text()).toBe('post');

    const custom = createModalApi({ isOpen: true });
    const customFooter = mountModal(
      custom,
      {},
      {
        footer: '<div class="custom-footer">custom</div>',
      },
    );
    await flushPromises();
    expect(customFooter.get('.custom-footer').text()).toBe('custom');
    expect(customFooter.findAll('.vben-button-stub')).toHaveLength(0);
  });

  it('falls back to registered buttons, store values and localized labels', async () => {
    const localized = createModalApi({ isOpen: true });
    const plain = mountModal(localized);
    await flushPromises();
    const [cancelLabel, confirmLabel] = plain.findAll('.vben-button-stub');
    expect(cancelLabel?.text()).toBe('取消');
    expect(confirmLabel?.text()).toBe('确认');

    globalShareState.setComponents({
      DefaultButton: {
        name: 'DefaultButtonStub',
        template: '<button class="default-button-stub"><slot /></button>',
      },
      PrimaryButton: {
        name: 'PrimaryButtonStub',
        template: '<button class="primary-button-stub"><slot /></button>',
      },
    });
    const store = createModalApi({
      cancelText: 'State cancel',
      confirmText: 'State confirm',
      fullscreenButton: false,
      isOpen: true,
      title: 'State title',
    });
    const wrapper = mountModal(store);
    await flushPromises();

    expect(wrapper.get('.dialog-title-stub').text()).toContain('State title');
    expect(wrapper.find('.default-button-stub').exists()).toBe(true);
    expect(wrapper.find('.primary-button-stub').exists()).toBe(true);
    expect(wrapper.findAll('.vben-button-stub')).toHaveLength(0);
    expect(wrapper.find('.vben-icon-button-stub').exists()).toBe(false);
  });

  it('controls force mount through destroyOnClose', async () => {
    const store = createModalApi({ isOpen: true });
    const wrapper = mountModal(store, { destroyOnClose: true });
    await flushPromises();

    expect(wrapper.get('.dialog-content-stub').attributes('force-mount')).toBe(
      'false',
    );
  });
});

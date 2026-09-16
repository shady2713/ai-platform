import type { Component } from 'vue';

import { flushPromises, mount } from '@vue/test-utils';
import { defineComponent, h, nextTick, onUnmounted } from 'vue';

import { beforeEach, describe, expect, it, vi } from 'vitest';

import CropperModal from './cropper-modal.vue';

interface ModalConfig {
  onConfirm: () => Promise<void>;
  onOpenChange: (isOpen: boolean) => void;
}

const mocks = vi.hoisted(() => ({
  close: vi.fn(() => Promise.resolve()),
  config: undefined as ModalConfig | undefined,
  dataURLtoBlob: vi.fn(() => new Blob(['avatar'], { type: 'image/png' })),
  setState: vi.fn(),
  showWarningMessage: vi.fn(),
  unmounted: vi.fn(),
}));

vi.mock('@vben/common-ui', async () => {
  const { defineComponent, h } = await import('vue');
  const Modal = defineComponent({
    name: 'ModalStub',
    inheritAttrs: false,
    setup(_props, { attrs, slots }) {
      return () => h('section', attrs, [slots.default?.(), slots.footer?.()]);
    },
  });
  return {
    useVbenModal: vi.fn((config: ModalConfig) => {
      mocks.config = config;
      return [
        Modal,
        {
          close: mocks.close,
          setState: mocks.setState,
        },
      ];
    }),
  };
});

vi.mock('@vben/icons', () => ({
  IconifyIcon: defineComponent({
    name: 'IconifyIcon',
    template: '<i />',
  }),
}));

vi.mock('@vben/locales', () => ({ $t: (key: string) => key }));

vi.mock('@vben/utils', () => ({ dataURLtoBlob: mocks.dataURLtoBlob }));

vi.mock('#/utils/feedback', () => ({
  showWarningMessage: mocks.showWarningMessage,
}));

vi.mock('element-plus', () => {
  function stub(name: string, tag = 'div') {
    return defineComponent({
      name,
      inheritAttrs: false,
      props: {
        beforeUpload: { default: undefined, type: Function },
        disabled: Boolean,
        size: { default: undefined, type: null },
        src: { default: undefined, type: null },
      },
      emits: ['click'],
      setup(_props, { attrs, emit, slots }) {
        return () =>
          h(tag, { ...attrs, onClick: () => emit('click') }, slots.default?.());
      },
    });
  }

  return {
    ElAvatar: stub('ElAvatar'),
    ElButton: stub('ElButton', 'button'),
    ElSpace: stub('ElSpace'),
    ElTooltip: stub('ElTooltip'),
    ElUpload: stub('ElUpload'),
  };
});

const CropperImageStub = defineComponent({
  name: 'CropperImage',
  props: {
    circled: Boolean,
    src: { default: '', type: String },
  },
  emits: ['cropend', 'cropendError', 'ready'],
  setup() {
    onUnmounted(mocks.unmounted);
    return () => h('div', { 'data-test': 'cropper-image' });
  },
});

class FileReaderStub {
  static mode: 'error' | 'invalid' | 'success' = 'success';

  error: DOMException | null = null;
  result: ArrayBuffer | null | string = null;

  private readonly listeners = new Map<
    string,
    EventListenerOrEventListenerObject
  >();

  addEventListener(type: string, listener: EventListenerOrEventListenerObject) {
    this.listeners.set(type, listener);
  }

  readAsDataURL() {
    if (FileReaderStub.mode === 'error') {
      this.error = new DOMException('read failed');
      this.dispatch('error');
      return;
    }
    this.result =
      FileReaderStub.mode === 'invalid'
        ? new ArrayBuffer(0)
        : 'data:image/png;base64,YQ==';
    this.dispatch('load');
  }

  private dispatch(type: string) {
    const listener = this.listeners.get(type);
    if (!listener) return;
    const event = { target: this } as unknown as Event;
    if (typeof listener === 'function') listener(event);
    else listener.handleEvent(event);
  }
}

function mountModal(
  props: Record<string, unknown> = {},
  cropperStub: Component = CropperImageStub,
) {
  return mount(CropperModal, {
    global: { stubs: { CropperImage: cropperStub } },
    props,
  });
}

function requireModalConfig() {
  expect(mocks.config).toBeDefined();
  return mocks.config as ModalConfig;
}

function beforeUpload(wrapper: ReturnType<typeof mountModal>, file: File) {
  const handler = wrapper
    .findComponent({ name: 'ElUpload' })
    .props('beforeUpload') as (file: File) => boolean;
  return handler(file);
}

function createCropperInstance() {
  return {
    reset: vi.fn(),
    rotate: vi.fn(),
    scaleX: vi.fn(),
    scaleY: vi.fn(),
    zoom: vi.fn(),
  };
}

beforeEach(() => {
  vi.clearAllMocks();
  mocks.config = undefined;
  FileReaderStub.mode = 'success';
  vi.stubGlobal('FileReader', FileReaderStub);
});

describe('cropperModal', () => {
  it('opens without permanent loading when no source exists and remounts existing images', async () => {
    const empty = mountModal();
    requireModalConfig().onOpenChange(true);
    expect(mocks.setState).toHaveBeenLastCalledWith({
      confirmLoading: false,
      loading: false,
    });
    requireModalConfig().onOpenChange(false);
    expect(empty.findComponent(CropperImageStub).exists()).toBe(false);

    mountModal({ src: 'avatar.png' });
    const config = requireModalConfig();
    config.onOpenChange(true);
    await nextTick();
    const unmountCount = mocks.unmounted.mock.calls.length;
    expect(mocks.setState).toHaveBeenLastCalledWith({
      confirmLoading: true,
      loading: true,
    });
    config.onOpenChange(false);
    config.onOpenChange(true);
    await nextTick();
    expect(mocks.unmounted).toHaveBeenCalledTimes(unmountCount + 1);
  });

  it('rejects non-images and oversized images with visible errors', () => {
    const wrapper = mountModal({ size: 1 });

    expect(
      beforeUpload(
        wrapper,
        new File(['pdf'], 'document.pdf', { type: 'application/pdf' }),
      ),
    ).toBe(false);
    expect(mocks.showWarningMessage).toHaveBeenLastCalledWith(
      'ui.cropper.invalidImageType',
    );

    const oversized = new File([new Uint8Array(1024 * 1024 + 1)], 'big.png', {
      type: 'image/png',
    });
    expect(beforeUpload(wrapper, oversized)).toBe(false);
    expect(mocks.showWarningMessage).toHaveBeenLastCalledWith(
      'ui.cropper.imageTooBig',
    );
    expect(wrapper.emitted('uploadError')).toHaveLength(2);
  });

  it.each(['invalid', 'error'] as const)(
    'reports image reader %s results and clears loading',
    (mode) => {
      FileReaderStub.mode = mode;
      const wrapper = mountModal({ src: 'original.png' });

      beforeUpload(
        wrapper,
        new File(['image'], 'avatar.png', { type: 'image/png' }),
      );

      expect(mocks.showWarningMessage).toHaveBeenCalledWith(
        'ui.cropper.imageReadError',
      );
      expect(mocks.setState).toHaveBeenLastCalledWith({
        confirmLoading: false,
        loading: false,
      });
      expect(wrapper.emitted('uploadError')).toHaveLength(1);
      expect(wrapper.findComponent(CropperImageStub).props('src')).toBe(
        'original.png',
      );
    },
  );

  it('loads valid images, previews crops, and invokes each typed toolbar action', async () => {
    const wrapper = mountModal();
    beforeUpload(
      wrapper,
      new File(['image'], 'avatar.png', { type: 'image/png' }),
    );
    await nextTick();
    const cropperComponent = wrapper.findComponent(CropperImageStub);
    expect(cropperComponent.props('src')).toBe('data:image/png;base64,YQ==');

    const instance = createCropperInstance();
    cropperComponent.vm.$emit('ready', instance);
    cropperComponent.vm.$emit('cropend', {
      imgBase64: 'data:image/png;base64,Y3JvcA==',
      imgInfo: {},
    });
    await nextTick();
    expect(wrapper.find('img').attributes('src')).toBe(
      'data:image/png;base64,Y3JvcA==',
    );

    const actions = [
      'ui.cropper.btn_reset',
      'ui.cropper.btn_rotate_left',
      'ui.cropper.btn_rotate_right',
      'ui.cropper.btn_scale_x',
      'ui.cropper.btn_scale_y',
      'ui.cropper.btn_zoom_in',
      'ui.cropper.btn_zoom_out',
    ];
    for (const action of actions) {
      const button = wrapper
        .findAllComponents({ name: 'ElButton' })
        .find((candidate) => candidate.attributes('aria-label') === action);
      if (!button) throw new Error(`Missing toolbar action: ${action}`);
      button.vm.$emit('click');
    }

    expect(instance.reset).toHaveBeenCalledOnce();
    expect(instance.rotate.mock.calls).toEqual([[-45], [45]]);
    expect(instance.scaleX).toHaveBeenCalledWith(-1);
    expect(instance.scaleY).toHaveBeenCalledWith(-1);
    expect(instance.zoom.mock.calls).toEqual([[0.1], [-0.1]]);
  });

  it('requires a preview, uploads a valid crop, and closes after success', async () => {
    const uploadApi = vi.fn(() => Promise.resolve({ url: '/avatar.png' }));
    const wrapper = mountModal({ uploadApi });
    const config = requireModalConfig();

    await config.onConfirm();
    expect(mocks.showWarningMessage).toHaveBeenCalledWith('未选择图片');
    expect(uploadApi).not.toHaveBeenCalled();

    beforeUpload(
      wrapper,
      new File(['image'], 'portrait.png', { type: 'image/png' }),
    );
    await nextTick();
    wrapper.findComponent(CropperImageStub).vm.$emit('cropend', {
      imgBase64: 'data:image/png;base64,Y3JvcA==',
      imgInfo: {},
    });
    await config.onConfirm();

    expect(uploadApi).toHaveBeenCalledWith({
      file: expect.any(Blob),
      filename: 'portrait.png',
      name: 'file',
    });
    expect(wrapper.emitted('uploadSuccess')?.[0]).toEqual([
      {
        data: { url: '/avatar.png' },
        source: 'data:image/png;base64,Y3JvcA==',
      },
    ]);
    expect(mocks.close).toHaveBeenCalledOnce();
  });

  it('keeps the modal open and emits a visible error when processing or upload fails', async () => {
    const uploadError = new Error('network failed');
    const wrapper = mountModal({
      src: 'avatar.png',
      uploadApi: vi.fn(() => Promise.reject(uploadError)),
    });
    const cropperComponent = wrapper.findComponent(CropperImageStub);
    cropperComponent.vm.$emit('cropendError', new Error('canvas failed'));
    cropperComponent.vm.$emit('cropend', {
      imgBase64: 'data:image/png;base64,Y3JvcA==',
      imgInfo: {},
    });

    await requireModalConfig().onConfirm();
    await flushPromises();

    expect(mocks.close).not.toHaveBeenCalled();
    expect(mocks.showWarningMessage).toHaveBeenCalledWith(
      'ui.cropper.imageProcessError',
    );
    expect(mocks.showWarningMessage).toHaveBeenCalledWith(
      'ui.cropper.uploadError',
    );
    expect(wrapper.emitted('uploadError')).toHaveLength(2);
  });
});

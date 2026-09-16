import type { UploadFile, UploadRawFile } from 'element-plus';

import { mount } from '@vue/test-utils';

import { beforeEach, describe, expect, it, vi } from 'vitest';

import { showSuccessMessage, showWarningMessage } from '#/utils/feedback';

import UploadForm from './form.vue';

interface ModalConfig {
  onConfirm: () => Promise<void>;
}

const state = vi.hoisted(() => ({
  formApi: {
    getValues: vi.fn(),
    setFieldValue: vi.fn(),
    validate: vi.fn<() => Promise<{ valid: boolean }>>(),
  },
  httpRequest: vi.fn(),
  modalApi: {
    close: vi.fn(() => Promise.resolve()),
    lock: vi.fn(),
    unlock: vi.fn(),
  },
  modalConfig: undefined as ModalConfig | undefined,
  uploadHandlers: undefined as Record<string, unknown> | undefined,
}));

vi.mock('@vben/common-ui', async () => {
  const { defineComponent, h } = await import('vue');
  return {
    useVbenModal: vi.fn((config: ModalConfig) => {
      state.modalConfig = config;
      return [
        defineComponent({
          name: 'ModalStub',
          setup(_props, { slots }) {
            return () => h('section', slots.default?.());
          },
        }),
        state.modalApi,
      ];
    }),
  };
});

vi.mock('#/adapter/form', async () => {
  const { defineComponent, h } = await import('vue');
  return {
    useVbenForm: vi.fn(() => [
      defineComponent({
        name: 'FormStub',
        setup(_props, { slots }) {
          return () => h('form', [slots.default?.(), slots.file?.()]);
        },
      }),
      state.formApi,
    ]),
  };
});

vi.mock('#/components/upload/use-upload', () => ({
  useUpload: vi.fn(() => ({ httpRequest: state.httpRequest })),
}));

vi.mock('element-plus', async () => {
  const { defineComponent, h } = await import('vue');
  return {
    ElUpload: defineComponent({
      name: 'ElUpload',
      inheritAttrs: false,
      props: {
        accept: { type: String, default: '' },
        autoUpload: Boolean,
        limit: { type: Number, default: 1 },
      },
      setup(_props, { attrs, slots }) {
        state.uploadHandlers = attrs;
        return () => h('div', { 'data-test': 'upload' }, slots.default?.());
      },
    }),
  };
});

vi.mock('#/locales', () => ({
  $t: (key: string) => key,
}));

vi.mock('#/utils/feedback', () => ({
  showSuccessMessage: vi.fn(),
  showWarningMessage: vi.fn(),
}));

vi.mock('../data', () => ({
  useFormSchema: vi.fn(() => []),
}));

function modalConfig() {
  if (!state.modalConfig) {
    throw new Error('弹窗配置未初始化');
  }
  return state.modalConfig;
}

function mountForm() {
  return mount(UploadForm);
}

function uploadFile(): UploadRawFile {
  return new File(['image'], 'photo.png', {
    type: 'image/png',
  }) as UploadRawFile;
}

describe('infra file upload form modal', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    state.modalConfig = undefined;
    state.formApi.validate.mockResolvedValue({ valid: true });
    state.httpRequest.mockResolvedValue('uploaded');
  });

  it('validates before uploading and reports success', async () => {
    const picked = uploadFile();
    state.formApi.getValues.mockResolvedValue({ file: picked });
    const wrapper = mountForm();

    await modalConfig().onConfirm();

    expect(state.httpRequest).toHaveBeenCalledWith(picked);
    expect(state.modalApi.close).toHaveBeenCalledOnce();
    expect(wrapper.emitted('success')).toHaveLength(1);
    expect(showSuccessMessage).toHaveBeenCalledWith(
      'ui.actionMessage.operationSuccess',
    );
    expect(state.modalApi.unlock).toHaveBeenCalledOnce();
  });

  it('skips the upload when validation fails', async () => {
    state.formApi.validate.mockResolvedValue({ valid: false });
    mountForm();

    await modalConfig().onConfirm();

    expect(state.httpRequest).not.toHaveBeenCalled();
    expect(state.modalApi.lock).not.toHaveBeenCalled();
  });

  it('stores the picked file, guards the single-file limit and never auto-uploads', () => {
    const wrapper = mountForm();
    const handlers = state.uploadHandlers as Record<string, unknown>;
    const picked = uploadFile();

    expect(wrapper.findComponent({ name: 'ElUpload' }).props('accept')).toBe(
      '.jpg,.png,.gif,.webp',
    );
    (handlers['on-change'] as (file: UploadFile) => void)({
      name: picked.name,
      percentage: 0,
      raw: picked,
      size: picked.size,
      status: 'ready',
      uid: 1,
    });
    expect(state.formApi.setFieldValue).toHaveBeenCalledWith('file', picked);

    (handlers['on-exceed'] as () => void)();
    expect(showWarningMessage).toHaveBeenCalledWith('最多只能上传一个文件');

    expect(
      (handlers['before-upload'] as (raw: UploadRawFile) => boolean)(picked),
    ).toBe(false);
  });

  it('unlocks the modal when the upload fails', async () => {
    state.formApi.getValues.mockResolvedValue({ file: uploadFile() });
    state.httpRequest.mockRejectedValue(new Error('upload failed'));
    mountForm();

    await expect(modalConfig().onConfirm()).rejects.toThrow('upload failed');

    expect(state.modalApi.close).not.toHaveBeenCalled();
    expect(state.modalApi.unlock).toHaveBeenCalledOnce();
  });
});

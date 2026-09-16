import type { InfraFileConfigApi } from '#/api/infra/file-config';

import { mount } from '@vue/test-utils';

import { beforeEach, describe, expect, it, vi } from 'vitest';

import {
  createFileConfig,
  getFileConfig,
  updateFileConfig,
} from '#/api/infra/file-config';
import { showSuccessMessage } from '#/utils/feedback';

import FileConfigForm from './form.vue';

interface ModalConfig {
  onConfirm: () => Promise<void>;
  onOpenChange: (isOpen: boolean) => Promise<void>;
}

const state = vi.hoisted(() => ({
  formApi: {
    getValues: vi.fn<() => Promise<InfraFileConfigApi.FileConfig>>(),
    setValues: vi.fn(() => Promise.resolve()),
    validate: vi.fn<() => Promise<{ valid: boolean }>>(),
  },
  modalApi: {
    close: vi.fn(() => Promise.resolve()),
    getData: vi.fn(),
    lock: vi.fn(),
    setState: vi.fn(),
    unlock: vi.fn(),
  },
  modalConfig: undefined as ModalConfig | undefined,
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
          return () => h('form', slots.default?.());
        },
      }),
      state.formApi,
    ]),
  };
});

vi.mock('#/api/infra/file-config', () => ({
  createFileConfig: vi.fn(),
  getFileConfig: vi.fn(),
  updateFileConfig: vi.fn(),
}));

vi.mock('#/locales', () => ({
  $t: (key: string) => key,
}));

vi.mock('#/utils/feedback', () => ({
  showSuccessMessage: vi.fn(),
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
  return mount(FileConfigForm);
}

function fileConfig(
  overrides: Partial<InfraFileConfigApi.FileConfig> = {},
): InfraFileConfigApi.FileConfig {
  return {
    config: { basePath: '/data/files', domain: 'http://cdn.example' },
    id: 7,
    master: false,
    name: '本地存储',
    remark: '',
    storage: 1,
    visible: true,
    ...overrides,
  };
}

describe('infra file-config form modal', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    state.modalConfig = undefined;
    state.formApi.validate.mockResolvedValue({ valid: true });
    state.formApi.getValues.mockResolvedValue(fileConfig());
    state.modalApi.getData.mockReturnValue({ id: 7 });
    vi.mocked(getFileConfig).mockResolvedValue(
      fileConfig({ name: '远程存储' }),
    );
  });

  it('validates before submitting and creates a file config', async () => {
    const wrapper = mountForm();
    state.modalApi.getData.mockReturnValue({});

    await modalConfig().onOpenChange(true);
    await modalConfig().onConfirm();

    expect(createFileConfig).toHaveBeenCalledWith(fileConfig());
    expect(updateFileConfig).not.toHaveBeenCalled();
    expect(state.modalApi.close).toHaveBeenCalledOnce();
    expect(wrapper.emitted('success')).toHaveLength(1);
    expect(showSuccessMessage).toHaveBeenCalledWith(
      'ui.actionMessage.operationSuccess',
    );
  });

  it('skips submission when validation fails', async () => {
    state.formApi.validate.mockResolvedValue({ valid: false });
    mountForm();

    await modalConfig().onConfirm();

    expect(createFileConfig).not.toHaveBeenCalled();
    expect(state.modalApi.lock).not.toHaveBeenCalled();
  });

  it('loads the config for editing and submits via update', async () => {
    const wrapper = mountForm();

    await modalConfig().onOpenChange(true);
    expect(getFileConfig).toHaveBeenCalledWith(7);
    expect(state.formApi.setValues).toHaveBeenCalledWith(
      fileConfig({ name: '远程存储' }),
    );

    await modalConfig().onConfirm();
    expect(updateFileConfig).toHaveBeenCalledWith(fileConfig());
    expect(wrapper.emitted('success')).toHaveLength(1);
  });

  it('resets the editing state when the modal closes', async () => {
    mountForm();
    await modalConfig().onOpenChange(false);
    state.modalApi.getData.mockReturnValue({ id: 7 });

    await modalConfig().onOpenChange(true);
    expect(getFileConfig).toHaveBeenCalledOnce();

    await modalConfig().onOpenChange(false);
    await modalConfig().onConfirm();
    expect(createFileConfig).toHaveBeenCalledOnce();
    expect(updateFileConfig).not.toHaveBeenCalled();
  });

  it('unlocks the modal when the update fails', async () => {
    vi.mocked(updateFileConfig).mockRejectedValue(new Error('request failed'));
    mountForm();

    await modalConfig().onOpenChange(true);
    await expect(modalConfig().onConfirm()).rejects.toThrow('request failed');

    expect(state.modalApi.lock).toHaveBeenCalledTimes(2);
    expect(state.modalApi.unlock).toHaveBeenCalledTimes(2);
  });
});

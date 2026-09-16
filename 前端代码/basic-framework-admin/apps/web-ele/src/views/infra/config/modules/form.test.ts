import type { InfraConfigApi } from '#/api/infra/config';

import { mount } from '@vue/test-utils';

import { beforeEach, describe, expect, it, vi } from 'vitest';

import { createConfig, getConfig, updateConfig } from '#/api/infra/config';
import { showSuccessMessage } from '#/utils/feedback';

import ConfigForm from './form.vue';

interface ModalConfig {
  onConfirm: () => Promise<void>;
  onOpenChange: (isOpen: boolean) => Promise<void>;
}

const state = vi.hoisted(() => ({
  formApi: {
    getValues: vi.fn<() => Promise<InfraConfigApi.Config>>(),
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

vi.mock('#/api/infra/config', () => ({
  createConfig: vi.fn(),
  getConfig: vi.fn(),
  updateConfig: vi.fn(),
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
  return mount(ConfigForm);
}

function configData(
  overrides: Partial<InfraConfigApi.Config> = {},
): InfraConfigApi.Config {
  return {
    category: 'default',
    id: 7,
    key: 'key',
    name: '新参数',
    remark: '',
    type: 1,
    value: 'v1',
    visible: true,
    ...overrides,
  };
}

describe('infra config form modal', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    state.modalConfig = undefined;
    state.formApi.validate.mockResolvedValue({ valid: true });
    state.formApi.getValues.mockResolvedValue(configData());
    state.modalApi.getData.mockReturnValue({ id: 7 });
    vi.mocked(getConfig).mockResolvedValue(configData({ value: 'fetched' }));
  });

  it('validates before submitting and creates a config', async () => {
    const wrapper = mountForm();

    await modalConfig().onConfirm();

    expect(createConfig).toHaveBeenCalledWith(configData());
    expect(updateConfig).not.toHaveBeenCalled();
    expect(state.modalApi.close).toHaveBeenCalledOnce();
    expect(wrapper.emitted('success')).toHaveLength(1);
    expect(showSuccessMessage).toHaveBeenCalledWith(
      'ui.actionMessage.operationSuccess',
    );
    expect(state.modalApi.unlock).toHaveBeenCalledOnce();
  });

  it('skips submission when validation fails', async () => {
    state.formApi.validate.mockResolvedValue({ valid: false });
    mountForm();

    await modalConfig().onConfirm();

    expect(state.formApi.getValues).not.toHaveBeenCalled();
    expect(createConfig).not.toHaveBeenCalled();
    expect(state.modalApi.lock).not.toHaveBeenCalled();
  });

  it('loads the config before editing and submits via update', async () => {
    const wrapper = mountForm();

    await modalConfig().onOpenChange(true);
    expect(getConfig).toHaveBeenCalledWith(7);
    expect(state.formApi.setValues).toHaveBeenCalledWith(
      configData({ value: 'fetched' }),
    );
    expect(state.modalApi.lock).toHaveBeenCalledOnce();
    expect(state.modalApi.unlock).toHaveBeenCalledOnce();

    await modalConfig().onConfirm();
    expect(updateConfig).toHaveBeenCalledWith(configData());
    expect(createConfig).not.toHaveBeenCalled();
    expect(wrapper.emitted('success')).toHaveLength(1);
  });

  it('resets state on close and never fetches without an id', async () => {
    state.modalApi.getData.mockReturnValue({});
    mountForm();

    await modalConfig().onOpenChange(true);
    expect(getConfig).not.toHaveBeenCalled();
    expect(state.modalApi.lock).not.toHaveBeenCalled();

    state.modalApi.getData.mockReturnValue({ id: 7 });
    await modalConfig().onOpenChange(false);
    await modalConfig().onOpenChange(true);
    expect(getConfig).toHaveBeenCalledOnce();
  });

  it('keeps the modal open and unlocks when the create fails', async () => {
    vi.mocked(createConfig).mockRejectedValue(new Error('request failed'));
    const wrapper = mountForm();

    await expect(modalConfig().onConfirm()).rejects.toThrow('request failed');

    expect(state.modalApi.close).not.toHaveBeenCalled();
    expect(wrapper.emitted('success')).toBeUndefined();
    expect(state.modalApi.unlock).toHaveBeenCalledOnce();
  });
});

import type { SystemDictDataApi } from '#/api/system/dict/data';

import { mount } from '@vue/test-utils';

import { beforeEach, describe, expect, it, vi } from 'vitest';

import {
  createDictData,
  getDictData,
  updateDictData,
} from '#/api/system/dict/data';
import { showSuccessMessage } from '#/utils/feedback';

import DictDataForm from './data-form.vue';

interface ModalConfig {
  onConfirm: () => Promise<void>;
  onOpenChange: (isOpen: boolean) => Promise<void>;
}

const state = vi.hoisted(() => ({
  formApi: {
    getValues: vi.fn<() => Promise<SystemDictDataApi.DictData>>(),
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

vi.mock('#/api/system/dict/data', () => ({
  createDictData: vi.fn(),
  getDictData: vi.fn(),
  updateDictData: vi.fn(),
}));

vi.mock('#/locales', () => ({
  $t: (key: string) => key,
}));

vi.mock('#/utils/feedback', () => ({
  showSuccessMessage: vi.fn(),
}));

vi.mock('../data', () => ({
  useDataFormSchema: vi.fn(() => []),
}));

function modalConfig() {
  if (!state.modalConfig) {
    throw new Error('弹窗配置未初始化');
  }
  return state.modalConfig;
}

function mountForm() {
  return mount(DictDataForm);
}

function dictData(
  overrides: Partial<SystemDictDataApi.DictData> = {},
): SystemDictDataApi.DictData {
  return {
    colorType: 'primary',
    createTime: new Date('2026-09-01T00:00:00Z'),
    cssClass: '',
    dictType: 'sex',
    id: 7,
    label: '男',
    remark: '',
    status: 0,
    value: '1',
    ...overrides,
  };
}

describe('dict data form modal', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    state.modalConfig = undefined;
    state.formApi.validate.mockResolvedValue({ valid: true });
    state.formApi.getValues.mockResolvedValue(dictData());
    state.modalApi.getData.mockReturnValue({ id: 7 });
    vi.mocked(getDictData).mockResolvedValue(dictData({ label: '女性' }));
  });

  it('validates before submitting and creates dict data', async () => {
    const wrapper = mountForm();

    await modalConfig().onConfirm();

    expect(createDictData).toHaveBeenCalledWith(dictData());
    expect(updateDictData).not.toHaveBeenCalled();
    expect(state.modalApi.close).toHaveBeenCalledOnce();
    expect(wrapper.emitted('success')).toHaveLength(1);
    expect(showSuccessMessage).toHaveBeenCalledWith(
      'ui.actionMessage.operationSuccess',
    );
  });

  it('presets the dict type for new data and updates on edit', async () => {
    state.modalApi.getData.mockReturnValue({ dictType: 'sex' });
    const wrapper = mountForm();

    await modalConfig().onOpenChange(true);
    expect(getDictData).not.toHaveBeenCalled();
    expect(state.formApi.setValues).toHaveBeenCalledWith({ dictType: 'sex' });

    state.modalApi.getData.mockReturnValue({ id: 7 });
    await modalConfig().onOpenChange(false);
    await modalConfig().onOpenChange(true);
    expect(getDictData).toHaveBeenCalledWith(7);
    expect(state.formApi.setValues).toHaveBeenCalledWith(
      dictData({ label: '女性' }),
    );

    await modalConfig().onConfirm();
    expect(updateDictData).toHaveBeenCalledWith(dictData());
    expect(wrapper.emitted('success')).toHaveLength(1);
  });

  it('skips submission when validation fails', async () => {
    state.formApi.validate.mockResolvedValue({ valid: false });
    mountForm();

    await modalConfig().onConfirm();

    expect(createDictData).not.toHaveBeenCalled();
    expect(state.modalApi.lock).not.toHaveBeenCalled();
  });

  it('unlocks the modal when the update fails', async () => {
    vi.mocked(updateDictData).mockRejectedValue(new Error('request failed'));
    mountForm();

    await modalConfig().onOpenChange(true);
    await expect(modalConfig().onConfirm()).rejects.toThrow('request failed');

    expect(state.modalApi.close).not.toHaveBeenCalled();
    expect(state.modalApi.unlock).toHaveBeenCalledTimes(2);
  });
});

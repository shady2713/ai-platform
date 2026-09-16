import type { SystemDictTypeApi } from '#/api/system/dict/type';

import { mount } from '@vue/test-utils';

import { beforeEach, describe, expect, it, vi } from 'vitest';

import {
  createDictType,
  getDictType,
  updateDictType,
} from '#/api/system/dict/type';
import { showSuccessMessage } from '#/utils/feedback';

import DictTypeForm from './type-form.vue';

interface ModalConfig {
  onConfirm: () => Promise<void>;
  onOpenChange: (isOpen: boolean) => Promise<void>;
}

const state = vi.hoisted(() => ({
  formApi: {
    getValues: vi.fn<() => Promise<SystemDictTypeApi.DictType>>(),
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

vi.mock('#/api/system/dict/type', () => ({
  createDictType: vi.fn(),
  getDictType: vi.fn(),
  updateDictType: vi.fn(),
}));

vi.mock('#/locales', () => ({
  $t: (key: string) => key,
}));

vi.mock('#/utils/feedback', () => ({
  showSuccessMessage: vi.fn(),
}));

vi.mock('../data', () => ({
  useTypeFormSchema: vi.fn(() => []),
}));

function modalConfig() {
  if (!state.modalConfig) {
    throw new Error('弹窗配置未初始化');
  }
  return state.modalConfig;
}

function mountForm() {
  return mount(DictTypeForm);
}

function dictType(
  overrides: Partial<SystemDictTypeApi.DictType> = {},
): SystemDictTypeApi.DictType {
  return {
    createTime: new Date('2026-09-01T00:00:00Z'),
    id: 7,
    name: '用户性别',
    remark: '',
    status: 0,
    type: 'sex',
    ...overrides,
  };
}

describe('dict type form modal', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    state.modalConfig = undefined;
    state.formApi.validate.mockResolvedValue({ valid: true });
    state.formApi.getValues.mockResolvedValue(dictType());
    state.modalApi.getData.mockReturnValue({ id: 7 });
    vi.mocked(getDictType).mockResolvedValue(dictType({ name: '通知类型' }));
  });

  it('validates before submitting and creates a dict type', async () => {
    const wrapper = mountForm();

    await modalConfig().onConfirm();

    expect(createDictType).toHaveBeenCalledWith(dictType());
    expect(updateDictType).not.toHaveBeenCalled();
    expect(state.modalApi.close).toHaveBeenCalledOnce();
    expect(wrapper.emitted('success')).toHaveLength(1);
    expect(showSuccessMessage).toHaveBeenCalledWith(
      'ui.actionMessage.operationSuccess',
    );
  });

  it('loads the dict type for editing and submits via update', async () => {
    const wrapper = mountForm();

    await modalConfig().onOpenChange(true);
    expect(getDictType).toHaveBeenCalledWith(7);
    expect(state.formApi.setValues).toHaveBeenCalledWith(
      dictType({ name: '通知类型' }),
    );

    await modalConfig().onConfirm();
    expect(updateDictType).toHaveBeenCalledWith(dictType());
    expect(createDictType).not.toHaveBeenCalled();
    expect(wrapper.emitted('success')).toHaveLength(1);
  });

  it('skips submission when validation fails', async () => {
    state.formApi.validate.mockResolvedValue({ valid: false });
    mountForm();

    await modalConfig().onConfirm();

    expect(state.formApi.getValues).not.toHaveBeenCalled();
    expect(createDictType).not.toHaveBeenCalled();
  });

  it('resets state on close so the next submission creates', async () => {
    mountForm();

    await modalConfig().onOpenChange(true);
    await modalConfig().onOpenChange(false);

    await modalConfig().onConfirm();
    expect(createDictType).toHaveBeenCalledOnce();
    expect(updateDictType).not.toHaveBeenCalled();
  });
});

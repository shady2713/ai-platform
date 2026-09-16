import type { SystemDeptApi } from '#/api/system/dept';

import { mount } from '@vue/test-utils';

import { beforeEach, describe, expect, it, vi } from 'vitest';

import { createDept, getDept, updateDept } from '#/api/system/dept';
import { showSuccessMessage } from '#/utils/feedback';

import DeptForm from './form.vue';

interface ModalConfig {
  onConfirm: () => Promise<void>;
  onOpenChange: (isOpen: boolean) => Promise<void>;
}

const state = vi.hoisted(() => ({
  formApi: {
    getValues: vi.fn<() => Promise<SystemDeptApi.Dept>>(),
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

vi.mock('#/api/system/dept', () => ({
  createDept: vi.fn(),
  getDept: vi.fn(),
  updateDept: vi.fn(),
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
  return mount(DeptForm);
}

type DepartmentOverrides = Omit<Partial<SystemDeptApi.Dept>, 'leaderUserId'> & {
  leaderUserId?: '' | null | number;
};

function department(overrides: DepartmentOverrides = {}): SystemDeptApi.Dept {
  return {
    createTime: new Date('2026-09-01T00:00:00Z'),
    email: '',
    id: 7,
    name: '研发部',
    parentId: 0,
    phone: '',
    sort: 0,
    status: 0,
    ...overrides,
    leaderUserId: overrides.leaderUserId ?? null,
  } as SystemDeptApi.Dept;
}

describe('system dept form modal', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    state.modalConfig = undefined;
    state.formApi.validate.mockResolvedValue({ valid: true });
    state.formApi.getValues.mockResolvedValue(department());
    state.modalApi.getData.mockReturnValue({ id: 7 });
    vi.mocked(getDept).mockResolvedValue(department({ name: '重命名部门' }));
  });

  it('normalizes an empty leader to null and creates the department', async () => {
    state.formApi.getValues.mockResolvedValue(department({ leaderUserId: '' }));
    const wrapper = mountForm();

    await modalConfig().onConfirm();

    expect(createDept).toHaveBeenCalledWith(department({ leaderUserId: null }));
    expect(state.modalApi.close).toHaveBeenCalledOnce();
    expect(wrapper.emitted('success')).toHaveLength(1);
    expect(showSuccessMessage).toHaveBeenCalledWith(
      'ui.actionMessage.operationSuccess',
    );
  });

  it('keeps a real leader id and updates an existing department', async () => {
    state.formApi.getValues.mockResolvedValue(
      department({ id: 7, leaderUserId: 9 }),
    );
    const wrapper = mountForm();

    await modalConfig().onOpenChange(true);
    expect(getDept).toHaveBeenCalledWith(7);
    expect(state.formApi.setValues).toHaveBeenCalledWith(
      department({ name: '重命名部门' }),
    );

    await modalConfig().onConfirm();
    expect(updateDept).toHaveBeenCalledWith(
      department({ id: 7, leaderUserId: 9 }),
    );
    expect(createDept).not.toHaveBeenCalled();
    expect(wrapper.emitted('success')).toHaveLength(1);
  });

  it('presets the parent id when creating a sub department', async () => {
    state.modalApi.getData.mockReturnValue({ parentId: 3 });
    mountForm();

    await modalConfig().onOpenChange(true);

    expect(getDept).not.toHaveBeenCalled();
    expect(state.formApi.setValues).toHaveBeenCalledWith({ parentId: 3 });
  });

  it('skips submission when validation fails', async () => {
    state.formApi.validate.mockResolvedValue({ valid: false });
    mountForm();

    await modalConfig().onConfirm();

    expect(createDept).not.toHaveBeenCalled();
    expect(state.modalApi.lock).not.toHaveBeenCalled();
  });

  it('unlocks the modal when the update fails', async () => {
    vi.mocked(updateDept).mockRejectedValue(new Error('request failed'));
    mountForm();

    await modalConfig().onOpenChange(true);
    await expect(modalConfig().onConfirm()).rejects.toThrow('request failed');

    expect(state.modalApi.close).not.toHaveBeenCalled();
    expect(state.modalApi.unlock).toHaveBeenCalledTimes(2);
  });
});

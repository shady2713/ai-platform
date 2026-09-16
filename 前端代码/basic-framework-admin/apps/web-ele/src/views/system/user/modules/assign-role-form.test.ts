import type { SystemUserApi } from '#/api/system/user';

import { mount } from '@vue/test-utils';

import { beforeEach, describe, expect, it, vi } from 'vitest';

import { assignUserRole, getUserRoleList } from '#/api/system/permission';
import { showSuccessMessage } from '#/utils/feedback';

import AssignRoleForm from './assign-role-form.vue';

interface AssignRoleValues {
  id: number;
  roleIds: number[];
}

interface ModalConfig {
  onConfirm: () => Promise<void>;
  onOpenChange: (isOpen: boolean) => Promise<void>;
}

const state = vi.hoisted(() => ({
  formApi: {
    getValues: vi.fn<() => Promise<AssignRoleValues>>(),
    setValues: vi.fn(() => Promise.resolve()),
    validate: vi.fn<() => Promise<{ valid: boolean }>>(),
  },
  modalApi: {
    close: vi.fn(() => Promise.resolve()),
    getData: vi.fn(),
    lock: vi.fn(),
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

vi.mock('#/api/system/permission', () => ({
  assignUserRole: vi.fn(),
  getUserRoleList: vi.fn(),
}));

vi.mock('#/locales', () => ({
  $t: (key: string) => key,
}));

vi.mock('#/utils/feedback', () => ({
  showSuccessMessage: vi.fn(),
}));

vi.mock('../data', () => ({
  useAssignRoleFormSchema: vi.fn(() => []),
}));

function modalConfig() {
  if (!state.modalConfig) {
    throw new Error('弹窗配置未初始化');
  }
  return state.modalConfig;
}

function user(overrides: Partial<SystemUserApi.User> = {}): SystemUserApi.User {
  return {
    avatar: '',
    deptId: 1,
    email: '',
    id: 1,
    loginIp: '',
    mobile: '',
    nickname: '管理员',
    postIds: [],
    remark: '',
    sex: 1,
    status: 0,
    username: 'admin',
    ...overrides,
  };
}

function mountForm() {
  return mount(AssignRoleForm);
}

describe('system user assign role form', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    state.modalConfig = undefined;
    state.formApi.validate.mockResolvedValue({ valid: true });
    state.formApi.getValues.mockResolvedValue({ id: 1, roleIds: [2, 3] });
    state.modalApi.getData.mockReturnValue(user());
    vi.mocked(getUserRoleList).mockResolvedValue([2, 3]);
  });

  it('loads the assigned roles and fills the form on open', async () => {
    mountForm();

    await modalConfig().onOpenChange(true);

    expect(getUserRoleList).toHaveBeenCalledWith(1);
    expect(state.formApi.setValues).toHaveBeenCalledWith({
      ...user(),
      roleIds: [2, 3],
    });
    expect(state.modalApi.lock).toHaveBeenCalledOnce();
    expect(state.modalApi.unlock).toHaveBeenCalledOnce();
  });

  it('skips loading when closing or opened without a user id', async () => {
    mountForm();

    await modalConfig().onOpenChange(false);
    state.modalApi.getData.mockReturnValue(undefined);
    await modalConfig().onOpenChange(true);
    state.modalApi.getData.mockReturnValue({ username: 'admin' });
    await modalConfig().onOpenChange(true);

    expect(getUserRoleList).not.toHaveBeenCalled();
    expect(state.modalApi.lock).not.toHaveBeenCalled();
  });

  it('assigns the selected roles and emits success', async () => {
    const wrapper = mountForm();

    await modalConfig().onConfirm();

    expect(assignUserRole).toHaveBeenCalledWith({
      roleIds: [2, 3],
      userId: 1,
    });
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

    expect(assignUserRole).not.toHaveBeenCalled();
    expect(state.modalApi.lock).not.toHaveBeenCalled();
  });

  it('keeps the modal open and unlocks it when the request fails', async () => {
    vi.mocked(assignUserRole).mockRejectedValue(new Error('request failed'));
    mountForm();

    await expect(modalConfig().onConfirm()).rejects.toThrow('request failed');

    expect(state.modalApi.close).not.toHaveBeenCalled();
    expect(showSuccessMessage).not.toHaveBeenCalled();
    expect(state.modalApi.unlock).toHaveBeenCalledOnce();
  });
});

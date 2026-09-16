import type { SystemUserApi } from '#/api/system/user';

import { mount } from '@vue/test-utils';

import { beforeEach, describe, expect, it, vi } from 'vitest';

import { createUser, getUser, updateUser } from '#/api/system/user';
import { showSuccessMessage } from '#/utils/feedback';

import UserForm from './form.vue';

interface ModalConfig {
  onConfirm: () => Promise<void>;
  onOpenChange: (isOpen: boolean) => Promise<void>;
}

const state = vi.hoisted(() => ({
  formApi: {
    getValues: vi.fn<() => Promise<SystemUserApi.User>>(),
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

vi.mock('#/api/system/user', () => ({
  createUser: vi.fn(),
  getUser: vi.fn(),
  updateUser: vi.fn(),
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

function user(overrides: Partial<SystemUserApi.User> = {}): SystemUserApi.User {
  return {
    avatar: '',
    deptId: 1,
    email: 'admin@example.com',
    id: 1,
    loginIp: '',
    mobile: '13800138000',
    nickname: '管理员',
    postIds: [],
    remark: '',
    sex: 1,
    status: 0,
    username: 'admin',
    ...overrides,
  };
}

function withoutStatus(value: SystemUserApi.User) {
  const { status, ...rest } = value;
  expect(status).toBeDefined();
  return rest;
}

function mountForm() {
  return mount(UserForm);
}

describe('system user form modal', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    state.modalConfig = undefined;
    state.formApi.validate.mockResolvedValue({ valid: true });
    state.formApi.getValues.mockResolvedValue(user());
    state.modalApi.getData.mockReturnValue(undefined);
    vi.mocked(getUser).mockResolvedValue(user({ nickname: '新昵称' }));
  });

  it('creates the user when no id is present', async () => {
    const wrapper = mountForm();

    await modalConfig().onOpenChange(true);
    expect(state.modalApi.setState).toHaveBeenCalledWith({
      title: 'ui.actionTitle.create',
    });
    expect(getUser).not.toHaveBeenCalled();

    await modalConfig().onConfirm();

    expect(createUser).toHaveBeenCalledWith(withoutStatus(user()));
    expect(vi.mocked(createUser).mock.calls[0]?.[0]).not.toHaveProperty(
      'status',
    );
    expect(updateUser).not.toHaveBeenCalled();
    expect(state.modalApi.close).toHaveBeenCalledOnce();
    expect(wrapper.emitted('success')).toHaveLength(1);
    expect(showSuccessMessage).toHaveBeenCalledWith(
      'ui.actionMessage.operationSuccess',
    );
    expect(state.modalApi.unlock).toHaveBeenCalledOnce();
  });

  it('loads the user detail and updates it in edit mode', async () => {
    state.modalApi.getData.mockReturnValue({ id: 1 });
    state.formApi.getValues.mockResolvedValue(user({ nickname: '改后昵称' }));
    const wrapper = mountForm();

    await modalConfig().onOpenChange(true);

    expect(state.modalApi.setState).toHaveBeenCalledWith({
      title: 'ui.actionTitle.edit',
    });
    expect(getUser).toHaveBeenCalledWith(1);
    expect(state.formApi.setValues).toHaveBeenCalledWith(
      user({ nickname: '新昵称' }),
    );
    expect(state.modalApi.lock).toHaveBeenCalledOnce();
    expect(state.modalApi.unlock).toHaveBeenCalledOnce();

    await modalConfig().onConfirm();

    expect(updateUser).toHaveBeenCalledWith(
      withoutStatus(user({ nickname: '改后昵称' })),
    );
    expect(vi.mocked(updateUser).mock.calls[0]?.[0]).not.toHaveProperty(
      'status',
    );
    expect(createUser).not.toHaveBeenCalled();
    expect(wrapper.emitted('success')).toHaveLength(1);
  });

  it('falls back to create mode after the modal closes', async () => {
    state.modalApi.getData.mockReturnValue({ id: 1 });
    mountForm();

    await modalConfig().onOpenChange(true);
    await modalConfig().onOpenChange(false);
    await modalConfig().onConfirm();

    expect(createUser).toHaveBeenCalledWith(withoutStatus(user()));
    expect(updateUser).not.toHaveBeenCalled();
  });

  it('skips submission when validation fails', async () => {
    state.formApi.validate.mockResolvedValue({ valid: false });
    mountForm();

    await modalConfig().onConfirm();

    expect(createUser).not.toHaveBeenCalled();
    expect(updateUser).not.toHaveBeenCalled();
    expect(state.modalApi.lock).not.toHaveBeenCalled();
  });

  it('keeps the modal open and unlocks it when the request fails', async () => {
    vi.mocked(createUser).mockRejectedValue(new Error('request failed'));
    mountForm();

    await expect(modalConfig().onConfirm()).rejects.toThrow('request failed');

    expect(state.modalApi.close).not.toHaveBeenCalled();
    expect(showSuccessMessage).not.toHaveBeenCalled();
    expect(state.modalApi.unlock).toHaveBeenCalledOnce();
  });

  it('unlocks the modal when loading the user detail fails', async () => {
    state.modalApi.getData.mockReturnValue({ id: 1 });
    vi.mocked(getUser).mockRejectedValue(new Error('load failed'));
    mountForm();

    await expect(modalConfig().onOpenChange(true)).rejects.toThrow(
      'load failed',
    );

    expect(state.modalApi.unlock).toHaveBeenCalledOnce();
  });
});

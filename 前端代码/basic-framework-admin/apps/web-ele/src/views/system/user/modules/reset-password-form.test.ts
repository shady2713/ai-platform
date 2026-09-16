import { mount } from '@vue/test-utils';

import { beforeEach, describe, expect, it, vi } from 'vitest';

import { resetUserPassword } from '#/api/system/user';
import { showSuccessMessage } from '#/utils/feedback';

import ResetPasswordForm from './reset-password-form.vue';

interface ResetPasswordValues {
  id: number;
  newPassword: string;
}

interface ModalConfig {
  onConfirm: () => Promise<void>;
  onOpenChange: (isOpen: boolean) => Promise<void>;
}

const state = vi.hoisted(() => ({
  formApi: {
    getValues: vi.fn<() => Promise<ResetPasswordValues>>(),
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

vi.mock('#/api/system/user', () => ({
  resetUserPassword: vi.fn(),
}));

vi.mock('#/locales', () => ({
  $t: (key: string) => key,
}));

vi.mock('#/utils/feedback', () => ({
  showSuccessMessage: vi.fn(),
}));

vi.mock('../data', () => ({
  useResetPasswordFormSchema: vi.fn(() => []),
}));

function modalConfig() {
  if (!state.modalConfig) {
    throw new Error('弹窗配置未初始化');
  }
  return state.modalConfig;
}

function mountForm() {
  return mount(ResetPasswordForm);
}

describe('system user reset password form', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    state.modalConfig = undefined;
    state.formApi.validate.mockResolvedValue({ valid: true });
    state.formApi.getValues.mockResolvedValue({
      id: 1,
      newPassword: 'New@123456',
    });
    state.modalApi.getData.mockReturnValue({ id: 1, username: 'admin' });
  });

  it('fills the form with the target user on open', async () => {
    mountForm();

    await modalConfig().onOpenChange(true);

    expect(state.formApi.setValues).toHaveBeenCalledWith({
      id: 1,
      username: 'admin',
    });
  });

  it('skips filling when closing or opened without a user id', async () => {
    mountForm();

    await modalConfig().onOpenChange(false);
    state.modalApi.getData.mockReturnValue(undefined);
    await modalConfig().onOpenChange(true);
    state.modalApi.getData.mockReturnValue({ username: 'admin' });
    await modalConfig().onOpenChange(true);

    expect(state.formApi.setValues).not.toHaveBeenCalled();
  });

  it('resets the password and emits success', async () => {
    const wrapper = mountForm();

    await modalConfig().onConfirm();

    expect(resetUserPassword).toHaveBeenCalledWith(1, 'New@123456');
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

    expect(resetUserPassword).not.toHaveBeenCalled();
    expect(state.modalApi.lock).not.toHaveBeenCalled();
  });

  it('keeps the modal open and unlocks it when the request fails', async () => {
    vi.mocked(resetUserPassword).mockRejectedValue(new Error('request failed'));
    mountForm();

    await expect(modalConfig().onConfirm()).rejects.toThrow('request failed');

    expect(state.modalApi.close).not.toHaveBeenCalled();
    expect(showSuccessMessage).not.toHaveBeenCalled();
    expect(state.modalApi.unlock).toHaveBeenCalledOnce();
  });
});

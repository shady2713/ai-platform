import { mount } from '@vue/test-utils';

import { beforeEach, describe, expect, it, vi } from 'vitest';

import { updateUserPassword } from '#/api/system/user/profile';
import { showSuccessMessage } from '#/utils/feedback';

import ResetPassword from './reset-pwd.vue';

type FormOptions = {
  handleSubmit: (values: Record<string, unknown>) => Promise<void>;
};

const testState = vi.hoisted(() => ({
  formApi: { setLoading: vi.fn() },
  formOptions: null as FormOptions | null,
  logError: vi.fn(),
}));

vi.mock('@vben/locales', () => ({
  $t: (key: string) => key,
}));

vi.mock('@vben/utils', () => ({
  logError: testState.logError,
}));

vi.mock('#/adapter/form', async () => {
  const { defineComponent } = await import('vue');
  return {
    buildLoginPasswordSchema: vi.fn(),
    buildRequiredPasswordSchema: vi.fn(() => ({ refine: vi.fn() })),
    useVbenForm: (options: FormOptions) => {
      testState.formOptions = options;
      return [defineComponent({ template: '<form />' }), testState.formApi];
    },
  };
});

vi.mock('#/api/system/user/profile', () => ({
  updateUserPassword: vi.fn(),
}));

vi.mock('#/utils/feedback', () => ({
  showSuccessMessage: vi.fn(),
}));

function submit() {
  if (!testState.formOptions) {
    throw new Error('表单提交回调未初始化');
  }
  return testState.formOptions.handleSubmit;
}

describe('profile password reset form', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    testState.formOptions = null;
  });

  it('只提交后端需要的旧密码和新密码', async () => {
    mount(ResetPassword);

    await submit()({
      confirmPassword: 'NewPassword1',
      newPassword: 'NewPassword1',
      oldPassword: 'OldPassword1',
    });

    expect(updateUserPassword).toHaveBeenCalledWith({
      newPassword: 'NewPassword1',
      oldPassword: 'OldPassword1',
    });
    expect(showSuccessMessage).toHaveBeenCalledWith(
      'ui.actionMessage.operationSuccess',
    );
    expect(testState.formApi.setLoading).toHaveBeenNthCalledWith(1, true);
    expect(testState.formApi.setLoading).toHaveBeenLastCalledWith(false);
  });

  it('拒绝非字符串密码字段并保留安全错误路径', async () => {
    mount(ResetPassword);

    await submit()({
      confirmPassword: 'NewPassword1',
      newPassword: 123,
      oldPassword: 'OldPassword1',
    });

    expect(updateUserPassword).not.toHaveBeenCalled();
    expect(showSuccessMessage).not.toHaveBeenCalled();
    expect(testState.logError).toHaveBeenCalledWith(
      'profile:reset-password:submit',
      expect.objectContaining({ message: '密码表单字段类型无效' }),
    );
    expect(testState.formApi.setLoading).toHaveBeenLastCalledWith(false);
  });
});

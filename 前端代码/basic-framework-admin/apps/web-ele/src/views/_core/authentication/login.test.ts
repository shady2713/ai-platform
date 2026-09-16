import type { VueWrapper } from '@vue/test-utils';

import type { AuthApi } from '#/api/core/auth';

import { flushPromises, mount } from '@vue/test-utils';

import { beforeEach, describe, expect, it, vi } from 'vitest';

import { changeExpiredPasswordApi } from '#/api/core/auth';
import { showRequestError, showSuccessMessage } from '#/utils/feedback';

import Login from './login.vue';

const captcha = vi.hoisted(() => ({
  enabled: false,
  show: vi.fn(),
  setValues: vi.fn(),
}));

const authStore = vi.hoisted(() => ({
  authLogin: vi.fn(),
  loginLoading: false,
}));

vi.mock('#/store', () => ({ useAuthStore: () => authStore }));

vi.mock('@vben/hooks', () => ({ isCaptchaEnable: () => captcha.enabled }));

vi.mock('@vben/locales', () => ({ $t: (key: string) => key }));

vi.mock('@vben/utils', () => ({ logError: vi.fn() }));

vi.mock('#/adapter/form', () => ({
  buildLoginPasswordSchema: vi.fn(() => []),
  buildRequiredUsernameSchema: vi.fn(() => []),
  isPasswordValue: (value: string) => [...value].length >= 15,
}));

vi.mock('#/api/core/auth', () => ({
  AUTH_PASSWORD_EXPIRED_CODE: 1_002_000_019,
  changeExpiredPasswordApi: vi.fn(),
  checkCaptcha: vi.fn(),
  getCaptcha: vi.fn(),
}));

vi.mock('#/utils/feedback', () => ({
  showRequestError: vi.fn(),
  showSuccessMessage: vi.fn(),
}));

vi.mock('@vben/common-ui', async () => {
  const { defineComponent } = await import('vue');
  return {
    AuthenticationLogin: defineComponent({
      name: 'AuthenticationLogin',
      emits: ['submit'],
      setup(_, { expose }) {
        expose({
          getFormApi: () => ({
            getValues: async () => ({
              username: 'admin',
              password: 'password',
            }),
            setValues: captcha.setValues,
          }),
        });
        return {};
      },
      template: `
        <button
          data-test="credential-submit"
          @click="$emit('submit', { username: 'admin', password: 'password' })"
        >
          登录
        </button>
      `,
    }),
    Verification: defineComponent({
      name: 'VerificationStub',
      emits: ['on-success'],
      setup(_, { expose }) {
        expose({ show: captcha.show });
        return {};
      },
      template: '<div />',
    }),
  };
});

vi.mock('element-plus', async () => {
  const { defineComponent } = await import('vue');
  return {
    ElAlert: defineComponent({
      name: 'ElAlert',
      template: '<aside><slot /></aside>',
    }),
    ElButton: defineComponent({
      name: 'ElButton',
      props: { disabled: Boolean, loading: Boolean },
      emits: ['click'],
      template: `
        <button :disabled="disabled" @click="$emit('click')"><slot /></button>
      `,
    }),
    ElDialog: defineComponent({
      name: 'ElDialog',
      props: { modelValue: Boolean },
      emits: ['update:modelValue'],
      template:
        '<section v-if="modelValue" data-test="password-dialog"><slot /></section>',
    }),
    ElInput: defineComponent({
      name: 'ElInput',
      inheritAttrs: false,
      props: { modelValue: { default: '', type: String } },
      emits: ['update:modelValue'],
      template: `
        <input
          v-bind="$attrs"
          :value="modelValue"
          @input="$emit('update:modelValue', $event.target.value)"
        />
      `,
    }),
  };
});

interface LoginComponent {
  handleVerifySuccess: (value: {
    captchaVerification: string;
  }) => Promise<void>;
  submitCredentials: (values: AuthApi.LoginParams) => Promise<void>;
  submitPasswordChange: () => Promise<void>;
}

function mountLogin() {
  return mount(Login);
}

function loginComponent(wrapper: VueWrapper) {
  return wrapper.vm as unknown as LoginComponent;
}

describe('login forced password change flow', () => {
  const passwordExpiredError = {
    response: {
      data: { code: 1_002_000_019, msg: '密码已过期，请修改密码后重新登录' },
      status: 422,
    },
  };

  beforeEach(() => {
    vi.clearAllMocks();
    authStore.authLogin.mockReset();
    captcha.enabled = false;
  });

  it('普通登录提交账号密码，不显示改密弹窗', async () => {
    authStore.authLogin.mockResolvedValue({});
    const wrapper = mountLogin();
    await wrapper.get('[data-test="credential-submit"]').trigger('click');
    await flushPromises();
    expect(authStore.authLogin).toHaveBeenCalledWith('username', {
      username: 'admin',
      password: 'password',
    });
    expect(wrapper.find('[data-test="password-dialog"]').exists()).toBe(false);
  });

  it('普通认证失败内联提示并向调用者传播', async () => {
    const error = new Error('network unavailable');
    authStore.authLogin.mockRejectedValue(error);
    await expect(
      loginComponent(mountLogin()).submitCredentials({
        username: 'admin',
        password: 'password',
      }),
    ).rejects.toBe(error);
    expect(showRequestError).toHaveBeenCalledWith(
      error,
      'ui.fallback.http.internalServerError',
    );
  });

  it.each([
    ['Network Error', 'ui.fallback.http.networkError'],
    ['timeout of 10000ms exceeded', 'ui.fallback.http.requestTimeout'],
  ])('断网或超时按原因映射兜底文案: %s', async (message, fallback) => {
    const error = new Error(message);
    authStore.authLogin.mockRejectedValue(error);
    await expect(
      loginComponent(mountLogin()).submitCredentials({
        username: 'admin',
        password: 'password',
      }),
    ).rejects.toBe(error);
    expect(showRequestError).toHaveBeenCalledWith(error, fallback);
  });

  it('启用图形验证码时先验证，再携带票据登录', async () => {
    captcha.enabled = true;
    authStore.authLogin.mockResolvedValue({});
    const wrapper = mountLogin();
    await wrapper.get('[data-test="credential-submit"]').trigger('click');
    expect(captcha.show).toHaveBeenCalledOnce();
    expect(authStore.authLogin).not.toHaveBeenCalled();
    await loginComponent(wrapper).handleVerifySuccess({
      captchaVerification: 'verified-ticket',
    });
    expect(authStore.authLogin).toHaveBeenCalledWith('username', {
      username: 'admin',
      password: 'password',
      captchaVerification: 'verified-ticket',
    });
  });

  it('登录被强制改密门禁拦截时切换到改密表单并复用已输入的用户名与原密码', async () => {
    authStore.authLogin.mockRejectedValue(passwordExpiredError);
    const wrapper = mountLogin();

    await loginComponent(wrapper).submitCredentials({
      password: 'password',
      username: 'admin',
    });

    const oldPasswordInput = wrapper.find(
      'input[aria-label="authentication.oldPasswordTip"]',
    );
    expect(oldPasswordInput.exists()).toBe(true);
    expect((oldPasswordInput.element as HTMLInputElement).value).toBe(
      'password',
    );
    // 过期密码分支由改密弹窗接管，不再弹出内联错误提示
    expect(showRequestError).not.toHaveBeenCalled();
  });

  it('新密码未满足策略、与原密码相同或确认不一致时不提交', async () => {
    authStore.authLogin.mockRejectedValue(passwordExpiredError);
    const wrapper = mountLogin();
    const component = loginComponent(wrapper);
    await component.submitCredentials({
      password: 'password',
      username: 'admin',
    });

    await wrapper
      .find('input[aria-label="authentication.newPasswordPolicyTip"]')
      .setValue('short');
    await wrapper
      .find('input[aria-label="authentication.confirmNewPasswordTip"]')
      .setValue('short');
    await component.submitPasswordChange();
    expect(changeExpiredPasswordApi).not.toHaveBeenCalled();

    await wrapper
      .find('input[aria-label="authentication.newPasswordPolicyTip"]')
      .setValue('correct horse battery staple');
    await wrapper
      .find('input[aria-label="authentication.confirmNewPasswordTip"]')
      .setValue('different horse battery staple');
    await component.submitPasswordChange();
    expect(changeExpiredPasswordApi).not.toHaveBeenCalled();

    await wrapper
      .find('input[aria-label="authentication.oldPasswordTip"]')
      .setValue('correct horse battery staple');
    await wrapper
      .find('input[aria-label="authentication.confirmNewPasswordTip"]')
      .setValue('correct horse battery staple');
    await component.submitPasswordChange();
    expect(changeExpiredPasswordApi).not.toHaveBeenCalled();
  });

  it('改密成功后关闭表单并使用新密码重新登录', async () => {
    authStore.authLogin.mockRejectedValueOnce(passwordExpiredError);
    authStore.authLogin.mockResolvedValueOnce({
      loginResult: { userId: 1 },
      userInfo: null,
    });
    vi.mocked(changeExpiredPasswordApi).mockResolvedValue(true);
    const wrapper = mountLogin();
    const component = loginComponent(wrapper);
    await component.submitCredentials({
      password: 'password',
      username: 'admin',
    });

    await wrapper
      .find('input[aria-label="authentication.newPasswordPolicyTip"]')
      .setValue('correct horse battery staple');
    await wrapper
      .find('input[aria-label="authentication.confirmNewPasswordTip"]')
      .setValue('correct horse battery staple');
    await component.submitPasswordChange();
    await flushPromises();

    expect(changeExpiredPasswordApi).toHaveBeenCalledWith({
      username: 'admin',
      oldPassword: 'password',
      newPassword: 'correct horse battery staple',
    });
    expect(
      wrapper
        .find('input[aria-label="authentication.oldPasswordTip"]')
        .exists(),
    ).toBe(false);
    expect(showSuccessMessage).toHaveBeenCalledWith(
      'authentication.expiredPasswordSuccessAutoLogin',
    );
    expect(authStore.authLogin).toHaveBeenLastCalledWith('username', {
      username: 'admin',
      password: 'correct horse battery staple',
    });
  });

  it('验证码启用时改密成功仅回填新密码并提示手动登录，不自动重登', async () => {
    captcha.enabled = true;
    authStore.authLogin.mockRejectedValueOnce(passwordExpiredError);
    vi.mocked(changeExpiredPasswordApi).mockResolvedValue(true);
    const wrapper = mountLogin();
    const component = loginComponent(wrapper);
    await component.submitCredentials({
      password: 'password',
      username: 'admin',
    });

    await wrapper
      .find('input[aria-label="authentication.newPasswordPolicyTip"]')
      .setValue('correct horse battery staple');
    await wrapper
      .find('input[aria-label="authentication.confirmNewPasswordTip"]')
      .setValue('correct horse battery staple');
    await component.submitPasswordChange();
    await flushPromises();

    expect(changeExpiredPasswordApi).toHaveBeenCalledWith({
      username: 'admin',
      oldPassword: 'password',
      newPassword: 'correct horse battery staple',
    });
    expect(showSuccessMessage).toHaveBeenCalledWith(
      'authentication.expiredPasswordSuccess',
    );
    // 验证码票据已消耗，无法自动重登；新密码回填到登录表单由用户再次提交
    expect(authStore.authLogin).toHaveBeenCalledTimes(1);
    expect(captcha.setValues).toHaveBeenCalledWith({
      password: 'correct horse battery staple',
    });
  });

  it('改密接口失败时保持表单打开以便重试', async () => {
    authStore.authLogin.mockRejectedValueOnce(passwordExpiredError);
    vi.mocked(changeExpiredPasswordApi).mockRejectedValue(
      new Error('账号密码不正确'),
    );
    const wrapper = mountLogin();
    const component = loginComponent(wrapper);
    await component.submitCredentials({
      password: 'password',
      username: 'admin',
    });

    await wrapper
      .find('input[aria-label="authentication.newPasswordPolicyTip"]')
      .setValue('correct horse battery staple');
    await wrapper
      .find('input[aria-label="authentication.confirmNewPasswordTip"]')
      .setValue('correct horse battery staple');
    await component.submitPasswordChange();
    await flushPromises();

    expect(changeExpiredPasswordApi).toHaveBeenCalled();
    expect(
      wrapper
        .find('input[aria-label="authentication.newPasswordPolicyTip"]')
        .exists(),
    ).toBe(true);
  });
});

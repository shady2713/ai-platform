import { flushPromises, mount } from '@vue/test-utils';

import { logError } from '@vben/utils';

import { beforeEach, describe, expect, it, vi } from 'vitest';

import { smsResetPassword } from '#/api/core/auth';

import ForgetPassword from './forget-password.vue';

const router = vi.hoisted(() => ({ push: vi.fn() }));

vi.mock('vue-router', () => ({ useRouter: () => router }));

vi.mock('@vben/locales', () => ({ $t: (key: string) => key }));

vi.mock('@vben/utils', () => ({ logError: vi.fn() }));

vi.mock('#/adapter/form', () => ({
  buildRequiredMobileSchema: vi.fn(() => ({})),
  buildRequiredPasswordSchema: vi.fn(() => ({ refine: vi.fn() })),
}));

vi.mock('#/api/core/auth', () => ({
  sendSmsCode: vi.fn(),
  smsResetPassword: vi.fn(),
}));

vi.mock('#/utils/feedback', () => ({ showSuccessMessage: vi.fn() }));

vi.mock('@vben/common-ui', async () => {
  const { defineComponent } = await import('vue');
  return {
    AuthenticationForgetPassword: defineComponent({
      name: 'AuthenticationForgetPassword',
      props: {
        formSchema: { default: () => [], type: Array },
        loading: Boolean,
      },
      emits: ['submit'],
      template: `
        <div>
          <button
            data-test="valid-submit"
            @click="$emit('submit', { mobile: '13800138000', code: '1234', password: 'StrongPassword1' })"
          >
            valid
          </button>
          <button
            data-test="invalid-submit"
            @click="$emit('submit', { mobile: 13800138000, code: '1234', password: 'StrongPassword1' })"
          >
            invalid
          </button>
        </div>
      `,
    }),
    z: {
      string: () => ({ length: () => ({}) }),
    },
  };
});

describe('forget password', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('submits validated string fields', async () => {
    const wrapper = mount(ForgetPassword);

    await wrapper.get('[data-test="valid-submit"]').trigger('click');
    await flushPromises();

    expect(smsResetPassword).toHaveBeenCalledWith({
      code: '1234',
      mobile: '13800138000',
      password: 'StrongPassword1',
    });
    expect(router.push).toHaveBeenCalledWith('/');
  });

  it('rejects non-string fields before calling the API', async () => {
    const wrapper = mount(ForgetPassword);

    await wrapper.get('[data-test="invalid-submit"]').trigger('click');
    await flushPromises();

    expect(smsResetPassword).not.toHaveBeenCalled();
    expect(logError).toHaveBeenCalledWith(
      'auth:forget-password:submit',
      expect.any(TypeError),
    );
  });
});

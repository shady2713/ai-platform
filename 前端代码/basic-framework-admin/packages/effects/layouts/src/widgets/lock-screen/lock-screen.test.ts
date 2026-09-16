import type { VueWrapper } from '@vue/test-utils';

import { mount } from '@vue/test-utils';
import { nextTick } from 'vue';

import { beforeEach, describe, expect, it, vi } from 'vitest';

import LockScreen from './lock-screen.vue';

const state = vi.hoisted(() => ({
  focus: vi.fn(),
  form: {
    setFieldError: vi.fn(),
    values: { password: 'candidate-password' },
  },
  unlockScreen: vi.fn(),
  useScrollLock: vi.fn(),
  validate: vi.fn(),
  verifyLockScreenPassword: vi.fn(),
}));

vi.mock('@vben/stores', () => ({
  useAccessStore: () => ({
    unlockScreen: state.unlockScreen,
    verifyLockScreenPassword: state.verifyLockScreenPassword,
  }),
}));

vi.mock('@vben-core/composables', () => ({
  useScrollLock: state.useScrollLock,
}));

vi.mock('@vben-core/form-ui', async () => {
  const { defineComponent } = await import('vue');
  interface RuleChain {
    max: () => RuleChain;
    min: () => RuleChain;
  }
  const rules: RuleChain = {
    max: vi.fn(() => rules),
    min: vi.fn(() => rules),
  };
  return {
    useVbenForm: vi.fn(() => [
      defineComponent({ name: 'FormStub', template: '<form />' }),
      {
        form: state.form,
        getFieldComponentRef: vi.fn(() => ({
          $el: { querySelector: () => ({ focus: state.focus }) },
        })),
        validate: state.validate,
      },
    ]),
    z: { string: () => rules },
  };
});

vi.mock('@vben/icons', async () => {
  const { defineComponent } = await import('vue');
  return {
    LockKeyhole: defineComponent({ name: 'LockKeyhole', template: '<i />' }),
  };
});

vi.mock('@vben/locales', () => ({
  $t: (key: string) => key,
  useI18n: () => ({ locale: { value: 'zh-CN' } }),
}));

vi.mock('@vben-core/shadcn-ui', async () => {
  const { defineComponent } = await import('vue');
  return {
    VbenAvatar: defineComponent({ name: 'VbenAvatar', template: '<div />' }),
    VbenButton: defineComponent({
      name: 'VbenButton',
      emits: ['click'],
      template: '<button @click="$emit(\'click\')"><slot /></button>',
    }),
  };
});

vi.mock('@vueuse/core', () => ({
  useDateFormat: () => ({ value: 'fixed-time' }),
  useNow: () => ({ value: new Date('2026-09-03T00:00:00Z') }),
}));

interface LockScreenComponent {
  handleSubmit: () => Promise<void>;
  toggleUnlockForm: () => void;
}

function component(wrapper: VueWrapper) {
  return wrapper.vm as unknown as LockScreenComponent;
}

describe('lock screen', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    state.form.values.password = 'candidate-password';
    state.validate.mockResolvedValue({ valid: true });
    state.verifyLockScreenPassword.mockResolvedValue(false);
    vi.stubGlobal('requestAnimationFrame', (callback: FrameRequestCallback) => {
      callback(0);
      return 1;
    });
  });

  it('does not verify an invalid form', async () => {
    state.validate.mockResolvedValue({ valid: false });
    const wrapper = mount(LockScreen);

    await component(wrapper).handleSubmit();

    expect(state.verifyLockScreenPassword).not.toHaveBeenCalled();
    expect(state.unlockScreen).not.toHaveBeenCalled();
    expect(state.useScrollLock).toHaveBeenCalledOnce();
  });

  it('keeps the screen locked and displays a field error for a wrong password', async () => {
    const wrapper = mount(LockScreen);

    await component(wrapper).handleSubmit();

    expect(state.verifyLockScreenPassword).toHaveBeenCalledWith(
      'candidate-password',
    );
    expect(state.form.setFieldError).toHaveBeenCalledWith(
      'password',
      'authentication.passwordErrorTip',
    );
    expect(state.unlockScreen).not.toHaveBeenCalled();
  });

  it('unlocks only after the derived credential matches', async () => {
    state.verifyLockScreenPassword.mockResolvedValue(true);
    const wrapper = mount(LockScreen);

    await component(wrapper).handleSubmit();

    expect(state.unlockScreen).toHaveBeenCalledOnce();
    expect(state.form.setFieldError).not.toHaveBeenCalled();
  });

  it('opens and focuses the password form, then emits the login fallback', async () => {
    const wrapper = mount(LockScreen);

    component(wrapper).toggleUnlockForm();
    await nextTick();
    expect(state.focus).toHaveBeenCalledOnce();
    const loginButton = wrapper
      .findAll('button')
      .find((button) =>
        button.text().includes('ui.widgets.lockScreen.backToLogin'),
      );
    expect(loginButton).toBeDefined();
    await loginButton?.trigger('click');
    expect(wrapper.emitted('toLogin')).toHaveLength(1);

    component(wrapper).toggleUnlockForm();
    expect(state.focus).toHaveBeenCalledOnce();
  });
});

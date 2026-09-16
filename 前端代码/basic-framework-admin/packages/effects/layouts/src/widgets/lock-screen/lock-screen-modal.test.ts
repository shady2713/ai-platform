import { flushPromises, mount } from '@vue/test-utils';

import { beforeEach, describe, expect, it, vi } from 'vitest';

import LockScreenModal from './lock-screen-modal.vue';

interface ModalOptions {
  onConfirm: () => void;
  onOpenChange: (isOpen: boolean) => void;
  onOpened: () => void;
}

const state = vi.hoisted(() => ({
  focus: vi.fn(),
  getValues: vi.fn(),
  modalOptions: undefined as ModalOptions | undefined,
  resetForm: vi.fn(),
  validate: vi.fn(),
}));

vi.mock('@vben/locales', () => ({
  $t: (key: string) => key,
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
        getFieldComponentRef: vi.fn(() => ({
          $el: { querySelector: () => ({ focus: state.focus }) },
        })),
        getValues: state.getValues,
        resetForm: state.resetForm,
        validate: state.validate,
      },
    ]),
    z: { string: () => rules },
  };
});

vi.mock('@vben-core/popup-ui', async () => {
  const { defineComponent } = await import('vue');
  return {
    useVbenModal: vi.fn((options: ModalOptions) => {
      state.modalOptions = options;
      return [
        defineComponent({
          name: 'ModalStub',
          template: '<section><slot /></section>',
        }),
      ];
    }),
  };
});

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

describe('lock screen modal', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    state.getValues.mockResolvedValue({
      lockScreenPassword: 'candidate-password',
    });
    state.validate.mockResolvedValue({ valid: true });
    vi.stubGlobal('requestAnimationFrame', (callback: FrameRequestCallback) => {
      callback(0);
      return 1;
    });
  });

  it('does not read or emit a password when validation fails', async () => {
    state.validate.mockResolvedValue({ valid: false });
    const wrapper = mount(LockScreenModal);

    state.modalOptions?.onConfirm();
    await flushPromises();

    expect(state.getValues).not.toHaveBeenCalled();
    expect(wrapper.emitted('submit')).toBeUndefined();
  });

  it('emits only a validated password', async () => {
    const wrapper = mount(LockScreenModal);

    state.modalOptions?.onConfirm();
    await flushPromises();

    expect(wrapper.emitted('submit')).toEqual([['candidate-password']]);
  });

  it('resets on open and focuses the password field after opening', () => {
    mount(LockScreenModal);

    state.modalOptions?.onOpenChange(false);
    expect(state.resetForm).not.toHaveBeenCalled();
    state.modalOptions?.onOpenChange(true);
    expect(state.resetForm).toHaveBeenCalledOnce();
    state.modalOptions?.onOpened();
    expect(state.focus).toHaveBeenCalledOnce();
  });
});

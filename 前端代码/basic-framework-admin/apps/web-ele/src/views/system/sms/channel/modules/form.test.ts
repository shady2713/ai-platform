import type { SystemSmsChannelApi } from '#/api/system/sms/channel';

import { mount } from '@vue/test-utils';

import { beforeEach, describe, expect, it, vi } from 'vitest';

import {
  createSmsChannel,
  getSmsChannel,
  updateSmsChannel,
} from '#/api/system/sms/channel';
import { showSuccessMessage } from '#/utils/feedback';

import SmsChannelForm from './form.vue';

interface ModalConfig {
  onConfirm: () => Promise<void>;
  onOpenChange: (isOpen: boolean) => Promise<void>;
}

const state = vi.hoisted(() => ({
  formApi: {
    getValues: vi.fn<() => Promise<SystemSmsChannelApi.Channel>>(),
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

vi.mock('#/api/system/sms/channel', () => ({
  createSmsChannel: vi.fn(),
  getSmsChannel: vi.fn(),
  updateSmsChannel: vi.fn(),
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
  return mount(SmsChannelForm);
}

function channel(
  overrides: Partial<SystemSmsChannelApi.Channel> = {},
): SystemSmsChannelApi.Channel {
  return {
    callbackUrl: '',
    code: 'aliyun',
    id: 7,
    remark: '',
    signature: 'Basic Framework',
    status: 0,
    ...overrides,
  };
}

describe('system sms channel form modal', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    state.modalConfig = undefined;
    state.formApi.validate.mockResolvedValue({ valid: true });
    state.formApi.getValues.mockResolvedValue(channel());
    state.modalApi.getData.mockReturnValue({ id: 7 });
    vi.mocked(getSmsChannel).mockResolvedValue(
      channel({ signature: 'Framework' }),
    );
  });

  it('validates before submitting and creates a channel', async () => {
    const wrapper = mountForm();

    await modalConfig().onConfirm();

    expect(createSmsChannel).toHaveBeenCalledWith(channel());
    expect(updateSmsChannel).not.toHaveBeenCalled();
    expect(state.modalApi.close).toHaveBeenCalledOnce();
    expect(wrapper.emitted('success')).toHaveLength(1);
    expect(showSuccessMessage).toHaveBeenCalledWith(
      'ui.actionMessage.operationSuccess',
    );
  });

  it('loads the channel for editing and submits via update', async () => {
    const wrapper = mountForm();

    await modalConfig().onOpenChange(true);
    expect(getSmsChannel).toHaveBeenCalledWith(7);
    expect(state.formApi.setValues).toHaveBeenCalledWith(
      channel({ signature: 'Framework' }),
    );

    await modalConfig().onConfirm();
    expect(updateSmsChannel).toHaveBeenCalledWith(channel());
    expect(createSmsChannel).not.toHaveBeenCalled();
    expect(wrapper.emitted('success')).toHaveLength(1);
  });

  it('skips submission when validation fails', async () => {
    state.formApi.validate.mockResolvedValue({ valid: false });
    mountForm();

    await modalConfig().onConfirm();

    expect(createSmsChannel).not.toHaveBeenCalled();
    expect(state.modalApi.lock).not.toHaveBeenCalled();
  });

  it('resets state on close so the next submission creates', async () => {
    mountForm();

    await modalConfig().onOpenChange(true);
    await modalConfig().onOpenChange(false);

    await modalConfig().onConfirm();
    expect(createSmsChannel).toHaveBeenCalledOnce();
    expect(updateSmsChannel).not.toHaveBeenCalled();
  });
});

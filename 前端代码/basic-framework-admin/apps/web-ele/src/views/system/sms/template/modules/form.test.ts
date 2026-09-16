import type { SystemSmsTemplateApi } from '#/api/system/sms/template';

import { mount } from '@vue/test-utils';

import { beforeEach, describe, expect, it, vi } from 'vitest';

import {
  createSmsTemplate,
  getSmsTemplate,
  updateSmsTemplate,
} from '#/api/system/sms/template';
import { showSuccessMessage } from '#/utils/feedback';

import SmsTemplateForm from './form.vue';

interface ModalConfig {
  onConfirm: () => Promise<void>;
  onOpenChange: (isOpen: boolean) => Promise<void>;
}

const state = vi.hoisted(() => ({
  formApi: {
    getValues: vi.fn<() => Promise<SystemSmsTemplateApi.Template>>(),
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

vi.mock('#/api/system/sms/template', () => ({
  createSmsTemplate: vi.fn(),
  getSmsTemplate: vi.fn(),
  updateSmsTemplate: vi.fn(),
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
  return mount(SmsTemplateForm);
}

function template(
  overrides: Partial<SystemSmsTemplateApi.Template> = {},
): SystemSmsTemplateApi.Template {
  return {
    apiTemplateId: 'SMS_123',
    channelCode: 'aliyun',
    channelId: 1,
    code: 'LOGIN_CODE',
    content: '您的验证码是 {code}',
    id: 7,
    name: '登录验证码',
    params: ['code'],
    remark: '',
    status: 0,
    type: 1,
    ...overrides,
  };
}

describe('system sms template form modal', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    state.modalConfig = undefined;
    state.formApi.validate.mockResolvedValue({ valid: true });
    state.formApi.getValues.mockResolvedValue(template());
    state.modalApi.getData.mockReturnValue({ id: 7 });
    vi.mocked(getSmsTemplate).mockResolvedValue(
      template({ name: '密码重置码' }),
    );
  });

  it('validates before submitting and creates a template', async () => {
    const wrapper = mountForm();

    await modalConfig().onConfirm();

    expect(createSmsTemplate).toHaveBeenCalledWith(template());
    expect(updateSmsTemplate).not.toHaveBeenCalled();
    expect(state.modalApi.close).toHaveBeenCalledOnce();
    expect(wrapper.emitted('success')).toHaveLength(1);
    expect(showSuccessMessage).toHaveBeenCalledWith(
      'ui.actionMessage.operationSuccess',
    );
  });

  it('loads the template for editing and submits via update', async () => {
    const wrapper = mountForm();

    await modalConfig().onOpenChange(true);
    expect(getSmsTemplate).toHaveBeenCalledWith(7);
    expect(state.formApi.setValues).toHaveBeenCalledWith(
      template({ name: '密码重置码' }),
    );

    await modalConfig().onConfirm();
    expect(updateSmsTemplate).toHaveBeenCalledWith(template());
    expect(createSmsTemplate).not.toHaveBeenCalled();
    expect(wrapper.emitted('success')).toHaveLength(1);
  });

  it('skips submission when validation fails', async () => {
    state.formApi.validate.mockResolvedValue({ valid: false });
    mountForm();

    await modalConfig().onConfirm();

    expect(createSmsTemplate).not.toHaveBeenCalled();
    expect(state.modalApi.lock).not.toHaveBeenCalled();
  });

  it('unlocks the modal when the update fails', async () => {
    vi.mocked(updateSmsTemplate).mockRejectedValue(new Error('request failed'));
    mountForm();

    await modalConfig().onOpenChange(true);
    await expect(modalConfig().onConfirm()).rejects.toThrow('request failed');

    expect(state.modalApi.close).not.toHaveBeenCalled();
    expect(state.modalApi.unlock).toHaveBeenCalledTimes(2);
  });
});

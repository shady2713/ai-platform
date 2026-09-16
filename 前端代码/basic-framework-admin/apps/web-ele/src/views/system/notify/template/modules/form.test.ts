import type { SystemNotifyTemplateApi } from '#/api/system/notify/template';

import { mount } from '@vue/test-utils';

import { beforeEach, describe, expect, it, vi } from 'vitest';

import {
  createNotifyTemplate,
  getNotifyTemplate,
  updateNotifyTemplate,
} from '#/api/system/notify/template';
import { showSuccessMessage } from '#/utils/feedback';

import NotifyTemplateForm from './form.vue';

interface ModalConfig {
  onConfirm: () => Promise<void>;
  onOpenChange: (isOpen: boolean) => Promise<void>;
}

const state = vi.hoisted(() => ({
  formApi: {
    getValues: vi.fn<() => Promise<SystemNotifyTemplateApi.Template>>(),
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

vi.mock('#/api/system/notify/template', () => ({
  createNotifyTemplate: vi.fn(),
  getNotifyTemplate: vi.fn(),
  updateNotifyTemplate: vi.fn(),
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
  return mount(NotifyTemplateForm);
}

function template(
  overrides: Partial<SystemNotifyTemplateApi.Template> = {},
): SystemNotifyTemplateApi.Template {
  return {
    code: 'password_reset',
    content: '您的验证码是 {code}',
    id: 3,
    name: '密码重置',
    nickname: '系统',
    params: ['code'],
    remark: '',
    status: 0,
    type: 1,
    ...overrides,
  };
}

describe('system notify template form modal', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    state.modalConfig = undefined;
    state.formApi.validate.mockResolvedValue({ valid: true });
    state.formApi.getValues.mockResolvedValue(template());
    state.modalApi.getData.mockReturnValue({ id: 3 });
    vi.mocked(getNotifyTemplate).mockResolvedValue(
      template({ name: '密码重置码' }),
    );
  });

  it('validates before submitting and creates a template', async () => {
    state.modalApi.getData.mockReturnValue(null);
    const wrapper = mountForm();

    await modalConfig().onOpenChange(true);
    expect(state.modalApi.setState).toHaveBeenCalledWith({
      title: 'ui.actionTitle.create',
    });
    expect(getNotifyTemplate).not.toHaveBeenCalled();

    await modalConfig().onConfirm();
    expect(createNotifyTemplate).toHaveBeenCalledWith(template());
    expect(updateNotifyTemplate).not.toHaveBeenCalled();
    expect(state.modalApi.close).toHaveBeenCalledOnce();
    expect(wrapper.emitted('success')).toHaveLength(1);
    expect(showSuccessMessage).toHaveBeenCalledWith(
      'ui.actionMessage.operationSuccess',
    );
  });

  it('loads the template for editing and submits via update', async () => {
    const wrapper = mountForm();

    await modalConfig().onOpenChange(true);
    expect(state.modalApi.setState).toHaveBeenCalledWith({
      title: 'ui.actionTitle.edit',
    });
    expect(getNotifyTemplate).toHaveBeenCalledWith(3);
    expect(state.formApi.setValues).toHaveBeenCalledWith(
      template({ name: '密码重置码' }),
    );
    expect(state.modalApi.lock).toHaveBeenCalledOnce();
    expect(state.modalApi.unlock).toHaveBeenCalledOnce();

    await modalConfig().onConfirm();
    expect(updateNotifyTemplate).toHaveBeenCalledWith(template());
    expect(createNotifyTemplate).not.toHaveBeenCalled();
    expect(wrapper.emitted('success')).toHaveLength(1);
  });

  it('clears the form data on close', async () => {
    mountForm();

    await modalConfig().onOpenChange(false);

    expect(getNotifyTemplate).not.toHaveBeenCalled();
    expect(state.modalApi.setState).not.toHaveBeenCalled();
  });

  it('skips submission when validation fails', async () => {
    state.formApi.validate.mockResolvedValue({ valid: false });
    mountForm();

    await modalConfig().onConfirm();

    expect(createNotifyTemplate).not.toHaveBeenCalled();
    expect(state.modalApi.lock).not.toHaveBeenCalled();
  });

  it('unlocks the modal when the update fails', async () => {
    vi.mocked(updateNotifyTemplate).mockRejectedValue(
      new Error('request failed'),
    );
    mountForm();

    await modalConfig().onOpenChange(true);
    await expect(modalConfig().onConfirm()).rejects.toThrow('request failed');

    expect(state.modalApi.close).not.toHaveBeenCalled();
    expect(showSuccessMessage).not.toHaveBeenCalled();
    expect(state.modalApi.unlock).toHaveBeenCalledTimes(2);
  });
});

import type { AiApplicationApi } from '#/api/ai/application';

import { flushPromises, mount } from '@vue/test-utils';

import { beforeEach, describe, expect, it, vi } from 'vitest';

import {
  createApplication,
  getApplication,
  rotateCredential,
  updateApplication,
} from '#/api/ai/application';

import ApplicationForm from './form.vue';
import Rotate from './rotate.vue';
import Secret from './secret.vue';

interface ModalConfig {
  onConfirm: () => Promise<void>;
  onOpenChange: (isOpen: boolean) => Promise<void>;
}

const state = vi.hoisted(() => ({
  formApi: {
    getValues: vi.fn(),
    resetForm: vi.fn(() => Promise.resolve()),
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
  modalConfigs: [] as ModalConfig[],
}));

vi.mock('@vben/common-ui', async () => {
  const actual =
    await vi.importActual<typeof import('@vben/common-ui')>('@vben/common-ui');
  const { defineComponent, h } = await import('vue');
  return {
    z: actual.z,
    useVbenModal: vi.fn((config: ModalConfig) => {
      state.modalConfigs.push(config);
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

vi.mock('#/api/ai/application', () => ({
  createApplication: vi.fn(),
  getApplication: vi.fn(),
  rotateCredential: vi.fn(),
  updateApplication: vi.fn(),
}));

vi.mock('#/locales', () => ({ $t: (key: string) => key }));
vi.mock('#/utils/feedback', () => ({ showSuccessMessage: vi.fn() }));

function config(index: number) {
  const found = state.modalConfigs[index];
  if (!found) {
    throw new Error(`弹窗配置 ${index} 未初始化`);
  }
  return found;
}

function application(): AiApplicationApi.Application {
  return {
    appCode: 'crm-portal',
    credentialConfigured: true,
    description: '对接 CRM',
    enabled: true,
    id: 5,
    name: 'CRM 门户',
    origins: ['https://crm.example.com'],
    version: 2,
  };
}

describe('ai application modules', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    state.modalConfigs.length = 0;
    state.formApi.validate.mockResolvedValue({ valid: true });
    state.modalApi.getData.mockReturnValue(undefined);
  });

  it('creates application with parsed exact origins and emits the one-time secret', async () => {
    state.formApi.getValues.mockResolvedValue({
      appCode: 'crm-portal',
      name: 'CRM 门户',
      originsText: 'https://crm.example.com\n\nhttps://api.example.com\n',
    });
    vi.mocked(createApplication).mockResolvedValue({
      appCode: 'crm-portal',
      applicationId: 5,
      credentialId: 9,
      secret: 'aiapp_once',
    });
    const wrapper = mount(ApplicationForm);

    await config(0).onConfirm();

    expect(createApplication).toHaveBeenCalledWith({
      appCode: 'crm-portal',
      description: undefined,
      id: undefined,
      name: 'CRM 门户',
      origins: ['https://crm.example.com', 'https://api.example.com'],
      version: undefined,
    });
    expect(wrapper.emitted('secret')?.[0]?.[0]).toEqual({
      appCode: 'crm-portal',
      secret: 'aiapp_once',
    });
  });

  it('updates application without sending an empty credential and validates first', async () => {
    state.modalApi.getData.mockReturnValue(application());
    vi.mocked(getApplication).mockResolvedValue(application());
    state.formApi.getValues.mockResolvedValue({
      appCode: 'crm-portal',
      credential: '',
      id: 5,
      name: 'CRM 门户',
      originsText: 'https://crm.example.com',
      version: 2,
    });
    mount(ApplicationForm);

    await config(0).onOpenChange(true);
    await config(0).onConfirm();

    expect(updateApplication).toHaveBeenCalledWith({
      appCode: 'crm-portal',
      description: undefined,
      id: 5,
      name: 'CRM 门户',
      origins: ['https://crm.example.com'],
      version: 2,
    });
    expect(createApplication).not.toHaveBeenCalled();

    state.formApi.validate.mockResolvedValue({ valid: false });
    vi.mocked(updateApplication).mockClear();
    await config(0).onConfirm();
    expect(updateApplication).not.toHaveBeenCalled();
  });

  it('rotates credential and emits the new secret once', async () => {
    state.modalApi.getData.mockReturnValue(application());
    vi.mocked(rotateCredential).mockResolvedValue({
      appCode: 'crm-portal',
      applicationId: 5,
      credentialId: 10,
      secret: 'aiapp_rotated',
    });
    const wrapper = mount(Rotate);

    await config(0).onOpenChange(true);
    await config(0).onConfirm();
    await flushPromises();

    expect(rotateCredential).toHaveBeenCalledWith(5, 2);
    expect(wrapper.emitted('secret')?.[0]?.[0]).toEqual({
      appCode: 'crm-portal',
      secret: 'aiapp_rotated',
    });
  });

  it('secret modal shows the value once and clears it when closed', async () => {
    state.modalApi.getData.mockReturnValue({
      appCode: 'crm-portal',
      secret: 'aiapp_once',
    });
    const wrapper = mount(Secret);

    await config(0).onOpenChange(true);
    await flushPromises();
    expect(wrapper.find('[data-test="ai-secret-value"]').text()).toBe(
      'aiapp_once',
    );

    await config(0).onOpenChange(false);
    state.modalApi.getData.mockReturnValue({
      appCode: 'crm-portal',
      secret: '',
    });
    await config(0).onOpenChange(true);
    await flushPromises();

    // 关闭即清理：再次打开（即使没有新秘密）不会残留上一次的明文
    expect(wrapper.find('[data-test="ai-secret-value"]').text()).toBe('');
  });
});

import type { AiModelEndpointApi } from '#/api/ai/model-endpoint';

import { flushPromises, mount } from '@vue/test-utils';

import { beforeEach, describe, expect, it, vi } from 'vitest';

import {
  createModelEndpoint,
  getModelEndpoint,
  rotateModelEndpointCredential,
  updateModelEndpoint,
} from '#/api/ai/model-endpoint';
import { showSuccessMessage } from '#/utils/feedback';

import ModelEndpointForm from './form.vue';
import ModelEndpointRotate from './rotate.vue';

interface ModalConfig {
  onConfirm: () => Promise<void>;
  onOpenChange: (isOpen: boolean) => Promise<void>;
}

const state = vi.hoisted(() => ({
  formApi: {
    getValues: vi.fn(() => Promise.resolve({})),
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

vi.mock('#/api/ai/model-endpoint', () => ({
  createModelEndpoint: vi.fn(),
  getModelEndpoint: vi.fn(),
  rotateModelEndpointCredential: vi.fn(),
  updateModelEndpoint: vi.fn(),
}));

vi.mock('#/locales', () => ({ $t: (key: string) => key }));

vi.mock('#/utils/feedback', () => ({ showSuccessMessage: vi.fn() }));

vi.mock('../data', () => ({
  useFormSchema: vi.fn(() => []),
  useRotateSchema: vi.fn(() => []),
}));

function modalConfig() {
  if (!state.modalConfig) {
    throw new Error('弹窗配置未初始化');
  }
  return state.modalConfig;
}

function endpoint(
  overrides: Partial<AiModelEndpointApi.ModelEndpoint> = {},
): AiModelEndpointApi.ModelEndpoint {
  return {
    baseUrl: 'https://api.example.com/v1',
    capabilities: ['TEXT'],
    configRevision: 1,
    credentialConfigured: true,
    credentialRevision: 1,
    enabled: true,
    id: 9,
    modelId: 'gpt-4o-mini',
    name: 'openai-生产',
    provider: 'openai_compatible',
    referenced: false,
    version: 3,
    ...overrides,
  };
}

describe('ai model endpoint form modal', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    state.modalConfig = undefined;
    state.formApi.validate.mockResolvedValue({ valid: true });
    state.modalApi.getData.mockReturnValue(undefined);
  });

  it('creates a new endpoint without sending an empty credential', async () => {
    state.formApi.getValues.mockResolvedValue({
      baseUrl: 'https://api.example.com/v1',
      capabilities: ['TEXT'],
      modelId: 'gpt-4o-mini',
      name: 'openai-生产',
      provider: 'openai_compatible',
    });
    const wrapper = mount(ModelEndpointForm);

    await modalConfig().onConfirm();

    expect(createModelEndpoint).toHaveBeenCalledWith({
      baseUrl: 'https://api.example.com/v1',
      capabilities: ['TEXT'],
      modelId: 'gpt-4o-mini',
      name: 'openai-生产',
      provider: 'openai_compatible',
    });
    expect(updateModelEndpoint).not.toHaveBeenCalled();
    expect(wrapper.emitted('success')).toHaveLength(1);
    expect(showSuccessMessage).toHaveBeenCalled();
    expect(state.modalApi.unlock).toHaveBeenCalledOnce();
  });

  it('keeps the stored credential when editing and blank field is left untouched', async () => {
    state.modalApi.getData.mockReturnValue(endpoint());
    vi.mocked(getModelEndpoint).mockResolvedValue(endpoint());
    state.formApi.getValues.mockResolvedValue({
      baseUrl: 'https://api.example.com/v1',
      capabilities: ['TEXT', 'EMBEDDING'],
      credential: '',
      id: 9,
      modelId: 'gpt-4o-mini',
      name: 'openai-生产',
      provider: 'openai_compatible',
      version: 3,
    });
    mount(ModelEndpointForm);

    await modalConfig().onOpenChange(true);
    await modalConfig().onConfirm();

    expect(getModelEndpoint).toHaveBeenCalledWith(9);
    expect(updateModelEndpoint).toHaveBeenCalledWith({
      baseUrl: 'https://api.example.com/v1',
      capabilities: ['TEXT', 'EMBEDDING'],
      id: 9,
      modelId: 'gpt-4o-mini',
      name: 'openai-生产',
      provider: 'openai_compatible',
      version: 3,
    });
    expect(
      vi.mocked(updateModelEndpoint).mock.calls[0]?.[0],
    ).not.toHaveProperty('credential');
  });

  it('submits a new credential when provided and skips invalid forms', async () => {
    state.formApi.getValues.mockResolvedValue({
      baseUrl: 'https://api.example.com/v1',
      capabilities: ['TEXT'],
      credential: 'sk-new',
      modelId: 'gpt-4o-mini',
      name: 'openai-生产',
      provider: 'openai_compatible',
    });
    mount(ModelEndpointForm);

    await modalConfig().onConfirm();

    expect(createModelEndpoint).toHaveBeenCalledWith(
      expect.objectContaining({ credential: 'sk-new' }),
    );

    state.formApi.validate.mockResolvedValue({ valid: false });
    vi.mocked(createModelEndpoint).mockClear();
    // 校验失败时同一份弹窗配置不得再次提交
    await modalConfig().onConfirm();
    expect(vi.mocked(createModelEndpoint)).not.toHaveBeenCalled();
  });

  it('resets the form when reopened for creation', async () => {
    mount(ModelEndpointForm);

    await modalConfig().onOpenChange(false);
    await modalConfig().onOpenChange(true);

    expect(state.formApi.resetForm).toHaveBeenCalled();
    expect(getModelEndpoint).not.toHaveBeenCalled();
  });
});

describe('ai model endpoint rotate modal', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    state.modalConfig = undefined;
    state.formApi.validate.mockResolvedValue({ valid: true });
    state.modalApi.getData.mockReturnValue(endpoint());
  });

  it('rotates the credential with the optimistic lock version', async () => {
    state.formApi.getValues.mockResolvedValue({ credential: 'sk-rotated' });
    const wrapper = mount(ModelEndpointRotate);

    await modalConfig().onOpenChange(true);
    await modalConfig().onConfirm();
    await flushPromises();

    expect(rotateModelEndpointCredential).toHaveBeenCalledWith(
      9,
      3,
      'sk-rotated',
    );
    expect(wrapper.emitted('success')).toHaveLength(1);
    expect(state.modalApi.setState).toHaveBeenCalledWith(
      expect.objectContaining({
        title: expect.stringContaining('openai-生产'),
      }),
    );
  });

  it('does not rotate when the form is invalid', async () => {
    state.formApi.validate.mockResolvedValue({ valid: false });
    mount(ModelEndpointRotate);

    await modalConfig().onOpenChange(true);
    await modalConfig().onConfirm();

    expect(rotateModelEndpointCredential).not.toHaveBeenCalled();
  });
});

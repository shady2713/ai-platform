import type { AiModelEndpointApi } from '#/api/ai/model-endpoint';

import { flushPromises, mount } from '@vue/test-utils';

import { beforeEach, describe, expect, it, vi } from 'vitest';

import {
  getModelEndpointCapabilities,
  getModelEndpointProbeResults,
  probeModelEndpoint,
} from '#/api/ai/model-endpoint';
import { showSuccessMessage } from '#/utils/feedback';

import ModelEndpointProbe from './probe.vue';

interface ModalConfig {
  onOpenChange: (isOpen: boolean) => Promise<void>;
}

const state = vi.hoisted(() => ({
  modalApi: {
    close: vi.fn(() => Promise.resolve()),
    getData: vi.fn(),
    setState: vi.fn(),
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

vi.mock('#/api/ai/model-endpoint', () => ({
  getModelEndpointCapabilities: vi.fn(),
  getModelEndpointProbeResults: vi.fn(),
  probeModelEndpoint: vi.fn(),
}));

vi.mock('#/locales', () => ({ $t: (key: string) => key }));

vi.mock('#/utils/feedback', () => ({ showSuccessMessage: vi.fn() }));

vi.mock('../data', () => ({
  PROBE_KIND_LABELS: { TEXT: '文本' },
  PROBE_STATUS_OPTIONS: [
    { color: 'success', label: '可用', value: 'SUPPORTED' },
    { color: 'info', label: '不支持', value: 'UNSUPPORTED' },
    { color: 'danger', label: '探测失败', value: 'FAILED' },
  ],
}));

function modalConfig() {
  if (!state.modalConfig) {
    throw new Error('弹窗配置未初始化');
  }
  return state.modalConfig;
}

function endpoint(): AiModelEndpointApi.ModelEndpoint {
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
  };
}

function results(
  status: string,
  detailCode?: string,
): AiModelEndpointApi.ModelProbeResult[] {
  return [
    { configRevision: 1, detailCode, latencyMs: 12, probeKind: 'TEXT', status },
    {
      configRevision: 1,
      embeddingDimension: 1536,
      latencyMs: 30,
      probeKind: 'EMBEDDING',
      status,
    },
  ];
}

function overview(): AiModelEndpointApi.CapabilityOverview {
  return {
    declared: ['TEXT'],
    endpointId: 9,
    publishable: ['TEXT'],
    supported: ['TEXT'],
  };
}

describe('ai model endpoint probe modal', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    state.modalConfig = undefined;
    state.modalApi.getData.mockReturnValue(endpoint());
    vi.mocked(getModelEndpointProbeResults).mockResolvedValue(
      results('SUPPORTED'),
    );
    vi.mocked(getModelEndpointCapabilities).mockResolvedValue(overview());
    vi.mocked(probeModelEndpoint).mockResolvedValue(
      results('FAILED', 'TIMEOUT'),
    );
  });

  it('loads the latest conclusions and capability scope on open', async () => {
    const wrapper = mount(ModelEndpointProbe);

    await modalConfig().onOpenChange(true);
    await flushPromises();

    expect(getModelEndpointProbeResults).toHaveBeenCalledWith(9);
    expect(getModelEndpointCapabilities).toHaveBeenCalledWith(9);
    expect(state.modalApi.setState).toHaveBeenCalledWith(
      expect.objectContaining({
        title: expect.stringContaining('openai-生产'),
      }),
    );
    expect(wrapper.text()).toContain('可发布范围：TEXT');
  });

  it('re-runs the probe and shows the stable detail code on failure', async () => {
    const wrapper = mount(ModelEndpointProbe);
    await modalConfig().onOpenChange(true);
    await flushPromises();

    const probeButton = wrapper
      .findAll('button')
      .find((button) => button.text().includes('重新探测'));
    await probeButton?.trigger('click');
    await flushPromises();

    expect(probeModelEndpoint).toHaveBeenCalledWith(9);
    expect(showSuccessMessage).toHaveBeenCalled();
    expect(wrapper.text()).toContain('TIMEOUT');
    expect(wrapper.text()).toContain('维度 1536');
  });

  it('clears state when closed', async () => {
    const wrapper = mount(ModelEndpointProbe);
    await modalConfig().onOpenChange(true);
    await flushPromises();

    await modalConfig().onOpenChange(false);
    await flushPromises();

    expect(wrapper.text()).not.toContain('可发布范围');
  });
});

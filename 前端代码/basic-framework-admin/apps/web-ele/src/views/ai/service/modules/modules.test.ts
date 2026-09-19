import type { AiServiceApi } from '#/api/ai/service';

import { flushPromises, mount } from '@vue/test-utils';

import { beforeEach, describe, expect, it, vi } from 'vitest';

import {
  bindServiceResource,
  checkReleaseReadiness,
  createReleaseCandidate,
  createService,
  getService,
  listReleaseEvaluations,
  listReleases,
  listServiceResources,
  publishRelease,
  recordReleaseEvaluation,
  rollbackRelease,
  runServiceDebug,
  unbindServiceResource,
  updateService,
} from '#/api/ai/service';

import Debug from './debug.vue';
import Form from './form.vue';
import Release from './release.vue';
import Versions from './versions.vue';

interface ModalConfig {
  onOpenChange: (isOpen: boolean) => Promise<void>;
}

interface FormConfig {
  handleValuesChange?: (
    values: Record<string, unknown>,
    fieldsChanged: string[],
  ) => void;
}

const state = vi.hoisted(() => ({
  formApi: {
    getValues: vi.fn(),
    resetForm: vi.fn(() => Promise.resolve()),
    setValues: vi.fn(() => Promise.resolve()),
    validate: vi.fn<() => Promise<{ valid: boolean }>>(),
  },
  formConfigs: [] as unknown[],
  modalApi: {
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
    useVbenForm: vi.fn((config: unknown) => {
      state.formConfigs.push(config);
      return [
        defineComponent({
          name: 'FormStub',
          setup(_props, { slots }) {
            return () => h('form', slots.default?.());
          },
        }),
        state.formApi,
      ];
    }),
  };
});

vi.mock('#/api/ai/service', () => ({
  bindServiceResource: vi.fn(),
  checkReleaseReadiness: vi.fn(),
  createReleaseCandidate: vi.fn(),
  createService: vi.fn(),
  getService: vi.fn(),
  listReleaseEvaluations: vi.fn(),
  listReleases: vi.fn(),
  listServiceResources: vi.fn(),
  publishRelease: vi.fn(),
  recordReleaseEvaluation: vi.fn(),
  rollbackRelease: vi.fn(),
  runServiceDebug: vi.fn(),
  unbindServiceResource: vi.fn(),
  updateService: vi.fn(),
}));

vi.mock('#/locales', () => ({ $t: (key: string) => key }));
vi.mock('#/utils/feedback', () => ({ showSuccessMessage: vi.fn() }));

function modalConfig(index: number) {
  const found = state.modalConfigs[index];
  if (!found) {
    throw new Error(`弹窗配置 ${index} 未初始化`);
  }
  return found;
}

function formConfig(index: number) {
  const found = state.formConfigs[index] as FormConfig | undefined;
  if (!found) {
    throw new Error(`表单配置 ${index} 未初始化`);
  }
  return found;
}

function service(
  overrides: Partial<AiServiceApi.Service> = {},
): AiServiceApi.Service {
  return {
    appId: 5,
    code: 'svc_order_qa',
    draftRevision: 3,
    evalThreshold: 80,
    id: 9,
    inputSchema: '{"type":"object"}',
    modelEndpointId: 1,
    name: '订单问答',
    promptTemplate: '你是订单助手',
    requiredCapabilities: ['TEXT'],
    runSubjectType: 'USER',
    status: 'READY',
    version: 4,
    ...overrides,
  };
}

function release(
  overrides: Partial<AiServiceApi.Release> = {},
): AiServiceApi.Release {
  return {
    contentHash: 'a'.repeat(64),
    endpointConfigRevision: 3,
    evalThreshold: 80,
    id: 21,
    modelEndpointId: 1,
    releaseVersion: 1,
    requiredCapabilities: 'TEXT',
    serviceId: 9,
    status: 'CANDIDATE',
    version: 0,
    ...overrides,
  };
}

describe('ai service modules', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    state.modalConfigs.length = 0;
    state.formConfigs.length = 0;
    state.formApi.validate.mockResolvedValue({ valid: true });
    state.modalApi.getData.mockReturnValue(undefined);
    vi.mocked(listServiceResources).mockResolvedValue([]);
    vi.mocked(listReleases).mockResolvedValue([]);
    vi.mocked(listReleaseEvaluations).mockResolvedValue([]);
    vi.mocked(checkReleaseReadiness).mockResolvedValue([]);
  });

  it('草稿编辑器即时提示非法 Schema，并在提交前再次拦截', async () => {
    state.modalApi.getData.mockReturnValue(service());
    vi.mocked(getService).mockResolvedValue(service());
    const wrapper = mount(Form);

    await modalConfig(0).onOpenChange(true);

    // 字段变化即提示：非法 JSON 与"不是对象"给出不同提示
    formConfig(0).handleValuesChange?.({ inputSchema: 'not-json' }, [
      'inputSchema',
    ]);
    await wrapper.vm.$nextTick();
    expect(wrapper.text()).toContain('不是合法 JSON');

    formConfig(0).handleValuesChange?.({ inputSchema: '[1,2]' }, [
      'inputSchema',
    ]);
    await wrapper.vm.$nextTick();
    expect(wrapper.text()).toContain('必须是 JSON 对象');

    // 提交前再次校验：非法 Schema 不提交，后端仍会独立拒绝
    state.formApi.getValues.mockResolvedValue({
      appId: 5,
      code: 'svc_order_qa',
      evalThreshold: 80,
      id: 9,
      inputSchema: 'not-json',
      modelEndpointId: 1,
      name: '订单问答',
      promptTemplate: '你是订单助手',
      requiredCapabilities: ['TEXT'],
      runSubjectType: 'USER',
      version: 4,
    });
    await modalConfig(0).onOpenChange(true);
    await state.formApi.validate();
    expect(updateService).not.toHaveBeenCalled();
  });

  it('草稿编辑器保存合法 Schema 并提交完整字段', async () => {
    state.formApi.getValues.mockResolvedValue({
      appId: 5,
      code: 'svc_order_qa',
      evalThreshold: 80,
      id: 9,
      inputSchema: '{"type":"object"}',
      modelEndpointId: 1,
      name: '订单问答',
      outputSchema: '{"type":"object"}',
      promptTemplate: '你是订单助手',
      requiredCapabilities: ['TEXT'],
      runSubjectType: 'USER',
      version: 4,
    });
    mount(Form);

    await modalConfig(0).onOpenChange(true);
    await (
      modalConfig(0) as unknown as { onConfirm: () => Promise<void> }
    ).onConfirm();

    expect(updateService).toHaveBeenCalledWith({
      appId: 5,
      code: 'svc_order_qa',
      evalThreshold: 80,
      id: 9,
      inputSchema: '{"type":"object"}',
      modelEndpointId: 1,
      name: '订单问答',
      outputSchema: '{"type":"object"}',
      promptTemplate: '你是订单助手',
      requiredCapabilities: ['TEXT'],
      runSubjectType: 'USER',
      version: 4,
    });
  });

  it('资源绑定与解绑走真实接口（越权由后端拒绝）', async () => {
    state.modalApi.getData.mockReturnValue(service());
    vi.mocked(getService).mockResolvedValue(service());
    vi.mocked(listServiceResources).mockResolvedValue([
      {
        actions: ['READ'],
        id: 11,
        resourceKey: 'report-1',
        resourceType: 'REPORT',
        serviceId: 9,
        status: 'ACTIVE',
        version: 0,
      },
    ]);
    vi.mocked(bindServiceResource).mockResolvedValue(12);
    const wrapper = mount(Form);

    await modalConfig(0).onOpenChange(true);
    expect(wrapper.text()).toContain('report-1');

    const keyInput = wrapper.find('input[placeholder="资源标识"]');
    expect(keyInput.exists()).toBe(true);
    await keyInput.setValue('report-2');

    const button = wrapper
      .findAll('button')
      .find((item) => item.text() === '绑定资源');
    expect(button).toBeDefined();
    await button?.trigger('click');
    await flushPromises();
    expect(bindServiceResource).toHaveBeenCalledWith({
      actions: ['READ'],
      resourceKey: 'report-2',
      resourceType: 'REPORT',
      serviceId: 9,
    });

    const unbind = wrapper
      .findAll('button')
      .find((item) => item.text() === '解绑');
    expect(unbind).toBeDefined();
    await unbind?.trigger('click');
    await flushPromises();
    expect(unbindServiceResource).toHaveBeenCalledWith(11, 0);
  });

  it('发布前显示预检查阻塞项，未通过时不发布', async () => {
    state.modalApi.getData.mockReturnValue(service());
    vi.mocked(getService).mockResolvedValue(service());
    vi.mocked(listReleases).mockResolvedValue([
      release({ id: 21, version: 2 }),
    ]);
    vi.mocked(checkReleaseReadiness).mockResolvedValue([
      '缺少与候选内容匹配的评测结果',
    ]);
    const wrapper = mount(Release);

    await modalConfig(0).onOpenChange(true);
    expect(wrapper.text()).toContain('缺少与候选内容匹配的评测结果');
    expect(wrapper.text()).toContain('候选 v1');

    const publish = wrapper
      .findAll('button')
      .find((item) => item.text().includes('发布（切换别名）'));
    expect(publish?.attributes('disabled')).toBeDefined();
    await publish?.trigger('click');
    expect(publishRelease).not.toHaveBeenCalled();
  });

  it('评测达标后发布切换别名并刷新版本', async () => {
    state.modalApi.getData.mockReturnValue(service());
    vi.mocked(getService).mockResolvedValue(service());
    vi.mocked(listReleases).mockResolvedValue([
      release({ id: 21, version: 2 }),
    ]);
    vi.mocked(createReleaseCandidate).mockResolvedValue(22);
    state.formApi.getValues.mockResolvedValue({
      caseCount: 12,
      notes: '回归集',
      score: 92,
    });
    const wrapper = mount(Release);

    await modalConfig(0).onOpenChange(true);
    const create = wrapper
      .findAll('button')
      .find((item) => item.text() === '创建发布候选');
    expect(create).toBeDefined();
    await create?.trigger('click');
    await flushPromises();
    expect(createReleaseCandidate).toHaveBeenCalledWith(9, 4);

    const evaluate = wrapper
      .findAll('button')
      .find((item) => item.text() === '记录评测结论');
    expect(evaluate).toBeDefined();
    await evaluate?.trigger('click');
    await flushPromises();
    expect(recordReleaseEvaluation).toHaveBeenCalledWith(21, 92, 12, '回归集');

    const publish = wrapper
      .findAll('button')
      .find((item) => item.text().includes('发布（切换别名）'));
    expect(publish).toBeDefined();
    await publish?.trigger('click');
    await flushPromises();
    expect(publishRelease).toHaveBeenCalledWith(21, 2);
    expect(wrapper.emitted('success')).toBeTruthy();
  });

  it('版本历史显示当前生效版本，回退只影响后续运行', async () => {
    state.modalApi.getData.mockReturnValue(service());
    vi.mocked(listReleases).mockResolvedValue([
      release({ id: 22, releaseVersion: 2, status: 'ACTIVE', version: 1 }),
      release({ id: 21, releaseVersion: 1, status: 'RETIRED', version: 3 }),
    ]);
    vi.mocked(listReleaseEvaluations).mockResolvedValue([
      {
        caseCount: 10,
        contentHash: 'a'.repeat(64),
        endpointConfigRevision: 3,
        id: 31,
        passed: true,
        releaseId: 21,
        score: 92,
        threshold: 80,
      },
    ]);
    vi.mocked(rollbackRelease).mockResolvedValue(true);
    const wrapper = mount(Versions);

    await modalConfig(0).onOpenChange(true);
    expect(wrapper.find('[data-testid="current-version"]').text()).toContain(
      'v2',
    );

    // 第二行是 v1（已退役）：点击它的"查看影响"
    const inspect = wrapper
      .findAll('button')
      .filter((item) => item.text() === '查看影响')[1];
    expect(inspect).toBeDefined();
    await inspect?.trigger('click');
    await flushPromises();
    expect(wrapper.text()).toContain('v2 → v1');
    expect(wrapper.text()).toContain('只影响后续新运行');
    expect(wrapper.text()).toContain('得分 92');

    const rollback = wrapper
      .findAll('button')
      .find((item) => item.text() === '回退到该版本');
    expect(rollback).toBeDefined();
    await rollback?.trigger('click');
    await flushPromises();
    expect(rollbackRelease).toHaveBeenCalledWith(21, 3);
    expect(wrapper.emitted('success')).toBeTruthy();

    // 回退后刷新：当前生效版本跟着版本表变化
    vi.mocked(listReleases).mockResolvedValue([
      release({ id: 22, releaseVersion: 2, status: 'RETIRED', version: 2 }),
      release({ id: 21, releaseVersion: 1, status: 'ACTIVE', version: 4 }),
    ]);
    await modalConfig(0).onOpenChange(true);
    expect(wrapper.find('[data-testid="current-version"]').text()).toContain(
      'v1',
    );
  });

  it('调试区只展示阶段摘要与证据，失败不伪造成功', async () => {
    state.modalApi.getData.mockReturnValue(service());
    state.formApi.getValues.mockResolvedValue({
      businessContext: '{"page":"order"}',
      dataLevel: 'L2_INTERNAL',
      testSubjectId: 'u-1001',
      testSubjectType: 'USER',
      userMessage: '帮我查订单',
    });
    vi.mocked(runServiceDebug).mockResolvedValue({
      authorizedBindings: ['REPORT:report-1'],
      contentHash: 'a'.repeat(64),
      durationMs: 42,
      estimatedTokens: 60,
      modelEndpointId: 1,
      modelRevision: 3,
      output: '订单 A-1 已发货',
      releaseId: 21,
      releaseVersion: 2,
      sections: [
        {
          droppedCount: 0,
          estimatedTokens: 35,
          includedCount: 1,
          sanitized: true,
          section: 'POLICY',
          truncated: false,
        },
      ],
      stages: [
        { durationMs: 1, stage: 'RESOLVE', status: 'OK' },
        { durationMs: 2, stage: 'AUTHORIZE', status: 'OK' },
        { durationMs: 1, stage: 'CONTEXT', status: 'OK' },
        { durationMs: 30, stage: 'MODEL', status: 'OK' },
      ],
      structured: false,
      testSubjectType: 'USER',
      truncated: false,
    });
    const wrapper = mount(Debug);

    await modalConfig(0).onOpenChange(true);
    const run = wrapper
      .findAll('button')
      .find((item) => item.text() === '执行调试');
    expect(run).toBeDefined();
    await run?.trigger('click');
    await flushPromises();

    expect(runServiceDebug).toHaveBeenCalledWith({
      businessContext: '{"page":"order"}',
      dataLevel: 'L2_INTERNAL',
      maxTokens: undefined,
      serviceId: 9,
      testSubjectId: 'u-1001',
      testSubjectType: 'USER',
      timeoutMillis: undefined,
      userMessage: '帮我查订单',
    });
    expect(wrapper.find('[data-testid="debug-release"]').text()).toContain(
      'v2',
    );
    expect(wrapper.text()).toContain('POLICY');
    expect(wrapper.text()).toContain('已中和伪造的分区标记');
    expect(wrapper.text()).toContain('订单 A-1 已发货');
    expect(wrapper.text()).not.toContain('你是订单助手');

    // 上游失败：只提示失败，不渲染任何成功结果
    vi.mocked(runServiceDebug).mockRejectedValue(new Error('502'));
    await run?.trigger('click');
    await flushPromises();
    expect(wrapper.text()).toContain('调试失败');
    expect(wrapper.find('[data-testid="debug-release"]').exists()).toBe(false);
  });

  it('缺少显式测试主体时不调用后端', async () => {
    state.modalApi.getData.mockReturnValue(service());
    state.formApi.validate.mockResolvedValue({ valid: false });
    const wrapper = mount(Debug);

    await modalConfig(0).onOpenChange(true);
    const run = wrapper
      .findAll('button')
      .find((item) => item.text() === '执行调试');
    expect(run).toBeDefined();
    await run?.trigger('click');
    await flushPromises();

    expect(runServiceDebug).not.toHaveBeenCalled();
    expect(wrapper.text()).toContain('显式测试主体');
  });

  it('新建草稿走创建接口并加载绑定', async () => {
    vi.mocked(createService).mockResolvedValue(9);
    vi.mocked(getService).mockResolvedValue(service());
    state.formApi.getValues.mockResolvedValue({
      appId: 5,
      code: 'svc_order_qa',
      evalThreshold: 0,
      inputSchema: '{"type":"object"}',
      modelEndpointId: 1,
      name: '订单问答',
      promptTemplate: '你是订单助手',
      requiredCapabilities: ['TEXT'],
      runSubjectType: 'USER',
    });
    mount(Form);

    await modalConfig(0).onOpenChange(true);
    await (
      modalConfig(0) as unknown as { onConfirm: () => Promise<void> }
    ).onConfirm();

    expect(createService).toHaveBeenCalledWith({
      appId: 5,
      code: 'svc_order_qa',
      evalThreshold: 0,
      id: undefined,
      inputSchema: '{"type":"object"}',
      modelEndpointId: 1,
      name: '订单问答',
      promptTemplate: '你是订单助手',
      requiredCapabilities: ['TEXT'],
      runSubjectType: 'USER',
      version: undefined,
    });
    expect(listServiceResources).toHaveBeenCalledWith(9);
  });
});

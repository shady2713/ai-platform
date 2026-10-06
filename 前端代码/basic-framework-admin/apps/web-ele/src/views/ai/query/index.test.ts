import { flushPromises, mount } from '@vue/test-utils';

import { beforeEach, describe, expect, it, vi } from 'vitest';

import { createQueryPlan, getQueryDatasetSummary } from '#/api/ai/query';
import { showRequestError } from '#/utils/feedback';

import QueryIndex from './index.vue';

const state = vi.hoisted(() => ({
  access: { hasAccessByCodes: vi.fn(() => true) },
  /** 两个表单各自返回"全字段"取值：页面必须自己按端点挑字段，测试才拦得住混传 */
  formValues: {} as Record<string, unknown>,
  planApi: {
    getValues: vi.fn(),
    validate: vi.fn<() => Promise<{ valid: boolean }>>(),
  },
  summaryApi: {
    getValues: vi.fn(),
    validate: vi.fn<() => Promise<{ valid: boolean }>>(),
  },
}));

vi.mock('@vben/access', () => ({
  useAccess: () => ({ hasAccessByCodes: state.access.hasAccessByCodes }),
}));

vi.mock('@vben/common-ui', async () => {
  const actual =
    await vi.importActual<typeof import('@vben/common-ui')>('@vben/common-ui');
  const { defineComponent, h } = await import('vue');
  return {
    z: actual.z,
    Page: defineComponent({
      name: 'PageStub',
      setup(_props, { slots }) {
        return () => h('main', slots.default?.());
      },
    }),
  };
});

vi.mock('#/adapter/form', async () => {
  const { defineComponent, h } = await import('vue');
  return {
    useVbenForm: vi.fn((options: { schema: { fieldName: string }[] }) => {
      // 计划表单含 question，摘要表单不含：据此分派各自的 formApi
      const isPlan = options.schema.some(
        (item) => item.fieldName === 'question',
      );
      const formApi = isPlan ? state.planApi : state.summaryApi;
      return [
        defineComponent({
          name: 'FormStub',
          setup(_props, { slots }) {
            return () => h('form', slots.default?.());
          },
        }),
        formApi,
      ];
    }),
  };
});

vi.mock('#/api/ai/query', () => ({
  createQueryPlan: vi.fn(),
  getQueryDatasetSummary: vi.fn(),
}));
vi.mock('#/utils/feedback', () => ({ showRequestError: vi.fn() }));

function button(wrapper: ReturnType<typeof mountQuery>, label: string) {
  return wrapper.findAll('button').find((item) => item.text() === label);
}

function mountQuery() {
  return mount(QueryIndex);
}

describe('ai query 页面（D05）', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    state.access.hasAccessByCodes.mockReturnValue(true);
    state.formValues = {
      allowedFieldCodes: ' amount , amount ',
      datasetId: 81,
      datasetVersionId: 3,
      endpointId: 7,
      maxRepairs: undefined,
      question: ' 近 30 天各渠道成交金额 ',
    };
    state.planApi.validate.mockResolvedValue({ valid: true });
    state.summaryApi.validate.mockResolvedValue({ valid: true });
    state.planApi.getValues.mockImplementation(async () => state.formValues);
    state.summaryApi.getValues.mockImplementation(async () => state.formValues);
  });

  it('提交计划时按 plan 端点契约组装请求', async () => {
    vi.mocked(createQueryPlan).mockResolvedValue({ kind: 'PLAN' });
    const wrapper = mountQuery();
    await flushPromises();

    await button(wrapper, '生成查询计划')?.trigger('click');
    await flushPromises();

    expect(createQueryPlan).toHaveBeenCalledWith({
      allowedFieldCodes: ['amount'],
      datasetId: 81,
      datasetVersionId: 3,
      endpointId: 7,
      question: '近 30 天各渠道成交金额',
    });
  });

  it('pLAN 结果只读展示计划 JSON 与版本锚点，页面无 SQL 编辑面', async () => {
    vi.mocked(createQueryPlan).mockResolvedValue({
      attempts: 1,
      datasetCode: 'crm-orders',
      datasetVersionNo: 3,
      kind: 'PLAN',
      planDatasetId: 'dset_9',
      planHash: 'ph-1',
      planJson: '{"datasetId":"dset_9","metrics":[]}',
      schemaHash: 'sh-1',
    });
    const wrapper = mountQuery();
    await flushPromises();

    await button(wrapper, '生成查询计划')?.trigger('click');
    await flushPromises();

    const planBlock = wrapper.get('[data-testid="ai-query-plan-json"]');
    // 计划内容落在只读 <pre> 里；页面自身不渲染任何可编辑控件
    // （真实输入面全部由 schema 驱动，且没有 SQL 字段，见 data.test.ts）
    expect(planBlock.get('pre').element.tagName).toBe('PRE');
    expect(wrapper.findAll('input, textarea').length).toBe(0);
    expect(planBlock.get('pre').text()).toBe(
      '{\n  "datasetId": "dset_9",\n  "metrics": []\n}',
    );
    expect(wrapper.text()).toContain('计划哈希 ph-1');
    expect(wrapper.text()).toContain('数据集 crm-orders · 计划标识 dset_9');
    expect(
      wrapper.find('[data-testid="ai-query-clarification"]').exists(),
    ).toBe(false);
  });

  it('cLARIFICATION 是正常结果：展示追问、原因与授权候选', async () => {
    vi.mocked(createQueryPlan).mockResolvedValue({
      candidates: [
        { code: 'amount', label: '成交金额' },
        { code: 'gmv', label: '商品成交总额' },
      ],
      kind: 'CLARIFICATION',
      question: '你说的“金额”是哪个口径？',
      reason: 'AMBIGUOUS',
    });
    const wrapper = mountQuery();
    await flushPromises();

    await button(wrapper, '生成查询计划')?.trigger('click');
    await flushPromises();

    const clarification = wrapper.get('[data-testid="ai-query-clarification"]');
    expect(clarification.text()).toContain('你说的“金额”是哪个口径？');
    expect(clarification.text()).toContain('成交金额（amount）');
    expect(clarification.text()).toContain('商品成交总额（gmv）');
    expect(wrapper.text()).toContain('歧义');
    expect(wrapper.text()).toContain('候选 2 项');
    expect(wrapper.find('[data-testid="ai-query-plan-json"]').exists()).toBe(
      false,
    );
  });

  it('摘要只提交数据集维度参数（不带 question/endpointId）', async () => {
    vi.mocked(getQueryDatasetSummary).mockResolvedValue({
      datasetId: 81,
      summaryJson: '{"fields":[{"name":"amount"}]}',
    });
    const wrapper = mountQuery();
    await flushPromises();

    await button(wrapper, '查看数据集摘要')?.trigger('click');
    await flushPromises();

    expect(getQueryDatasetSummary).toHaveBeenCalledWith({
      allowedFieldCodes: ['amount'],
      datasetId: 81,
      datasetVersionId: 3,
    });
    expect(wrapper.get('[data-testid="ai-query-summary-json"]').text()).toBe(
      '{\n  "fields": [\n    {\n      "name": "amount"\n    }\n  ]\n}',
    );
  });

  it('无权用户看不到提交入口，只看到授权提示', async () => {
    state.access.hasAccessByCodes.mockReturnValue(false);
    const wrapper = mountQuery();
    await flushPromises();

    expect(button(wrapper, '生成查询计划')).toBeUndefined();
    expect(button(wrapper, '查看数据集摘要')).toBeUndefined();
    expect(wrapper.text()).toContain('ai:query:plan');
    expect(wrapper.text()).toContain('ai:query:summary');
    expect(createQueryPlan).not.toHaveBeenCalled();
  });

  it('校验不通过不发请求', async () => {
    state.planApi.validate.mockResolvedValue({ valid: false });
    const wrapper = mountQuery();
    await flushPromises();

    await button(wrapper, '生成查询计划')?.trigger('click');
    await flushPromises();

    expect(createQueryPlan).not.toHaveBeenCalled();
  });

  it('后端拒绝时清空旧结果，不让上一份计划冒充本次结论', async () => {
    vi.mocked(createQueryPlan)
      .mockResolvedValueOnce({ kind: 'PLAN', planJson: '{"stale":true}' })
      .mockRejectedValueOnce(new Error('AI_QUERY_DATASET_NOT_PUBLISHED'));
    const wrapper = mountQuery();
    await flushPromises();

    await button(wrapper, '生成查询计划')?.trigger('click');
    await flushPromises();
    expect(wrapper.find('[data-testid="ai-query-plan-json"]').exists()).toBe(
      true,
    );

    await button(wrapper, '生成查询计划')?.trigger('click');
    await flushPromises();

    expect(wrapper.find('[data-testid="ai-query-plan-json"]').exists()).toBe(
      false,
    );
    expect(
      wrapper.get('[data-testid="ai-query-plan-failure"]').text(),
    ).toContain('未返回任何结果');
    expect(showRequestError).toHaveBeenCalledWith(
      expect.any(Error),
      '查询计划生成失败',
    );
  });
});

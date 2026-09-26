import { flushPromises, mount } from '@vue/test-utils';

import { beforeEach, describe, expect, it, vi } from 'vitest';

const api = vi.hoisted(() => ({
  getApplicationPage: vi.fn(),
  getQuotaActive: vi.fn(),
  getUsagePage: vi.fn(),
  getUsageServiceSummary: vi.fn(),
  getUsageSummary: vi.fn(),
}));

vi.mock('#/api/ai/observability/usage', () => ({
  getQuotaActive: api.getQuotaActive,
  getUsagePage: api.getUsagePage,
  getUsageServiceSummary: api.getUsageServiceSummary,
  getUsageSummary: api.getUsageSummary,
}));

vi.mock('#/api/ai/application', () => ({
  getApplicationPage: api.getApplicationPage,
}));

vi.mock('@vben/common-ui', async () => {
  const { defineComponent, h } = await import('vue');
  return {
    Page: defineComponent({
      name: 'PageStub',
      setup(_props, { slots }) {
        return () => h('main', { 'data-testid': 'page' }, slots.default?.());
      },
    }),
  };
});

const { default: UsageIndex } = await import('./index.vue');

function usageRow(overrides: Record<string, unknown> = {}) {
  return {
    applicationId: 1,
    durationMs: 88,
    endpointRef: 'endpoint:3',
    inputTokens: null,
    invocationId: 'inv_1',
    modelRef: 'qwen-plus',
    modelRevision: 2,
    occurredAt: '2026-09-26T10:00:00',
    outputTokens: null,
    runId: 9,
    serviceId: 4,
    status: 'SUCCEEDED',
    usageSource: 'UNKNOWN',
    ...overrides,
  };
}

/** ElSelect 根节点是 div：测试通过组件事件驱动 v-model 与 change，而不是 setValue。 */
function emitSelect(
  wrapper: ReturnType<typeof mount>,
  selector: string,
  value: unknown,
): void {
  const select = wrapper.findComponent(selector) as unknown as {
    vm: { $emit: (event: string, payload: unknown) => void };
  };
  select.vm.$emit('update:modelValue', value);
  select.vm.$emit('change', value);
}

async function mountPage() {
  const wrapper = mount(UsageIndex);
  await flushPromises();
  return wrapper;
}

describe('用量与限额页（Q03）', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    api.getApplicationPage.mockResolvedValue({
      list: [{ appCode: 'crm-portal', id: 1, name: 'CRM 门户' }],
      total: 1,
    });
    api.getUsagePage.mockResolvedValue({
      list: [
        usageRow(),
        usageRow({ invocationId: 'inv_2', usageSource: 'REPORTED' }),
      ],
      total: 42,
    });
    api.getUsageSummary.mockResolvedValue({
      sources: [
        {
          inputTokens: 100,
          invocationCount: 1,
          outputTokens: 20,
          usageSource: 'REPORTED',
        },
        {
          inputTokens: 0,
          invocationCount: 1,
          outputTokens: 0,
          usageSource: 'UNKNOWN',
        },
      ],
      unknownInvocations: 1,
    });
    api.getUsageServiceSummary.mockResolvedValue([
      { inputTokens: 100, invocationCount: 2, outputTokens: 20, serviceId: 4 },
    ]);
    api.getQuotaActive.mockResolvedValue(2);
  });

  it('挂载即读取应用与用量，未知用量显示「未知」而不是 0', async () => {
    const wrapper = await mountPage();

    expect(api.getApplicationPage).toHaveBeenCalled();
    expect(api.getUsagePage).toHaveBeenCalled();
    expect(wrapper.get('[data-testid="ai-usage-headline"]').text()).toContain(
      '来源未知 1 次',
    );
    expect(wrapper.text()).toContain('未知（上游未返回）');
    expect(wrapper.text()).toContain('上游报告');
    expect(wrapper.text()).toContain('当前有效占位 2');
    // 未知 token 不能在界面里变成 0
    const unknownRow = wrapper
      .findAll('tr')
      .find((row) => row.text().includes('未知（上游未返回）'));
    expect(unknownRow?.text()).toContain('未知');
  });

  it('筛选与时间窗都交给服务端（from/to 为本地 ISO，不带时区后缀）', async () => {
    const wrapper = await mountPage();
    api.getUsagePage.mockClear();

    emitSelect(wrapper, '[data-testid="ai-usage-window"]', 30);
    await flushPromises();

    const params = api.getUsagePage.mock.calls[0]?.[0] as
      | undefined
      | { from: string; pageNo: number; to: string };
    expect(params?.pageNo).toBe(1);
    expect(params?.from).toMatch(/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}$/);
    expect(params?.to).toMatch(/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}$/);
    const from = params?.from ?? '';
    const to = params?.to ?? '';
    expect(from < to).toBe(true);
    expect(wrapper.text()).toContain('最近 30 天');
  });

  it('翻页把新页码带给服务端（不在本地切页）', async () => {
    const wrapper = await mountPage();
    api.getUsagePage.mockClear();

    const { ElPagination } = await import('element-plus');
    wrapper.findComponent(ElPagination).vm.$emit('current-change', 3);
    await flushPromises();

    const params = api.getUsagePage.mock.calls[0]?.[0] as
      | undefined
      | { pageNo: number };
    expect(params?.pageNo).toBe(3);
  });

  it('读取失败给出含权限码的提示（不伪装成功）', async () => {
    api.getUsageSummary.mockRejectedValue(new Error('forbidden'));
    const wrapper = await mountPage();

    expect(wrapper.get('[data-testid="ai-usage-error"]').text()).toContain(
      'ai:usage:query',
    );
  });
});

import { flushPromises, mount } from '@vue/test-utils';

import { beforeEach, describe, expect, it, vi } from 'vitest';

const api = vi.hoisted(() => ({
  getApplicationPage: vi.fn(),
  getRunDetail: vi.fn(),
  getRunPage: vi.fn(),
  getRunTimeline: vi.fn(),
  retryRun: vi.fn(),
}));

const access = vi.hoisted(() => ({
  hasAccessByCodes: vi.fn<(codes: string[]) => boolean>(),
}));

vi.mock('#/api/ai/observability', () => ({
  getRunDetail: api.getRunDetail,
  getRunPage: api.getRunPage,
  getRunTimeline: api.getRunTimeline,
  retryRun: api.retryRun,
}));

vi.mock('#/api/ai/application', () => ({
  getApplicationPage: api.getApplicationPage,
}));

vi.mock('@vben/access', () => ({
  useAccess: () => ({ hasAccessByCodes: access.hasAccessByCodes }),
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

const { default: ObservabilityIndex } = await import('./index.vue');

function runRow(overrides: Record<string, unknown> = {}) {
  return {
    applicationId: 1,
    attemptCount: 3,
    createTime: '2026-09-26T10:00:00',
    lastErrorCode: 'STEP_BUDGET_EXCEEDED',
    latestSeq: 4,
    releaseId: 7,
    retryBlockedReason: undefined,
    retryable: true,
    runId: 21,
    runKey: 'run_abc',
    serviceId: 4,
    status: 'FAILED',
    stepCount: 2,
    subjectType: 'USER',
    taskStatus: 'FAILED',
    updateTime: '2026-09-26T10:01:00',
    ...overrides,
  };
}

function runDetail(overrides: Record<string, unknown> = {}) {
  return {
    ...runRow(),
    contentHash: 'a'.repeat(64),
    conversationId: 5,
    dataLevel: 'L2_INTERNAL',
    endpointConfigRevision: 2,
    modelEndpointId: 3,
    resultDigest: undefined,
    resultMessageId: undefined,
    runVersion: 6,
    taskId: 33,
    taskKind: 'RUN_STEP',
    timing: {
      modelDurationMs: 900,
      modelInvocationCount: 2,
      totalDurationMs: 5000,
      unknownUsageCount: 1,
      unmeasuredStages: ['RETRIEVAL', 'BUSINESS_API'],
    },
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
  const wrapper = mount(ObservabilityIndex);
  await flushPromises();
  return wrapper;
}

describe('运行监控页（Q03）', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    access.hasAccessByCodes.mockReturnValue(true);
    api.getApplicationPage.mockResolvedValue({
      list: [{ appCode: 'crm-portal', id: 1, name: 'CRM 门户' }],
      total: 1,
    });
    api.getRunPage.mockResolvedValue({
      list: [
        runRow(),
        runRow({
          retryBlockedReason: '结果未知（UNKNOWN），请核对后新建运行',
          retryable: false,
          runId: 22,
          runKey: 'run_unknown',
          status: 'FAILED',
          taskStatus: 'UNKNOWN',
        }),
      ],
      total: 21,
    });
    api.getRunDetail.mockResolvedValue(runDetail());
    api.getRunTimeline.mockResolvedValue([
      {
        blockPresent: false,
        createTime: '2026-09-26T10:00:00',
        schemaVersion: '1.0',
        seq: 1,
        status: 'QUEUED',
      },
      {
        blockPresent: true,
        blockType: 'table',
        createTime: '2026-09-26T10:01:00',
        schemaVersion: '1.0',
        seq: 2,
        status: 'SUCCEEDED',
      },
    ]);
    api.retryRun.mockResolvedValue(true);
  });

  it('挂载即按服务端筛选读取运行，并区分可重试与结果未知', async () => {
    const wrapper = await mountPage();

    expect(api.getRunPage).toHaveBeenCalled();
    expect(wrapper.text()).toContain('run_abc');
    expect(wrapper.text()).toContain('失败（可人工重试）');
    expect(wrapper.text()).toContain('可重试');
    expect(wrapper.text()).toContain('结果未知（需人工核对后新建运行）');
    expect(wrapper.text()).toContain('超出执行步数预算');
  });

  it('筛选与时间窗交给服务端，翻页带新页码', async () => {
    const wrapper = await mountPage();
    api.getRunPage.mockClear();

    emitSelect(wrapper, '[data-testid="ai-observability-status"]', 'FAILED');
    await flushPromises();

    const params = api.getRunPage.mock.calls[0]?.[0] as
      | undefined
      | { pageNo: number; status: string };
    expect(params?.pageNo).toBe(1);
    expect(params?.status).toBe('FAILED');

    const { ElPagination } = await import('element-plus');
    api.getRunPage.mockClear();
    wrapper.findComponent(ElPagination).vm.$emit('current-change', 2);
    await flushPromises();
    const paged = api.getRunPage.mock.calls[0]?.[0] as
      | undefined
      | { pageNo: number };
    expect(paged?.pageNo).toBe(2);
  });

  it('无重试权限时不显示重试按钮，也不隐藏查看能力', async () => {
    access.hasAccessByCodes.mockReturnValue(false);
    const wrapper = await mountPage();

    const buttons = wrapper.findAll('button').map((button) => button.text());
    expect(buttons).not.toContain('重试');
    expect(buttons).toContain('详情');
    expect(wrapper.text()).toContain('ai:observability:retry');
  });

  it('重试要求先读详情里的运行版本，确认后带着版本提交并刷新列表', async () => {
    const wrapper = await mountPage();

    const retry = wrapper
      .findAll('button')
      .find((button) => button.text() === '重试');
    await retry?.trigger('click');
    await flushPromises();

    expect(api.getRunDetail).toHaveBeenCalledWith(21);
    const before = api.getRunPage.mock.calls.length;
    await wrapper
      .get('[data-testid="ai-observability-retry-confirm"]')
      .trigger('click');
    await flushPromises();

    expect(api.retryRun).toHaveBeenCalledWith({ runId: 21, version: 6 });
    expect(api.getRunPage.mock.calls.length).toBeGreaterThan(before);
    expect(
      wrapper.get('[data-testid="ai-observability-notice"]').text(),
    ).toContain('已重试 run_abc');
  });

  it('重试被拒时给出可核对的原因，不伪装成功', async () => {
    api.retryRun.mockRejectedValue(new Error('not retryable'));
    const wrapper = await mountPage();

    const retry = wrapper
      .findAll('button')
      .find((button) => button.text() === '重试');
    await retry?.trigger('click');
    await flushPromises();
    await wrapper
      .get('[data-testid="ai-observability-retry-confirm"]')
      .trigger('click');
    await flushPromises();

    expect(
      wrapper.get('[data-testid="ai-observability-error"]').text(),
    ).toContain('重试被拒绝');
  });

  it('详情展示耗时分解（模型实测 + 检索未单独计量）与不含正文的时间线', async () => {
    const wrapper = await mountPage();

    const detailButton = wrapper
      .findAll('button')
      .find((button) => button.text() === '详情');
    await detailButton?.trigger('click');
    await flushPromises();

    expect(api.getRunTimeline).toHaveBeenCalledWith({ limit: 100, runId: 21 });
    const timingTable = wrapper.get('[data-testid="ai-observability-timing"]');
    expect(timingTable.text()).toContain('900 ms');
    expect(timingTable.text()).toContain('未单独计量');
    expect(wrapper.text()).toContain('事件时间线（2 条；正文不在列表返回）');
    expect(wrapper.text()).toContain('是（受权接口读取）');
    expect(
      wrapper.get('[data-testid="ai-observability-retry-state"]').text(),
    ).toContain('可重试：是');
  });
});

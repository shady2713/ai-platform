import { flushPromises, mount } from '@vue/test-utils';

import { beforeEach, describe, expect, it, vi } from 'vitest';

const api = vi.hoisted(() => ({
  createCase: vi.fn(),
  createSuite: vi.fn(),
  deleteCase: vi.fn(),
  freezeSuite: vi.fn(),
  getApplicationPage: vi.fn(),
  getReport: vi.fn(),
  getResultPage: vi.fn(),
  getRun: vi.fn(),
  getRunPage: vi.fn(),
  getSuitePage: vi.fn(),
  listCases: vi.fn(),
  listResults: vi.fn(),
  newSuiteRevision: vi.fn(),
  reviewResult: vi.fn(),
  startRun: vi.fn(),
  updateCase: vi.fn(),
  updateSuite: vi.fn(),
}));

const access = vi.hoisted(() => ({
  hasAccessByCodes: vi.fn<(codes: string[]) => boolean>(),
}));

vi.mock('#/api/ai/evaluation', () => ({ ...api }));

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

const { default: EvaluationIndex } = await import('./index.vue');

const ALL_PERMISSIONS = [
  'ai:eval:manage',
  'ai:eval:query',
  'ai:eval:review',
  'ai:eval:run',
];
const granted = new Set<string>();

function suiteRow(overrides: Record<string, unknown> = {}) {
  return {
    applicationId: 1,
    caseCount: 2,
    code: 'crm-smoke',
    contentDigest: 'a'.repeat(64),
    createTime: '2026-09-26T09:00:00',
    dataLevel: 'L2_INTERNAL',
    description: '冒烟集',
    externalUserId: 'eval-bot',
    frozenTime: undefined,
    id: 11,
    name: 'CRM 冒烟',
    revision: 1,
    serviceId: 4,
    status: 'DRAFT',
    subjectType: 'APP',
    version: 3,
    ...overrides,
  };
}

function caseRow(overrides: Record<string, unknown> = {}) {
  return {
    caseKey: 'money-round',
    checksJson: '[{"kind":"MONEY","path":"total","expect":"100.00"}]',
    expectVersion: 'endpoint-rev-2',
    id: 21,
    needsReview: true,
    question: '合计是多少？',
    severity: 'BLOCKER',
    suiteId: 11,
    title: '金额四舍五入',
    version: 5,
    ...overrides,
  };
}

function runRow(overrides: Record<string, unknown> = {}) {
  return {
    applicationId: 1,
    caseTotal: 4,
    errorCount: 1,
    failedCount: 2,
    finishedTime: '2026-09-26T10:05:00',
    id: 31,
    passedCount: 1,
    serviceId: 4,
    startedTime: '2026-09-26T10:00:00',
    status: 'COMPLETED',
    suiteDigest: 'b'.repeat(64),
    suiteId: 11,
    suiteRevision: 2,
    summaryJson: '{"cases":[{"caseKey":"money-round"}]}',
    ...overrides,
  };
}

function resultRow(overrides: Record<string, unknown> = {}) {
  return {
    caseDigest: 'c'.repeat(64),
    caseId: 21,
    caseKey: 'money-round',
    expectVersion: 'endpoint-rev-2',
    failureCode: undefined,
    id: 41,
    observedVersion: 'endpoint-rev-2',
    resultDigest: 'd'.repeat(64),
    reviewNote: undefined,
    reviewStatus: 'NOT_REQUIRED',
    reviewedBy: undefined,
    reviewedTime: undefined,
    runId: 31,
    runRef: 99,
    severity: 'BLOCKER',
    status: 'PASSED',
    verdictJson: JSON.stringify([
      {
        expected: '100.00',
        index: 0,
        kind: 'MONEY',
        message: '金额相符',
        observed: '100.00',
        path: 'total',
        passed: true,
      },
    ]),
    ...overrides,
  };
}

function dateVerdict(passed: boolean) {
  return JSON.stringify([
    {
      expected: '2026-09-01',
      index: 0,
      kind: 'DATE',
      message: passed ? '日期相符' : '日期不符',
      observed: passed ? '2026-09-01' : '2026-08-31',
      path: 'dueDate',
      passed,
    },
  ]);
}

/** ElSelect 根节点是 div：用组件事件驱动 v-model 与 change，而不是 setValue。 */
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

function buttonWithText(wrapper: ReturnType<typeof mount>, text: string) {
  return wrapper.findAll('button').find((button) => button.text() === text);
}

async function mountPage() {
  // 对话框在测试里内联渲染（生产仍走 teleport + 遮罩），便于直接查询表单字段
  const wrapper = mount(EvaluationIndex, {
    global: {
      stubs: {
        ElDialog: { template: '<div><slot /><slot name="footer" /></div>' },
        teleport: true,
      },
    },
  });
  await flushPromises();
  return wrapper;
}

describe('评测控制面页面壳（Q05）', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    granted.clear();
    for (const code of ALL_PERMISSIONS) {
      granted.add(code);
    }
    access.hasAccessByCodes.mockImplementation((codes: string[]) =>
      codes.some((code) => granted.has(code)),
    );

    api.getApplicationPage.mockResolvedValue({
      list: [{ appCode: 'crm-portal', id: 1, name: 'CRM 门户' }],
      total: 1,
    });
    api.getSuitePage.mockResolvedValue({ list: [suiteRow()], total: 1 });
    api.listCases.mockResolvedValue([caseRow()]);
    api.getRunPage.mockResolvedValue({
      list: [
        runRow(),
        runRow({ errorCount: 1, failedCount: 1, id: 32, passedCount: 2 }),
      ],
      total: 2,
    });
    api.getRun.mockImplementation((id: number) =>
      Promise.resolve(runRow({ id })),
    );
    api.listResults.mockResolvedValue([
      resultRow(),
      resultRow({
        caseKey: 'date-tz',
        id: 42,
        reviewStatus: 'PENDING',
        severity: 'MAJOR',
        status: 'REVIEW_REQUIRED',
        verdictJson: dateVerdict(false),
      }),
    ]);
    api.getResultPage.mockResolvedValue({
      list: [
        resultRow({
          caseKey: 'date-tz',
          id: 42,
          reviewStatus: 'PENDING',
          severity: 'MAJOR',
          status: 'REVIEW_REQUIRED',
          verdictJson: dateVerdict(false),
        }),
      ],
      total: 1,
    });
    api.getReport.mockResolvedValue(
      '{"run":{"passedCount":1,"runId":31},"cases":[]}',
    );
    api.startRun.mockResolvedValue(41);
    api.createSuite.mockResolvedValue(12);
    api.updateSuite.mockResolvedValue(true);
    api.freezeSuite.mockResolvedValue(true);
    api.newSuiteRevision.mockResolvedValue(true);
    api.createCase.mockResolvedValue(22);
    api.updateCase.mockResolvedValue(true);
    api.deleteCase.mockResolvedValue(true);
    api.reviewResult.mockResolvedValue(true);
  });

  it('挂载即按应用读取套件、样例与运行（筛选都交给服务端）', async () => {
    const wrapper = await mountPage();

    expect(api.getApplicationPage).toHaveBeenCalledWith({
      pageNo: 1,
      pageSize: 100,
    });
    expect(api.getSuitePage).toHaveBeenCalledWith({
      applicationId: 1,
      pageNo: 1,
      pageSize: 20,
    });
    expect(api.listCases).toHaveBeenCalledWith(11);
    expect(api.getRunPage).toHaveBeenCalledWith({
      pageNo: 1,
      pageSize: 20,
      suiteId: 11,
    });

    expect(wrapper.get('[data-testid="ai-eval-suite-table"]').text()).toContain(
      'crm-smoke',
    );
    expect(wrapper.get('[data-testid="ai-eval-case-table"]').text()).toContain(
      '金额四舍五入',
    );
    const runTable = wrapper.get('[data-testid="ai-eval-run-table"]');
    expect(runTable.text()).toContain('修订 2 · bbbbbbbbbbbb…');
    expect(runTable.text()).toContain('1 / 2 / 1 / 4');
  });

  it('套件状态筛选与翻页都由服务端执行', async () => {
    const wrapper = await mountPage();
    api.getSuitePage.mockClear();

    emitSelect(wrapper, '[data-testid="ai-eval-suite-status"]', 'FROZEN');
    await flushPromises();
    expect(api.getSuitePage).toHaveBeenCalledWith({
      applicationId: 1,
      pageNo: 1,
      pageSize: 20,
      status: 'FROZEN',
    });

    api.getSuitePage.mockClear();
    const { ElPagination } = await import('element-plus');
    wrapper
      .findAllComponents(ElPagination)
      .at(0)
      ?.vm.$emit('current-change', 2);
    await flushPromises();
    expect(api.getSuitePage).toHaveBeenCalledWith({
      applicationId: 1,
      pageNo: 2,
      pageSize: 20,
      status: 'FROZEN',
    });
  });

  it('冻结套件只读：套件编辑/冻结与样例增删改按钮消失，只留新建修订', async () => {
    api.getSuitePage.mockResolvedValue({
      list: [suiteRow({ status: 'FROZEN' })],
      total: 1,
    });
    const wrapper = await mountPage();

    const buttons = wrapper.findAll('button').map((button) => button.text());
    expect(buttons).not.toContain('编辑');
    expect(buttons).not.toContain('冻结');
    expect(buttons).not.toContain('删除');
    expect(buttons).toContain('新建修订');
    // 对话框 footer 的提交按钮在测试桩里始终渲染，因此按表单入口的 testid 断言
    expect(wrapper.find('[data-testid="ai-eval-case-create"]').exists()).toBe(
      false,
    );
    expect(
      wrapper.get('[data-testid="ai-eval-suite-frozen-hint"]').text(),
    ).toContain('套件已冻结（修订 1）');

    await buttonWithText(wrapper, '新建修订')?.trigger('click');
    await flushPromises();
    expect(api.newSuiteRevision).toHaveBeenCalledWith({ id: 11, version: 3 });
    expect(wrapper.get('[data-testid="ai-eval-notice"]').text()).toContain(
      '已创建新修订',
    );
  });

  it('创建套件：标识、服务编号、主体与分级随请求下发', async () => {
    const wrapper = await mountPage();
    await wrapper.get('[data-testid="ai-eval-suite-create"]').trigger('click');

    await wrapper
      .get('[data-testid="ai-eval-suite-code"]')
      .setValue('order-flow');
    await wrapper
      .get('[data-testid="ai-eval-suite-name"]')
      .setValue('订单流程');
    await wrapper
      .get('[data-testid="ai-eval-suite-description"]')
      .setValue('回归集');
    await wrapper.get('[data-testid="ai-eval-suite-service"]').setValue('7');
    await wrapper
      .get('[data-testid="ai-eval-suite-external-user"]')
      .setValue('eval-bot');
    emitSelect(wrapper, '[data-testid="ai-eval-suite-subject"]', 'USER');
    emitSelect(
      wrapper,
      '[data-testid="ai-eval-suite-data-level"]',
      'L1_PUBLIC',
    );
    await wrapper.get('[data-testid="ai-eval-suite-submit"]').trigger('click');
    await flushPromises();

    expect(api.createSuite).toHaveBeenCalledWith({
      applicationId: 1,
      code: 'order-flow',
      dataLevel: 'L1_PUBLIC',
      description: '回归集',
      externalUserId: 'eval-bot',
      name: '订单流程',
      serviceId: 7,
      subjectType: 'USER',
    });
    expect(wrapper.get('[data-testid="ai-eval-notice"]').text()).toContain(
      '已创建',
    );
  });

  it('编辑套件只提交名称/说明/分级与乐观锁版本', async () => {
    const wrapper = await mountPage();
    await buttonWithText(wrapper, '编辑')?.trigger('click');
    await wrapper
      .get('[data-testid="ai-eval-suite-name"]')
      .setValue('CRM 冒烟 v2');
    await wrapper.get('[data-testid="ai-eval-suite-submit"]').trigger('click');
    await flushPromises();

    expect(api.updateSuite).toHaveBeenCalledWith({
      dataLevel: 'L2_INTERNAL',
      description: '冒烟集',
      id: 11,
      name: 'CRM 冒烟 v2',
      version: 3,
    });
  });

  it('选定运行：运行详情只读一次，结果与复核面板按该运行加载', async () => {
    const wrapper = await mountPage();
    api.getRun.mockClear();
    api.listResults.mockClear();
    api.getResultPage.mockClear();

    await buttonWithText(wrapper, '查看结果')?.trigger('click');
    await flushPromises();

    expect(api.getRun).toHaveBeenCalledTimes(1);
    expect(api.getRun).toHaveBeenCalledWith(31);
    expect(api.listResults).toHaveBeenCalledWith(31);
    expect(api.getResultPage).toHaveBeenCalledWith({
      pageNo: 1,
      pageSize: 20,
      runId: 31,
    });

    expect(wrapper.get('[data-testid="ai-eval-counting"]').text()).toContain(
      '通过 1（只计 PASSED）',
    );
    expect(
      wrapper.get('[data-testid="ai-eval-results-table"]').text(),
    ).toContain('date-tz');
  });

  it('只读账号：所有变更按钮消失，提示缺哪个权限码（查看能力保留）', async () => {
    granted.clear();
    granted.add('ai:eval:query');
    const wrapper = await mountPage();
    await buttonWithText(wrapper, '查看结果')?.trigger('click');
    await flushPromises();

    const buttons = wrapper.findAll('button').map((button) => button.text());
    expect(buttons).not.toContain('新建套件');
    expect(buttons).not.toContain('开始评测');
    expect(buttons).not.toContain('冻结');
    expect(buttons).toContain('查看结果');
    expect(buttons).toContain('比较两个运行');
    expect(wrapper.find('[data-testid="ai-eval-case-create"]').exists()).toBe(
      false,
    );
    expect(wrapper.find('[data-testid="ai-eval-suite-create"]').exists()).toBe(
      false,
    );
    expect(wrapper.find('[data-testid="ai-eval-run-start"]').exists()).toBe(
      false,
    );
    expect(wrapper.get('[data-testid="ai-eval-manage-hint"]').text()).toContain(
      'ai:eval:manage',
    );
    expect(wrapper.get('[data-testid="ai-eval-run-hint"]').text()).toContain(
      'ai:eval:run',
    );
    expect(wrapper.get('[data-testid="ai-eval-review-hint"]').text()).toContain(
      'ai:eval:review',
    );
    // 无复核权限时也不显示通过/否决
    expect(
      wrapper.findAll('button').map((button) => button.text()),
    ).not.toContain('通过');
  });

  it('读取失败给出含权限码的提示，不伪装成功', async () => {
    api.getSuitePage.mockRejectedValue(new Error('forbidden'));
    const wrapper = await mountPage();
    expect(wrapper.get('[data-testid="ai-eval-error"]').text()).toContain(
      'ai:eval:query',
    );

    const wrapper2 = await mountPage();
    api.getRun.mockRejectedValue(new Error('forbidden'));
    await buttonWithText(wrapper2, '查看结果')?.trigger('click');
    await flushPromises();
    expect(wrapper2.get('[data-testid="ai-eval-error"]').text()).toContain(
      'ai:eval:query',
    );
  });
});

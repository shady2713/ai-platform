import { flushPromises, mount } from '@vue/test-utils';

import { beforeEach, describe, expect, it, vi } from 'vitest';

const api = vi.hoisted(() => ({
  createCase: vi.fn(),
  deleteCase: vi.fn(),
  getReport: vi.fn(),
  getResultPage: vi.fn(),
  getRun: vi.fn(),
  getRunPage: vi.fn(),
  listCases: vi.fn(),
  listResults: vi.fn(),
  reviewResult: vi.fn(),
  startRun: vi.fn(),
  updateCase: vi.fn(),
}));

vi.mock('#/api/ai/evaluation', () => ({ ...api }));

const { default: SamplePanel } = await import('./SamplePanel.vue');
const { default: RunComparePanel } = await import('./RunComparePanel.vue');
const { default: FailurePanel } = await import('./FailurePanel.vue');
const { default: ReviewPanel } = await import('./ReviewPanel.vue');
const { default: ReportPanel } = await import('./ReportPanel.vue');

function suiteRow(overrides: Record<string, unknown> = {}) {
  return {
    applicationId: 1,
    caseCount: 2,
    code: 'crm-smoke',
    contentDigest: 'a'.repeat(64),
    dataLevel: 'L2_INTERNAL',
    description: '冒烟集',
    externalUserId: 'eval-bot',
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

function lastFeedback(wrapper: ReturnType<typeof mount>) {
  const events = wrapper.emitted('feedback') ?? [];
  return events.at(-1)?.[0] as undefined | { kind: string; message: string };
}

/** 面板直接挂载：对话框在测试里内联渲染（生产仍走 teleport + 遮罩） */
function mountPanel(
  component: Parameters<typeof mount>[0],
  props: Record<string, unknown>,
) {
  return mount(component, {
    props,
    global: {
      stubs: {
        ElDialog: { template: '<div><slot /><slot name="footer" /></div>' },
        teleport: true,
      },
    },
  });
}

describe('评测控制面面板（Q05）', () => {
  beforeEach(() => {
    vi.clearAllMocks();
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
    api.listResults.mockImplementation((runId: number) =>
      Promise.resolve(
        runId === 31
          ? [
              resultRow(),
              resultRow({
                caseKey: 'date-tz',
                id: 42,
                reviewStatus: 'PENDING',
                severity: 'MAJOR',
                status: 'REVIEW_REQUIRED',
                verdictJson: dateVerdict(false),
              }),
              resultRow({
                caseKey: 'structure-out',
                failureCode: 'AI_EVAL_EXECUTION_FAILED',
                id: 43,
                reviewStatus: 'PENDING',
                severity: 'MAJOR',
                status: 'ERROR',
                verdictJson: undefined,
              }),
              resultRow({
                caseKey: 'broken-verdict',
                id: 44,
                severity: 'MINOR',
                status: 'FAILED',
                verdictJson: '{bad',
              }),
            ]
          : [
              resultRow({ id: 51 }),
              resultRow({
                caseKey: 'date-tz',
                failureCode: 'AI_EVAL_EXECUTION_FAILED',
                id: 52,
                severity: 'MAJOR',
                status: 'FAILED',
                verdictJson: dateVerdict(true),
              }),
              resultRow({
                caseKey: 'only-target',
                failureCode: 'AI_EVAL_EXECUTION_FAILED',
                id: 53,
                status: 'ERROR',
                verdictJson: undefined,
              }),
            ],
      ),
    );
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
    api.createCase.mockResolvedValue(22);
    api.updateCase.mockResolvedValue(true);
    api.deleteCase.mockResolvedValue(true);
    api.reviewResult.mockResolvedValue(true);
  });

  it('样例面板：期望规则先校验 JSON 数组，非法不提交、合法按数组规范化提交', async () => {
    const wrapper = mountPanel(SamplePanel, {
      canManage: true,
      refreshKey: 0,
      suite: suiteRow(),
    });
    await flushPromises();
    expect(api.listCases).toHaveBeenCalledWith(11);

    await wrapper.get('[data-testid="ai-eval-case-create"]').trigger('click');
    await wrapper.get('[data-testid="ai-eval-case-key"]').setValue('value-42');
    await wrapper
      .get('[data-testid="ai-eval-case-title"]')
      .setValue('取值校验');
    await wrapper
      .get('[data-testid="ai-eval-case-question"]')
      .setValue('答案是多少？');
    await wrapper
      .get('[data-testid="ai-eval-case-checks"]')
      .setValue('{"kind":');
    await wrapper.get('[data-testid="ai-eval-case-submit"]').trigger('click');
    await flushPromises();

    expect(api.createCase).not.toHaveBeenCalled();
    expect(wrapper.get('[data-testid="ai-eval-case-error"]').text()).toContain(
      '不是合法 JSON',
    );

    await wrapper
      .get('[data-testid="ai-eval-case-checks"]')
      .setValue(' [ { "kind": "VALUE", "path": "answer", "expect": "42" } ] ');
    emitSelect(wrapper, '[data-testid="ai-eval-case-severity"]', 'MAJOR');
    await wrapper.get('[data-testid="ai-eval-case-submit"]').trigger('click');
    await flushPromises();

    expect(api.createCase).toHaveBeenCalledWith({
      caseKey: 'value-42',
      checksJson: '[{"kind":"VALUE","path":"answer","expect":"42"}]',
      expectVersion: undefined,
      needsReview: false,
      question: '答案是多少？',
      severity: 'MAJOR',
      suiteId: 11,
      title: '取值校验',
    });
    expect(api.listCases).toHaveBeenCalledTimes(2);
    expect(lastFeedback(wrapper)).toMatchObject({ kind: 'notice' });
    // 冻结样例数需要父页面按同一接口重读
    expect(wrapper.emitted('changed')).toHaveLength(1);
  });

  it('样例面板：编辑与删除都带乐观锁版本', async () => {
    const wrapper = mountPanel(SamplePanel, {
      canManage: true,
      refreshKey: 0,
      suite: suiteRow(),
    });
    await flushPromises();

    await wrapper
      .get('[data-testid="ai-eval-case-table"]')
      .findAll('button')
      .find((button) => button.text() === '编辑')
      ?.trigger('click');
    await wrapper
      .get('[data-testid="ai-eval-case-title"]')
      .setValue('金额四舍五入 v2');
    await wrapper.get('[data-testid="ai-eval-case-submit"]').trigger('click');
    await flushPromises();
    expect(api.updateCase).toHaveBeenCalledWith({
      caseKey: 'money-round',
      checksJson: '[{"kind":"MONEY","path":"total","expect":"100.00"}]',
      expectVersion: 'endpoint-rev-2',
      id: 21,
      needsReview: true,
      question: '合计是多少？',
      severity: 'BLOCKER',
      suiteId: 11,
      title: '金额四舍五入 v2',
      version: 5,
    });

    await wrapper
      .get('[data-testid="ai-eval-case-table"]')
      .findAll('button')
      .find((button) => button.text() === '删除')
      ?.trigger('click');
    await flushPromises();
    expect(api.deleteCase).toHaveBeenCalledWith({ id: 21, version: 5 });
    expect(lastFeedback(wrapper)?.message).toContain('已删除');
  });

  it('样例面板：冻结套件只读（无新增/编辑/删除，给冻结说明）', async () => {
    const wrapper = mountPanel(SamplePanel, {
      canManage: true,
      refreshKey: 0,
      suite: suiteRow({ status: 'FROZEN' }),
    });
    await flushPromises();

    const buttons = wrapper.findAll('button').map((button) => button.text());
    expect(buttons).not.toContain('编辑');
    expect(buttons).not.toContain('删除');
    expect(wrapper.find('[data-testid="ai-eval-case-create"]').exists()).toBe(
      false,
    );
    expect(
      wrapper.get('[data-testid="ai-eval-suite-frozen-hint"]').text(),
    ).toContain('套件已冻结（修订 1）');
  });

  it('运行面板：开始评测调用运行接口并刷新运行列表', async () => {
    const wrapper = mountPanel(RunComparePanel, {
      canRun: true,
      contextKey: 0,
      refreshKey: 0,
      suite: suiteRow(),
    });
    await flushPromises();
    expect(api.getRunPage).toHaveBeenCalledWith({
      pageNo: 1,
      pageSize: 20,
      suiteId: 11,
    });

    await wrapper.get('[data-testid="ai-eval-run-start"]').trigger('click');
    await flushPromises();

    expect(api.startRun).toHaveBeenCalledWith(11);
    expect(api.getRunPage).toHaveBeenCalledTimes(2);
    expect(lastFeedback(wrapper)?.message).toContain('已开始评测：运行 41');
  });

  it('运行面板：比较两个运行，按 caseKey 给出状态、摘要、失败码与判定变化', async () => {
    const wrapper = mountPanel(RunComparePanel, {
      canRun: false,
      contextKey: 0,
      refreshKey: 0,
      suite: suiteRow(),
    });
    await flushPromises();
    // 无 ai:eval:run 权限：只提示，不给按钮
    expect(wrapper.find('[data-testid="ai-eval-run-start"]').exists()).toBe(
      false,
    );
    expect(wrapper.get('[data-testid="ai-eval-run-hint"]').text()).toContain(
      'ai:eval:run',
    );

    const baseButton = wrapper
      .findAll('button')
      .find((button) => button.text() === '设为基准');
    const targetButtons = wrapper
      .findAll('button')
      .filter((button) => button.text() === '设为对比');
    await baseButton?.trigger('click');
    await targetButtons[1]?.trigger('click');
    await flushPromises();

    await wrapper.get('[data-testid="ai-eval-compare"]').trigger('click');
    await flushPromises();

    expect(api.getRun).toHaveBeenCalledWith(31);
    expect(api.getRun).toHaveBeenCalledWith(32);
    expect(api.listResults).toHaveBeenCalledWith(31);
    expect(api.listResults).toHaveBeenCalledWith(32);

    const head = wrapper.get('[data-testid="ai-eval-compare-head"]').text();
    expect(head).toContain('套件摘要 bbbbbbbbbbbb…');
    expect(head).toContain('冻结快照 1 例');
    expect(
      wrapper.get('[data-testid="ai-eval-diff-headline"]').text(),
    ).toContain('共比较 5 例：1 例有变化；仅基准运行 2 例、仅对比运行 1 例');

    const diffTable = wrapper.get('[data-testid="ai-eval-diff-table"]');
    expect(diffTable.text()).toContain('date-tz');
    expect(diffTable.text()).toContain('only-target');
    expect(diffTable.text()).toContain(
      '仅对比运行有此样例（两次冻结内容不同）',
    );
    expect(diffTable.text()).toContain(
      '状态 待复核（计入 failedCount，复核通过前不算通过） → 失败（计入 failedCount）',
    );
    expect(diffTable.text()).toContain('判定变化：DATE#0');
    expect(diffTable.text()).toContain('无变化');
  });

  it('运行面板：选定运行只把运行编号交给父页面（不自己读结果）', async () => {
    const wrapper = mountPanel(RunComparePanel, {
      canRun: true,
      contextKey: 0,
      refreshKey: 0,
      suite: suiteRow(),
    });
    await flushPromises();

    await wrapper
      .findAll('button')
      .find((button) => button.text() === '查看结果')
      ?.trigger('click');
    await flushPromises();

    expect(wrapper.emitted('selectRun')?.[0]).toStrictEqual([31]);
    expect(api.listResults).not.toHaveBeenCalled();
  });

  it('失败分类面板：计数口径、级别/规则类型/错误码聚合与无法解析的判定', async () => {
    const wrapper = mountPanel(FailurePanel, {
      refreshKey: 0,
      run: runRow(),
    });
    await flushPromises();

    expect(api.listResults).toHaveBeenCalledWith(31);
    expect(wrapper.get('[data-testid="ai-eval-counting"]').text()).toContain(
      '通过 1（只计 PASSED）',
    );
    expect(wrapper.get('[data-testid="ai-eval-counting"]').text()).toContain(
      '不能只用通过样例做分母',
    );
    expect(wrapper.get('[data-testid="ai-eval-count-check"]').text()).toBe(
      '计数核对：1 + 2 + 1 = 4，与样例总数一致',
    );
    expect(
      wrapper.get('[data-testid="ai-eval-pending-review"]').text(),
    ).toContain('2 例等待人工复核');
    expect(wrapper.get('[data-testid="ai-eval-unparsed"]').text()).toContain(
      '1 例判定 JSON 无法解析',
    );

    const severity = wrapper.get('[data-testid="ai-eval-failure-severity"]');
    expect(severity.text()).toContain('阻断级（BLOCKER）');
    expect(severity.text()).toContain('严重（MAJOR）');
    expect(severity.text()).toContain('轻微（MINOR）');

    const kinds = wrapper.get('[data-testid="ai-eval-failure-kind"]');
    expect(kinds.text()).toContain('日期');
    expect(kinds.text()).toContain('金额');

    const codes = wrapper.get('[data-testid="ai-eval-failure-code"]');
    expect(codes.text()).toContain('AI_EVAL_EXECUTION_FAILED');
    expect(codes.text()).toContain('执行未完成（未产生判定，不能按通过计）');

    const statuses = wrapper.get('[data-testid="ai-eval-failure-status"]');
    expect(statuses.text()).toContain(
      '待复核（计入 failedCount，复核通过前不算通过）',
    );
    expect(statuses.text()).toContain('错误（未能执行，计入 errorCount）');
  });

  it('失败分类面板：没有选定运行时只提示选择运行，不发请求', async () => {
    const wrapper = mountPanel(FailurePanel, { refreshKey: 0 });
    await flushPromises();

    expect(api.listResults).not.toHaveBeenCalled();
    expect(wrapper.text()).toContain('请先在运行列表点「查看结果」选择运行');
  });

  it('复核面板：只有 PENDING 有通过/否决，备注随请求提交并通知父页面刷新', async () => {
    const wrapper = mountPanel(ReviewPanel, {
      canReview: true,
      refreshKey: 0,
      run: runRow(),
    });
    await flushPromises();

    const table = wrapper.get('[data-testid="ai-eval-results-table"]');
    expect(table.text()).toContain('等待复核');
    expect(table.text()).toContain('1/1 条规则未通过（日期）');

    await table
      .findAll('button')
      .find((button) => button.text() === '通过')
      ?.trigger('click');
    await flushPromises();
    // 复核对话框里逐条判定与原文说明都要能看到（未通过不是通过）
    expect(wrapper.text()).toContain('日期不符');
    expect(wrapper.text()).toContain('未通过');
    await wrapper
      .get('[data-testid="ai-eval-review-note"]')
      .setValue('人工核对通过');
    api.getResultPage.mockClear();
    await wrapper
      .get('[data-testid="ai-eval-review-confirm"]')
      .trigger('click');
    await flushPromises();

    expect(api.reviewResult).toHaveBeenCalledWith({
      approve: true,
      note: '人工核对通过',
      resultId: 42,
    });
    expect(api.getResultPage).toHaveBeenCalled();
    expect(lastFeedback(wrapper)?.message).toContain('已通过 date-tz 的复核');
    expect(wrapper.emitted('reviewed')).toHaveLength(1);
  });

  it('复核面板：否决不带备注时只提交结论与结果编号', async () => {
    const wrapper = mountPanel(ReviewPanel, {
      canReview: true,
      refreshKey: 0,
      run: runRow(),
    });
    await flushPromises();

    await wrapper
      .get('[data-testid="ai-eval-results-table"]')
      .findAll('button')
      .find((button) => button.text() === '否决')
      ?.trigger('click');
    await flushPromises();
    await wrapper
      .get('[data-testid="ai-eval-review-confirm"]')
      .trigger('click');
    await flushPromises();

    expect(api.reviewResult).toHaveBeenCalledWith({
      approve: false,
      resultId: 42,
    });
    expect(lastFeedback(wrapper)?.message).toContain('已否决');
  });

  it('复核面板：无 ai:eval:review 权限时不显示按钮并说明权限码', async () => {
    const wrapper = mountPanel(ReviewPanel, {
      canReview: false,
      refreshKey: 0,
      run: runRow(),
    });
    await flushPromises();

    const table = wrapper.get('[data-testid="ai-eval-results-table"]');
    expect(
      table.findAll('button').map((button) => button.text()),
    ).not.toContain('通过');
    expect(table.text()).toContain('无复核权限');
    expect(wrapper.get('[data-testid="ai-eval-review-hint"]').text()).toContain(
      'ai:eval:review',
    );
  });

  it('复核面板：读取结果失败时抛出含权限码的提示', async () => {
    api.getResultPage.mockRejectedValue(new Error('forbidden'));
    const wrapper = mountPanel(ReviewPanel, {
      canReview: true,
      refreshKey: 0,
      run: runRow(),
    });
    await flushPromises();

    expect(lastFeedback(wrapper)).toMatchObject({ kind: 'error' });
    expect(lastFeedback(wrapper)?.message).toContain('ai:eval:query');
  });

  it('报告面板：只读格式化展示，注入串按文本显示且不产生 HTML 元素', async () => {
    api.getReport.mockResolvedValue(
      JSON.stringify({
        cases: [],
        note: '<img src=x onerror=alert(1)>',
        run: { passedCount: 1, runId: 31 },
      }),
    );
    const wrapper = mountPanel(ReportPanel, { run: runRow() });
    await flushPromises();

    await wrapper.get('[data-testid="ai-eval-report-load"]').trigger('click');
    await flushPromises();

    expect(api.getReport).toHaveBeenCalledWith(31);
    const pre = wrapper.get('[data-testid="ai-eval-report"]');
    expect(pre.text()).toContain('<img src=x onerror=alert(1)>');
    expect(pre.text()).toContain('\n  "run": {');
    expect(wrapper.element.querySelectorAll('img')).toHaveLength(0);
  });

  it('报告面板：不是合法 JSON 时按原文展示并标注（不吞内容）', async () => {
    api.getReport.mockResolvedValue('not-json-report');
    const wrapper = mountPanel(ReportPanel, { run: runRow() });
    await flushPromises();
    await wrapper.get('[data-testid="ai-eval-report-load"]').trigger('click');
    await flushPromises();

    expect(wrapper.get('[data-testid="ai-eval-report-raw"]').text()).toContain(
      '报告不是合法 JSON',
    );
    expect(wrapper.get('[data-testid="ai-eval-report"]').text()).toBe(
      'not-json-report',
    );
  });

  it('报告面板：未选运行时按钮禁用；读取失败提示含读取权限码', async () => {
    const idle = mountPanel(ReportPanel, {});
    await flushPromises();
    expect(
      idle.get('[data-testid="ai-eval-report-load"]').attributes('disabled'),
    ).toBeDefined();

    api.getReport.mockRejectedValue(new Error('forbidden'));
    const wrapper = mountPanel(ReportPanel, { run: runRow() });
    await flushPromises();
    await wrapper.get('[data-testid="ai-eval-report-load"]').trigger('click');
    await flushPromises();

    expect(lastFeedback(wrapper)?.message).toContain('ai:eval:query');
  });
});

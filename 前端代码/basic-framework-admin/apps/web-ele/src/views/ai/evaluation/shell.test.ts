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
const { default: ReviewPanel } = await import('./modules/ReviewPanel.vue');

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
    id: 31,
    passedCount: 1,
    serviceId: 4,
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
    id: 41,
    resultDigest: 'd'.repeat(64),
    reviewNote: undefined,
    reviewStatus: 'NOT_REQUIRED',
    runId: 31,
    severity: 'BLOCKER',
    status: 'PASSED',
    verdictJson: JSON.stringify([
      { index: 0, kind: 'MONEY', path: 'total', passed: true },
    ]),
    ...overrides,
  };
}

function pendingResult() {
  return resultRow({
    caseKey: 'date-tz',
    id: 42,
    reviewStatus: 'PENDING',
    severity: 'MAJOR',
    status: 'REVIEW_REQUIRED',
    verdictJson: JSON.stringify([
      { index: 0, kind: 'DATE', path: 'dueDate', passed: false },
    ]),
  });
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

describe('评测控制面页面壳编排分支（Q05）', () => {
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
    api.getRunPage.mockResolvedValue({ list: [runRow()], total: 1 });
    api.getRun.mockImplementation((id: number) =>
      Promise.resolve(runRow({ id })),
    );
    api.listResults.mockResolvedValue([resultRow(), pendingResult()]);
    api.getResultPage.mockResolvedValue({ list: [pendingResult()], total: 1 });
    api.getReport.mockResolvedValue('{"run":{"runId":31},"cases":[]}');
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

  it('读取应用列表失败：只给含权限码的提示，不再请求套件', async () => {
    api.getApplicationPage.mockRejectedValue(new Error('forbidden'));
    const wrapper = await mountPage();

    expect(wrapper.get('[data-testid="ai-eval-error"]').text()).toContain(
      'ai:application:query',
    );
    expect(api.getSuitePage).not.toHaveBeenCalled();
  });

  it('应用列表为空：applicationId 未定，套件请求不发', async () => {
    api.getApplicationPage.mockResolvedValue({ list: [], total: 0 });
    const wrapper = await mountPage();

    expect(api.getSuitePage).not.toHaveBeenCalled();
    expect(wrapper.text()).toContain('该应用下没有评测套件');
  });

  it('套件表单四个校验分支与保存失败提示（不把非法输入发出去）', async () => {
    api.createSuite.mockRejectedValue(new Error('duplicate'));
    const wrapper = await mountPage();
    await wrapper.get('[data-testid="ai-eval-suite-create"]').trigger('click');

    // 名称空
    await wrapper.get('[data-testid="ai-eval-suite-submit"]').trigger('click');
    await flushPromises();
    expect(wrapper.get('[data-testid="ai-eval-error"]').text()).toContain(
      '套件名称不能为空',
    );

    // 标识空
    await wrapper
      .get('[data-testid="ai-eval-suite-name"]')
      .setValue('订单流程');
    await wrapper.get('[data-testid="ai-eval-suite-submit"]').trigger('click');
    await flushPromises();
    expect(wrapper.get('[data-testid="ai-eval-error"]').text()).toContain(
      '套件标识不能为空',
    );

    // 服务编号不是正整数
    await wrapper
      .get('[data-testid="ai-eval-suite-code"]')
      .setValue('order-flow');
    await wrapper.get('[data-testid="ai-eval-suite-service"]').setValue('abc');
    await wrapper.get('[data-testid="ai-eval-suite-submit"]').trigger('click');
    await flushPromises();
    expect(wrapper.get('[data-testid="ai-eval-error"]').text()).toContain(
      '服务编号必须是正整数',
    );
    expect(api.createSuite).not.toHaveBeenCalled();

    // 服务端拒绝（标识重复）
    await wrapper.get('[data-testid="ai-eval-suite-service"]').setValue('7');
    await wrapper.get('[data-testid="ai-eval-suite-submit"]').trigger('click');
    await flushPromises();
    expect(api.createSuite).toHaveBeenCalledTimes(1);
    expect(wrapper.get('[data-testid="ai-eval-error"]').text()).toContain(
      'ai:eval:manage',
    );
  });

  it('没有应用时创建套件被拦下（applicationId 未定）', async () => {
    api.getApplicationPage.mockResolvedValue({ list: [], total: 0 });
    const wrapper = await mountPage();
    await wrapper.get('[data-testid="ai-eval-suite-create"]').trigger('click');
    await wrapper
      .get('[data-testid="ai-eval-suite-name"]')
      .setValue('订单流程');
    await wrapper
      .get('[data-testid="ai-eval-suite-code"]')
      .setValue('order-flow');
    await wrapper.get('[data-testid="ai-eval-suite-service"]').setValue('7');
    await wrapper.get('[data-testid="ai-eval-suite-submit"]').trigger('click');
    await flushPromises();

    expect(api.createSuite).not.toHaveBeenCalled();
    expect(wrapper.get('[data-testid="ai-eval-error"]').text()).toContain(
      '请先选择应用',
    );
  });

  it('编辑套件：缺省分级/说明/主体标识回退到表单默认值', async () => {
    api.getSuitePage.mockResolvedValue({
      list: [
        suiteRow({
          dataLevel: undefined,
          description: undefined,
          externalUserId: undefined,
        }),
      ],
      total: 1,
    });
    const wrapper = await mountPage();
    await buttonWithText(wrapper, '编辑')?.trigger('click');
    await wrapper.get('[data-testid="ai-eval-suite-submit"]').trigger('click');
    await flushPromises();

    expect(api.updateSuite).toHaveBeenCalledWith({
      dataLevel: 'L2_INTERNAL',
      description: undefined,
      id: 11,
      name: 'CRM 冒烟',
      version: 3,
    });
  });

  it('冻结套件：成功给出只读说明并重读套件；失败提示所需权限码', async () => {
    const wrapper = await mountPage();
    api.getSuitePage.mockClear();
    await buttonWithText(wrapper, '冻结')?.trigger('click');
    await flushPromises();

    expect(api.freezeSuite).toHaveBeenCalledWith({ id: 11, version: 3 });
    expect(api.getSuitePage).toHaveBeenCalledTimes(1);
    expect(wrapper.get('[data-testid="ai-eval-notice"]').text()).toContain(
      '已冻结',
    );

    api.freezeSuite.mockRejectedValue(new Error('no cases'));
    await buttonWithText(wrapper, '冻结')?.trigger('click');
    await flushPromises();
    expect(wrapper.get('[data-testid="ai-eval-error"]').text()).toContain(
      'ai:eval:manage',
    );
  });

  it('新建修订失败时给出可核对原因（含权限码）', async () => {
    api.getSuitePage.mockResolvedValue({
      list: [suiteRow({ status: 'FROZEN' })],
      total: 1,
    });
    api.newSuiteRevision.mockRejectedValue(new Error('not frozen'));
    const wrapper = await mountPage();

    await buttonWithText(wrapper, '新建修订')?.trigger('click');
    await flushPromises();
    expect(wrapper.get('[data-testid="ai-eval-error"]').text()).toContain(
      'ai:eval:manage',
    );
  });

  it('读取运行失败：提示含查询权限码，不显示结果面板', async () => {
    const wrapper = await mountPage();
    api.getRun.mockRejectedValue(new Error('forbidden'));
    await buttonWithText(wrapper, '查看结果')?.trigger('click');
    await flushPromises();

    expect(wrapper.get('[data-testid="ai-eval-error"]').text()).toContain(
      'ai:eval:query',
    );
    expect(wrapper.find('[data-testid="ai-eval-counting"]').exists()).toBe(
      false,
    );
  });

  it('面板 feedback：错误与成功都落到顶部一处告警，读取报告前会清掉旧告警', async () => {
    const wrapper = await mountPage();

    // notice 分支：设为基准（面板 emit feedback notice）
    await buttonWithText(wrapper, '设为基准')?.trigger('click');
    await flushPromises();
    expect(wrapper.get('[data-testid="ai-eval-notice"]').text()).toContain(
      '已把运行 31 设为基准运行',
    );

    // 报告要先选定运行（否则按钮禁用，不会发请求）
    await buttonWithText(wrapper, '查看结果')?.trigger('click');
    await flushPromises();

    // error 分支 + clearFeedback 分支：报告读取失败 → 再成功时旧错误被清掉
    api.getReport.mockRejectedValue(new Error('forbidden'));
    await wrapper.get('[data-testid="ai-eval-report-load"]').trigger('click');
    await flushPromises();
    expect(wrapper.get('[data-testid="ai-eval-error"]').text()).toContain(
      'ai:eval:query',
    );

    api.getReport.mockResolvedValue('{"run":{"runId":31},"cases":[]}');
    await wrapper.get('[data-testid="ai-eval-report-load"]').trigger('click');
    await flushPromises();
    expect(wrapper.find('[data-testid="ai-eval-error"]').exists()).toBe(false);
    expect(wrapper.get('[data-testid="ai-eval-report"]').text()).toContain(
      '"runId": 31',
    );
  });

  it('套件行「样例与运行」：清掉已选运行与比较选择，并重新拉样例/运行', async () => {
    const wrapper = await mountPage();
    await buttonWithText(wrapper, '查看结果')?.trigger('click');
    await buttonWithText(wrapper, '设为基准')?.trigger('click');
    await flushPromises();
    expect(wrapper.find('[data-testid="ai-eval-counting"]').exists()).toBe(
      true,
    );

    api.listCases.mockClear();
    api.getRunPage.mockClear();
    await buttonWithText(wrapper, '样例与运行')?.trigger('click');
    await flushPromises();

    expect(wrapper.find('[data-testid="ai-eval-counting"]').exists()).toBe(
      false,
    );
    expect(wrapper.find('[data-testid="ai-eval-base-tag"]').exists()).toBe(
      false,
    );
    expect(api.listCases).toHaveBeenCalledWith(11);
    expect(api.getRunPage).toHaveBeenCalledWith({
      pageNo: 1,
      pageSize: 20,
      suiteId: 11,
    });
  });

  it('样例面板 changed：只重读套件行（冻结样例数），不重读运行', async () => {
    const wrapper = await mountPage();
    api.getSuitePage.mockClear();
    api.getRunPage.mockClear();

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
      .setValue('[{"kind":"VALUE","path":"answer","expect":"42"}]');
    await wrapper.get('[data-testid="ai-eval-case-submit"]').trigger('click');
    await flushPromises();

    expect(api.createCase).toHaveBeenCalledTimes(1);
    expect(api.getSuitePage).toHaveBeenCalledTimes(1);
    expect(api.getRunPage).not.toHaveBeenCalled();
  });

  it('复核 reviewed：重读运行详情并把刷新信号发给结果/复核面板', async () => {
    const wrapper = await mountPage();
    await buttonWithText(wrapper, '查看结果')?.trigger('click');
    await flushPromises();
    expect(api.getRun).toHaveBeenCalledTimes(1);

    api.getRun.mockClear();
    api.listResults.mockClear();
    api.getResultPage.mockClear();
    await wrapper
      .get('[data-testid="ai-eval-results-table"]')
      .findAll('button')
      .find((button) => button.text() === '通过')
      ?.trigger('click');
    await flushPromises();
    await wrapper
      .get('[data-testid="ai-eval-review-confirm"]')
      .trigger('click');
    await flushPromises();

    expect(api.reviewResult).toHaveBeenCalledWith({
      approve: true,
      resultId: 42,
    });
    expect(api.getRun).toHaveBeenCalledWith(31);
    expect(api.listResults).toHaveBeenCalledWith(31);
    expect(api.getResultPage).toHaveBeenCalledWith({
      pageNo: 1,
      pageSize: 20,
      runId: 31,
    });
  });

  it('复核后重读运行详情失败：给出含权限码的提示，仍刷新其它面板', async () => {
    const wrapper = await mountPage();
    await buttonWithText(wrapper, '查看结果')?.trigger('click');
    await flushPromises();
    api.getRun.mockRejectedValue(new Error('forbidden'));

    await wrapper
      .get('[data-testid="ai-eval-results-table"]')
      .findAll('button')
      .find((button) => button.text() === '通过')
      ?.trigger('click');
    await flushPromises();
    api.getResultPage.mockClear();
    await wrapper
      .get('[data-testid="ai-eval-review-confirm"]')
      .trigger('click');
    await flushPromises();

    expect(api.reviewResult).toHaveBeenCalledTimes(1);
    expect(wrapper.get('[data-testid="ai-eval-error"]').text()).toContain(
      'ai:eval:query',
    );
    // 刷新信号照发：复核面板重新读了结果页
    expect(api.getResultPage).toHaveBeenCalledWith({
      pageNo: 1,
      pageSize: 20,
      runId: 31,
    });
  });

  it('未选运行时的 reviewed 事件直接早退（不发运行详情请求）', async () => {
    const wrapper = await mountPage();
    api.getRun.mockClear();

    wrapper.findComponent(ReviewPanel).vm.$emit('reviewed');
    await flushPromises();

    expect(api.getRun).not.toHaveBeenCalled();
  });

  it('切换应用：分页回第 1 页、套件选择与运行上下文一起重置', async () => {
    api.getApplicationPage.mockResolvedValue({
      list: [
        { appCode: 'crm-portal', id: 1, name: 'CRM 门户' },
        { appCode: 'order-hub', id: 2, name: '订单中心' },
      ],
      total: 2,
    });
    api.getSuitePage.mockImplementation((params: { applicationId?: number }) =>
      Promise.resolve({
        list: [suiteRow(params.applicationId === 2 ? { id: 12 } : {})],
        total: 1,
      }),
    );
    const wrapper = await mountPage();
    await buttonWithText(wrapper, '查看结果')?.trigger('click');
    await flushPromises();
    expect(wrapper.find('[data-testid="ai-eval-counting"]').exists()).toBe(
      true,
    );

    api.getSuitePage.mockClear();
    api.listCases.mockClear();
    emitSelect(wrapper, '[data-testid="ai-eval-application"]', 2);
    await flushPromises();

    expect(api.getSuitePage).toHaveBeenCalledWith({
      applicationId: 2,
      pageNo: 1,
      pageSize: 20,
    });
    expect(api.listCases).toHaveBeenCalledWith(12);
    expect(wrapper.find('[data-testid="ai-eval-counting"]').exists()).toBe(
      false,
    );
  });

  it('没有任何评测权限时：工具栏给出说明，页面只读', async () => {
    granted.clear();
    const wrapper = await mountPage();

    expect(wrapper.text()).toContain('当前账号没有');
    expect(wrapper.text()).toContain('套件、样例、运行与报告都不会返回');
    expect(wrapper.find('[data-testid="ai-eval-suite-create"]').exists()).toBe(
      false,
    );
  });
});

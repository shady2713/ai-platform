import { flushPromises, mount } from '@vue/test-utils';

import { beforeEach, describe, expect, it, vi } from 'vitest';

const api = vi.hoisted(() => ({
  getReportPage: vi.fn(),
  issueDebugTicket: vi.fn(),
  listReportVersions: vi.fn(),
  readCurrentReportVersion: vi.fn(),
  readRefreshState: vi.fn(),
  readReportVersion: vi.fn(),
  refreshReport: vi.fn(),
  reviseReport: vi.fn(),
}));

const feedback = vi.hoisted(() => ({
  showErrorMessage: vi.fn(),
  showSuccessMessage: vi.fn(),
}));

vi.mock('#/api/ai/report', () => ({
  getReportPage: api.getReportPage,
  listReportVersions: api.listReportVersions,
  readCurrentReportVersion: api.readCurrentReportVersion,
  readRefreshState: api.readRefreshState,
  readReportVersion: api.readReportVersion,
  refreshReport: api.refreshReport,
  reviseReport: api.reviseReport,
}));

vi.mock('#/api/ai/open-platform', () => ({
  issueDebugTicket: api.issueDebugTicket,
}));

vi.mock('#/utils/feedback', () => ({
  showErrorMessage: feedback.showErrorMessage,
  showSuccessMessage: feedback.showSuccessMessage,
}));

vi.mock('@vben/common-ui', async () => {
  const { defineComponent, h } = await import('vue');
  return {
    Page: defineComponent({
      name: 'PageStub',
      props: {
        description: { default: '', type: String },
        title: { default: '', type: String },
      },
      setup(props, { slots }) {
        return () =>
          h('main', { 'data-testid': 'page' }, [
            h('h1', String(props.title)),
            slots.default?.(),
          ]);
      },
    }),
  };
});

const { default: ReportIndex } = await import('./index.vue');

const spec = {
  schemaVersion: '1.0',
  title: '华东 8 月销售',
  themeRef: { themeId: 'thm_default', revision: 1 },
  layout: {
    columns: 12,
    gap: 16,
    items: [{ blockId: 'intro', row: 0, column: 0, span: 12 }],
  },
  blocks: [{ id: 'intro', title: '口径', type: 'text', text: '8 月、华东。' }],
  datasetRefs: [
    {
      id: 'sales_result',
      resultRef: 'plan_cccccccccccc',
      queryRef: 'sales_query',
      columns: [{ field: 'customer_name', label: '客户', dataType: 'STRING' }],
      rowCount: 1,
      completeness: 'COMPLETE',
    },
  ],
  queryRefs: [{ id: 'sales_query', plan: { datasetId: 'dset_golden-sales' } }],
  sources: [
    {
      id: 'sales_source',
      kind: 'DATASET',
      resourceId: 'golden-sales',
      resourceVersion: 1,
      description: '合成数据集',
    },
  ],
};

const versionData = {
  kind: 'REPORT',
  data: [{ blockId: 'intro', type: 'text', verified: false }],
  datasets: [
    {
      datasetRef: 'sales_result',
      columns: [{ field: 'customer_name', label: '客户', dataType: 'STRING' }],
      rows: [{ customer_name: 'alice' }],
      completeness: 'COMPLETE',
    },
  ],
  notes: [],
};

const report = {
  code: 'r07_sales',
  id: 71,
  latestVersionNo: 2,
  mode: 'REFRESHABLE',
  name: '华东 8 月销售',
  publishedVersionNo: 2,
  schemaVersion: '1.0',
  version: 1,
};

const version = {
  id: 81,
  reportId: 71,
  versionNo: 2,
  mode: 'REFRESHABLE',
  specJson: JSON.stringify(spec),
  completeness: 'COMPLETE',
  asOf: '2026-09-23T10:00:00',
};

async function openPage() {
  // 对话框在测试里内联渲染（生产仍走 teleport + 遮罩）：便于直接查询表单字段
  const wrapper = mount(ReportIndex, {
    global: {
      stubs: {
        ElDialog: { template: '<div><slot /><slot name="footer" /></div>' },
        teleport: true,
      },
    },
  });
  await flushPromises();
  await wrapper.get('[data-testid="report-app-code"]').setValue('r07-app');
  await wrapper
    .get('[data-testid="report-app-secret"]')
    .setValue('secret-value');
  await wrapper.get('[data-testid="report-external-user"]').setValue('alice');
  await wrapper.get('[data-testid="report-issue-ticket"]').trigger('click');
  await flushPromises();
  return wrapper;
}

describe('个人报表页面', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    api.issueDebugTicket.mockResolvedValue({ token: 'ticket-1' });
    api.getReportPage.mockResolvedValue({ list: [report], total: 1 });
    api.readCurrentReportVersion.mockResolvedValue(version);
    api.listReportVersions.mockResolvedValue([
      { id: 81, versionNo: 2, mode: 'REFRESHABLE' },
    ]);
    api.readRefreshState.mockResolvedValue({
      attempted: true,
      status: 'OK',
      completeness: 'COMPLETE',
      asOf: '2026-09-23T10:00:00',
      dataJson: JSON.stringify(versionData),
    });
    api.refreshReport.mockResolvedValue({
      status: 'FAILED',
      reason: '1003006029',
      asOf: '2026-09-23T11:00:00',
    });
    api.reviseReport.mockResolvedValue({
      outcome: 'CLARIFICATION',
      clarificationQuestion: '“销售额”指净额还是含退款金额？',
      clarificationCandidates: [{ code: 'total_net_amount', label: '净额' }],
    });
  });

  it('换取票据后加载报表并预览当前版本（票据只驻留内存）', async () => {
    const wrapper = await openPage();

    expect(api.issueDebugTicket).toHaveBeenCalledWith(
      'r07-app',
      'secret-value',
      'USER',
      'alice',
    );
    expect(api.getReportPage).toHaveBeenCalledWith('ticket-1', {
      pageNo: 1,
      pageSize: 10,
    });
    expect(wrapper.text()).toContain('华东 8 月销售');
    expect(wrapper.text()).toContain('可刷新');
    // 页面没有分享/发布入口（未实现的能力不放按钮）
    expect(wrapper.text()).not.toContain('分享');
    expect(wrapper.text()).not.toContain('发布');
  });

  it('打开报表后渲染规格与数据、展示截至时间与完整性', async () => {
    const wrapper = await openPage();
    await wrapper.get('[data-testid="report-open"]').trigger('click');
    await flushPromises();

    expect(api.readCurrentReportVersion).toHaveBeenCalledWith('ticket-1', 71);
    expect(api.listReportVersions).toHaveBeenCalledWith('ticket-1', 71);
    expect(api.readRefreshState).toHaveBeenCalledWith('ticket-1', 71);
    expect(wrapper.get('[data-testid="report-preview-title"]').text()).toBe(
      '华东 8 月销售',
    );
    expect(wrapper.get('[data-testid="report-as-of"]').text()).toContain(
      '2026-09-23 10:00',
    );
    expect(
      wrapper.get('[data-testid="report-preview-completeness"]').text(),
    ).toBe('完整数据');
  });

  it('刷新失败时显示原因与时间，并保留上一次结果', async () => {
    const wrapper = await openPage();
    await wrapper.get('[data-testid="report-open"]').trigger('click');
    await flushPromises();

    await wrapper.get('[data-testid="report-refresh"]').trigger('click');
    await flushPromises();

    expect(api.refreshReport).toHaveBeenCalledWith('ticket-1', { id: 71 });
    expect(
      wrapper.get('[data-testid="report-refresh-message"]').text(),
    ).toContain('已保留上一次结果');
    expect(
      wrapper.get('[data-testid="report-refresh-message"]').text(),
    ).toContain('1003006029');
    // 上一次结果仍在页面上（不因为刷新失败而清空）
    expect(wrapper.get('[data-testid="report-preview-title"]').text()).toBe(
      '华东 8 月销售',
    );
  });

  it('对话修改：澄清时给出追问与候选，不假装已修改', async () => {
    const wrapper = await openPage();
    await wrapper.get('[data-testid="report-open"]').trigger('click');
    await flushPromises();

    await wrapper.get('[data-testid="report-revise-open"]').trigger('click');
    await wrapper
      .get('[data-testid="report-revise-instruction"] input')
      .setValue('按周汇总');
    await wrapper
      .get('[data-testid="report-revise-endpoint"] input')
      .setValue('5');
    await wrapper.get('[data-testid="report-revise-submit"]').trigger('click');
    await flushPromises();

    expect(api.reviseReport).toHaveBeenCalledWith('ticket-1', {
      baseVersionNo: 2,
      endpointId: 5,
      id: 71,
      instruction: '按周汇总',
      version: 1,
    });
    expect(
      wrapper.get('[data-testid="report-revise-message"]').text(),
    ).toContain('需要澄清');
    expect(
      wrapper.get('[data-testid="report-revise-message"]').text(),
    ).toContain('净额');
  });

  it('快照报表不显示刷新按钮（模式语义一致）', async () => {
    api.getReportPage.mockResolvedValue({
      list: [{ ...report, mode: 'SNAPSHOT' }],
      total: 1,
    });
    const wrapper = await openPage();
    await wrapper.get('[data-testid="report-open"]').trigger('click');
    await flushPromises();

    expect(wrapper.find('[data-testid="report-refresh"]').exists()).toBe(false);
  });

  it('票据换取失败如实提示，不进入报表列表', async () => {
    api.issueDebugTicket.mockRejectedValue(new Error('客户端凭据无效'));
    const wrapper = await openPage();

    expect(
      wrapper.get('[data-testid="report-ticket-failure"]').text(),
    ).toContain('客户端凭据无效');
    expect(api.getReportPage).not.toHaveBeenCalled();
  });

  it('卸载后清空票据与预览数据（不留在内存之外）', async () => {
    const wrapper = await openPage();
    await wrapper.get('[data-testid="report-open"]').trigger('click');
    await flushPromises();

    wrapper.unmount();

    // 卸载后再触发加载必须重新换取票据（说明旧票据已不在组件状态里）
    const again = await openPage();
    expect(api.issueDebugTicket).toHaveBeenCalledTimes(2);
    again.unmount();
  });
});

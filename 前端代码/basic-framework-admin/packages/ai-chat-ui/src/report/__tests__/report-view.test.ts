import { mount } from '@vue/test-utils';

import { describe, expect, it, vi } from 'vitest';

// 图表适配层在组件测试里用替身：本套件验证"报表渲染层"的行为（图表契约另由 R02 的测试覆盖）
vi.mock('@antv/g2', () => ({
  Chart: class {
    public changeSize = vi.fn();
    public destroy = vi.fn();
    public options = vi.fn();
    public render = vi.fn();
    public constructor(public config: Record<string, unknown>) {}
  },
}));

const { default: AiReportView } = await import('../AiReportView.vue');
const {
  buildChartSpec,
  cellText,
  formatMetric,
  parseJsonText,
  parseReportData,
  parseReportSpec,
  safeParseReportData,
  safeParseReportSpec,
} = await import('../reportSpec');

const spec = {
  schemaVersion: '1.0' as const,
  title: '华东 8 月销售',
  themeRef: { themeId: 'thm_default', revision: 1 },
  layout: {
    columns: 12 as const,
    gap: 16,
    items: [
      { blockId: 'intro', row: 0, column: 0, span: 12 },
      { blockId: 'total', row: 1, column: 0, span: 4 },
      { blockId: 'sales_chart', row: 1, column: 4, span: 8 },
      { blockId: 'detail', row: 2, column: 0, span: 12 },
    ],
  },
  blocks: [
    {
      id: 'intro',
      title: '统计口径',
      type: 'text' as const,
      text: '8 月、华东、已付款订单。',
    },
    {
      id: 'total',
      title: '净销售额合计',
      type: 'metric' as const,
      datasetRef: 'sales_result',
      metricField: 'total_net_amount',
      rowIndex: 0,
      format: 'CURRENCY' as const,
      unit: 'CNY',
    },
    {
      id: 'sales_chart',
      title: '客户净销售额',
      type: 'chart' as const,
      datasetRef: 'sales_result',
      chart: {
        chartType: 'column' as const,
        categoryField: 'customer_name',
        valueField: 'total_net_amount',
        legend: false,
      },
    },
    {
      id: 'detail',
      title: '客户明细',
      type: 'table' as const,
      datasetRef: 'sales_result',
      columns: [
        { field: 'customer_name', label: '客户', format: 'TEXT' as const },
        {
          field: 'total_net_amount',
          label: '净销售额',
          format: 'CURRENCY' as const,
        },
      ],
      pageSize: 10,
    },
  ],
  datasetRefs: [
    {
      id: 'sales_result',
      resultRef: 'plan_cccccccccccc',
      queryRef: 'sales_query',
      columns: [
        { field: 'customer_name', label: '客户', dataType: 'STRING' as const },
        {
          field: 'total_net_amount',
          label: '净销售额',
          dataType: 'DECIMAL' as const,
          unit: 'CNY',
        },
      ],
      rowCount: 2,
      completeness: 'COMPLETE' as const,
    },
  ],
  queryRefs: [
    {
      id: 'sales_query',
      plan: { datasetId: 'dset_golden-sales', datasetVersion: 1 },
    },
  ],
  sources: [
    {
      id: 'sales_source',
      kind: 'DATASET' as const,
      resourceId: 'golden-sales',
      resourceVersion: 1,
      queryRef: 'sales_query',
      description: '黄金集合成数据集',
    },
  ],
};

const data = {
  kind: 'REPORT' as const,
  data: [
    { blockId: 'intro', type: 'text', verified: false },
    { blockId: 'total', type: 'metric', verified: true, value: '740.00' },
    {
      blockId: 'sales_chart',
      type: 'chart',
      verified: true,
      points: [
        { customer_name: 'alice', total_net_amount: '290.00' },
        { customer_name: 'bob', total_net_amount: '450.00' },
      ],
    },
    {
      blockId: 'detail',
      type: 'table',
      verified: true,
      rows: [
        {
          customer_name: 'alice',
          total_net_amount: '290.00',
          internal_note: '不可见列',
        },
        { customer_name: 'bob', total_net_amount: '450.00' },
      ],
    },
  ],
  datasets: [
    {
      datasetRef: 'sales_result',
      columns: [
        { field: 'customer_name', label: '客户', dataType: 'STRING' as const },
        {
          field: 'total_net_amount',
          label: '净销售额',
          dataType: 'DECIMAL' as const,
          unit: 'CNY',
        },
      ],
      rows: [
        { customer_name: 'alice', total_net_amount: '290.00' },
        { customer_name: 'bob', total_net_amount: '450.00' },
      ],
      completeness: 'COMPLETE' as const,
    },
  ],
  notes: [],
};

/** 结果行夹具（避免非空断言：找不到即抛错，测试失败要能定位）。 */
function blockDataAt(index: number) {
  const item = data.data[index];
  if (!item) {
    throw new Error(`缺少第 ${index} 个块数据`);
  }
  return item;
}

/** 数据集夹具（同上）。 */
function firstDataset() {
  const dataset = data.datasets[0];
  if (!dataset) {
    throw new Error('缺少数据集夹具');
  }
  return dataset;
}

describe('report 渲染层契约', () => {
  it('解析规格与数据（严格模式：未知键拒绝）', () => {
    expect(parseReportSpec(spec).title).toBe('华东 8 月销售');
    expect(parseReportData(data).data).toHaveLength(4);
    expect(safeParseReportSpec({ ...spec, script: 'alert(1)' })).toBeNull();
    expect(
      safeParseReportSpec({
        ...spec,
        blocks: [{ id: 'x', title: 'x', type: 'html' }],
      }),
    ).toBeNull();
    expect(safeParseReportData({ ...data, kind: 'OTHER' })).toBeNull();
    expect(parseJsonText('not-json')).toBeNull();
    expect(parseJsonText(undefined)).toBeNull();
  });

  it('图表块转 ChartSpec：column → bar，缺失取值降级为表格', () => {
    const chartBlock = spec.blocks[2] as unknown as Parameters<
      typeof buildChartSpec
    >[0];
    const built = buildChartSpec(chartBlock, data.data[2]?.points);
    expect(built.spec?.type).toBe('bar');
    expect(built.spec?.categories).toEqual(['alice', 'bob']);
    expect(built.spec?.series[0]?.data).toEqual(['290.00', '450.00']);

    expect(buildChartSpec(chartBlock, [])).toEqual({
      reason: '没有可绘制的数据点',
      spec: null,
    });
    expect(
      buildChartSpec(chartBlock, [
        { customer_name: 'alice', total_net_amount: '290.00' },
        { customer_name: 'bob', total_net_amount: null },
      ]).reason,
    ).toBe('数值列存在缺失取值');
    expect(
      buildChartSpec(chartBlock, [
        { customer_name: 'alice', total_net_amount: 'not-a-number' },
      ]).reason,
    ).toBe('数值列存在缺失取值');
  });

  it('展示格式只做文本转换（非数字不假装成数字）', () => {
    expect(formatMetric('740.00', 'CURRENCY', 'CNY')).toBe('740 CNY');
    expect(formatMetric(null, 'CURRENCY')).toBe('—');
    expect(formatMetric('说明文本', 'NUMBER')).toBe('说明文本');
    expect(formatMetric(12, 'PERCENT')).toBe('12%');
    expect(formatMetric('1234.5', 'NUMBER')).toBe('1,234.5');
    expect(cellText(undefined)).toBe('');
    expect(cellText({ a: 1 })).toBe('{"a":1}');
  });

  it('渲染四类块、元信息与失败状态（文本插值，无脚本执行）', () => {
    const wrapper = mount(AiReportView, {
      props: {
        data,
        failureReason: '上次刷新失败：来源已停用',
        spec,
        theme: 'dark',
      },
    });

    expect(wrapper.get('[data-testid="ai-report-title"]').text()).toBe(
      '华东 8 月销售',
    );
    expect(wrapper.get('[data-testid="ai-report-text"]').text()).toContain(
      '8 月、华东',
    );
    expect(wrapper.get('[data-testid="ai-report-metric"]').text()).toBe(
      '740 CNY',
    );
    expect(wrapper.get('[data-testid="ai-report-completeness"]').text()).toBe(
      '完整数据',
    );
    expect(wrapper.get('[data-testid="ai-report-source"]').text()).toContain(
      'golden-sales@v1',
    );
    expect(wrapper.get('[data-testid="ai-report-failure"]').text()).toContain(
      '来源已停用',
    );
    // 表格只渲染声明的列：结果里的 internal_note 不进界面
    const tableText = wrapper.get('[data-testid="ai-report-table"]').text();
    expect(tableText).toContain('alice');
    expect(tableText).toContain('290.00');
    expect(tableText).not.toContain('不可见列');
    // 深浅色由令牌驱动
    expect(wrapper.get('[data-testid="ai-report"]').classes()).toContain(
      'ai-report--dark',
    );
    // 恶意内容只作为文本存在，不产生脚本节点
    expect(wrapper.html()).not.toContain('<script');
  });

  it('规格不合法时拒绝渲染（不猜测结构）', () => {
    const wrapper = mount(AiReportView, {
      props: { spec: JSON.stringify({ ...spec, blocks: [{ type: 'html' }] }) },
    });
    expect(wrapper.get('[data-testid="ai-report-invalid"]').text()).toContain(
      '拒绝渲染',
    );
    expect(wrapper.find('[data-testid="ai-report-grid"]').exists()).toBe(false);
  });

  it('空数据/部分数据/无图表数据都有明确提示', () => {
    const empty = mount(AiReportView, { props: { data: null, spec } });
    expect(empty.get('[data-testid="ai-report-table-empty"]').text()).toBe(
      '没有数据行',
    );
    expect(empty.get('[data-testid="ai-report-metric"]').text()).toBe('—');
    expect(
      empty.get('[data-testid="ai-report-chart-fallback"]').text(),
    ).toContain('已改为表格');

    const partial = mount(AiReportView, {
      props: {
        data: {
          ...data,
          datasets: [
            {
              columns: firstDataset().columns,
              completeness: 'PARTIAL' as const,
              datasetRef: firstDataset().datasetRef,
              rows: firstDataset().rows,
            },
          ],
        },
        spec,
      },
    });
    expect(
      partial.get('[data-testid="ai-report-completeness"]').text(),
    ).toContain('不能当作完整统计');

    const failed = mount(AiReportView, {
      props: {
        data: {
          ...data,
          datasets: [
            {
              columns: firstDataset().columns,
              completeness: 'FAILED' as const,
              datasetRef: firstDataset().datasetRef,
              rows: firstDataset().rows,
            },
          ],
        },
        spec,
      },
    });
    expect(
      failed.get('[data-testid="ai-report-completeness"]').text(),
    ).toContain('上游失败');
  });

  it('点数据表格用于图表降级，窄容器由栅格与滚动容器兜底', () => {
    const narrow = mount(AiReportView, {
      props: {
        data: {
          ...data,
          data: [
            blockDataAt(0),
            blockDataAt(1),
            {
              blockId: 'sales_chart',
              type: 'chart',
              points: [
                { customer_name: 'alice', total_net_amount: '290.00' },
                { customer_name: 'bob', total_net_amount: null },
              ],
            },
            blockDataAt(3),
          ],
        },
        spec,
      },
    });

    expect(
      narrow.get('[data-testid="ai-report-points-table"]').text(),
    ).toContain('alice');
    const chartBlock = narrow.get('[data-block-id="sales_chart"]');
    // 栅格位置来自布局声明（12 列栅格 + min-width: 0，窄容器不溢出）
    expect(chartBlock.attributes('style')).toContain('grid-column: 5 / span 8');
    expect(chartBlock.attributes('style')).toContain('min-width: 0');
    expect(narrow.find('[data-testid="ai-report-table-wrap"]').exists()).toBe(
      true,
    );
  });

  it('文本与标题里的 HTML 只作为文本渲染', () => {
    const malicious = mount(AiReportView, {
      props: {
        spec: {
          ...spec,
          blocks: [
            {
              id: 'intro',
              title: '<img src=x onerror=alert(1)>',
              type: 'text' as const,
              text: '<script>alert(1)</script>',
            },
          ],
          layout: {
            columns: 12 as const,
            gap: 16,
            items: [{ blockId: 'intro', row: 0, column: 0, span: 12 }],
          },
        },
      },
    });

    const html = malicious.html();
    // 只作为文本：标签被转义，不产生真实节点与事件属性
    expect(html).not.toContain('<script');
    expect(html).not.toContain('<img');
    expect(html).toContain('&lt;script&gt;');
    expect(malicious.get('[data-testid="ai-report-text"]').text()).toBe(
      '<script>alert(1)</script>',
    );
  });
});

import type {
  ReportData,
  ReportSpec,
} from '../../../../../../packages/ai-chat-ui/src/report/reportSpec';

import { mount } from '@vue/test-utils';

import { describe, expect, it } from 'vitest';

import ReportChartSvg from './ReportChartSvg.vue';
import ReportPreview from './ReportPreview.vue';

const spec: ReportSpec = {
  schemaVersion: '1.0',
  title: '华东 8 月销售',
  themeRef: { themeId: 'thm_default', revision: 1 },
  layout: {
    columns: 12,
    gap: 16,
    items: [
      { blockId: 'intro', row: 0, column: 0, span: 12 },
      { blockId: 'total', row: 1, column: 0, span: 4 },
      { blockId: 'sales_chart', row: 1, column: 4, span: 8 },
      { blockId: 'detail', row: 2, column: 0, span: 12 },
    ],
  },
  blocks: [
    { id: 'intro', title: '统计口径', type: 'text', text: '8 月、华东。' },
    {
      id: 'total',
      title: '净销售额合计',
      type: 'metric',
      datasetRef: 'sales_result',
      metricField: 'total_net_amount',
      rowIndex: 0,
      format: 'CURRENCY',
      unit: 'CNY',
    },
    {
      id: 'sales_chart',
      title: '客户净销售额',
      type: 'chart',
      datasetRef: 'sales_result',
      chart: {
        chartType: 'column',
        categoryField: 'customer_name',
        valueField: 'total_net_amount',
        legend: false,
      },
    },
    {
      id: 'detail',
      title: '客户明细',
      type: 'table',
      datasetRef: 'sales_result',
      columns: [
        { field: 'customer_name', label: '客户', format: 'TEXT' },
        { field: 'total_net_amount', label: '净销售额', format: 'CURRENCY' },
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
        { field: 'customer_name', label: '客户', dataType: 'STRING' },
        {
          field: 'total_net_amount',
          label: '净销售额',
          dataType: 'DECIMAL',
          unit: 'CNY',
        },
      ],
      rowCount: 2,
      completeness: 'COMPLETE',
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
      kind: 'DATASET',
      resourceId: 'golden-sales',
      resourceVersion: 1,
      description: '合成数据集',
    },
  ],
};

const data: ReportData = {
  kind: 'REPORT',
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
      ],
    },
  ],
  datasets: [
    {
      datasetRef: 'sales_result',
      columns: [
        { field: 'customer_name', label: '客户', dataType: 'STRING' },
        {
          field: 'total_net_amount',
          label: '净销售额',
          dataType: 'DECIMAL',
          unit: 'CNY',
        },
      ],
      rows: [{ customer_name: 'alice', total_net_amount: '290.00' }],
      completeness: 'COMPLETE',
    },
  ],
  notes: [],
};

/** 结果行夹具（避免索引访问的可空推断）。 */
function blockDataAt(index: number): ReportData['data'][number] {
  const item = data.data[index];
  if (!item) {
    throw new Error(`缺少第 ${index} 个块数据`);
  }
  return item;
}

describe('报表预览（页面版）', () => {
  it('渲染四类块与元信息（指标取真实值、表格列白名单、图表走 SVG）', () => {
    const wrapper = mount(ReportPreview, {
      props: {
        data,
        failureReason: '刷新失败：1003006029',
        spec,
        theme: 'dark',
      },
    });

    expect(wrapper.get('[data-testid="report-preview-title"]').text()).toBe(
      '华东 8 月销售',
    );
    expect(wrapper.get('[data-testid="report-preview-text"]').text()).toContain(
      '8 月、华东',
    );
    expect(wrapper.get('[data-testid="report-preview-metric"]').text()).toBe(
      '740 CNY',
    );
    expect(
      wrapper.get('[data-testid="report-preview-completeness"]').text(),
    ).toBe('完整数据');
    expect(
      wrapper.get('[data-testid="report-preview-source"]').text(),
    ).toContain('golden-sales@v1');
    expect(
      wrapper.get('[data-testid="report-preview-failure"]').text(),
    ).toContain('1003006029');
    expect(wrapper.find('[data-testid="report-chart-svg"]').exists()).toBe(
      true,
    );
    expect(wrapper.get('[data-testid="report-chart-legend"]').text()).toContain(
      'alice：290.00',
    );

    const tableText = wrapper
      .get('[data-testid="report-preview-table"]')
      .text();
    expect(tableText).toContain('alice');
    expect(tableText).not.toContain('不可见列');
    expect(wrapper.get('[data-testid="report-preview"]').classes()).toContain(
      'report-preview--dark',
    );
  });

  it('图表缺失取值/无数据时降级为表格并说明原因（不补 0）', () => {
    const wrapper = mount(ReportPreview, {
      props: {
        data: {
          ...data,
          data: [
            blockDataAt(0),
            blockDataAt(1),
            {
              blockId: 'sales_chart',
              points: [
                { customer_name: 'alice', total_net_amount: '290.00' },
                { customer_name: 'bob', total_net_amount: null },
              ],
              type: 'chart',
            },
            blockDataAt(3),
          ],
        },
        spec,
      },
    });

    expect(
      wrapper.get('[data-testid="report-preview-chart-fallback"]').text(),
    ).toContain('已改为表格');
    expect(
      wrapper.get('[data-testid="report-preview-points-table"]').text(),
    ).toContain('alice');
  });

  it('空数据与不合法规格都有明确提示（不猜测结构）', () => {
    const empty = mount(ReportPreview, { props: { data: null, spec } });
    expect(empty.get('[data-testid="report-preview-table-empty"]').text()).toBe(
      '没有数据行',
    );
    expect(empty.get('[data-testid="report-preview-metric"]').text()).toBe('—');
    expect(
      empty.get('[data-testid="report-preview-chart-fallback"]').text(),
    ).toContain('没有可绘制的数据点');

    const invalid = mount(ReportPreview, {
      props: { spec: JSON.stringify({ ...spec, blocks: [{ type: 'html' }] }) },
    });
    expect(
      invalid.get('[data-testid="report-preview-invalid"]').text(),
    ).toContain('拒绝渲染');
  });

  it('文本里的 HTML 只作为文本渲染（无脚本执行）', () => {
    const malicious = mount(ReportPreview, {
      props: {
        spec: {
          ...spec,
          blocks: [
            {
              id: 'intro',
              title: '<img src=x onerror=alert(1)>',
              type: 'text',
              text: '<script>alert(1)</script>',
            },
          ],
          layout: {
            columns: 12,
            gap: 16,
            items: [{ blockId: 'intro', row: 0, column: 0, span: 12 }],
          },
        },
      },
    });

    const html = malicious.html();
    expect(html).not.toContain('<script');
    expect(html).not.toContain('<img');
    expect(malicious.get('[data-testid="report-preview-text"]').text()).toBe(
      '<script>alert(1)</script>',
    );
  });

  it('窄容器由栅格与滚动容器兜底（12 列 + min-width: 0）', () => {
    const wrapper = mount(ReportPreview, { props: { data, spec } });
    const chartBlock = wrapper.get('[data-block-id="sales_chart"]');
    expect(chartBlock.attributes('style')).toContain('grid-column: 5 / span 8');
    expect(chartBlock.attributes('style')).toContain('min-width: 0');
    expect(
      wrapper.find('[data-testid="report-preview-table-wrap"]').exists(),
    ).toBe(true);
  });
});

describe('报表图表（零依赖 SVG）', () => {
  it('柱状/折线/饼图都渲染为 SVG 元素，并在图例里保留原始值文本', () => {
    const column = mount(ReportChartSvg, {
      props: {
        categories: ['alice', 'bob'],
        chartType: 'column',
        values: ['290.00', '450.00'],
      },
    });
    expect(column.findAll('rect')).toHaveLength(2);
    expect(column.get('[data-testid="report-chart-legend"]').text()).toContain(
      '450.00',
    );

    const line = mount(ReportChartSvg, {
      props: { categories: ['a', 'b'], chartType: 'line', values: [1, 2] },
    });
    expect(line.get('path').attributes('d')).toContain('M');

    const pie = mount(ReportChartSvg, {
      props: { categories: ['a', 'b'], chartType: 'pie', values: [1, 1] },
    });
    expect(pie.findAll('path')).toHaveLength(2);

    const emptyPie = mount(ReportChartSvg, {
      props: { categories: ['a'], chartType: 'pie', values: [0] },
    });
    expect(emptyPie.findAll('path')).toHaveLength(0);
  });
});

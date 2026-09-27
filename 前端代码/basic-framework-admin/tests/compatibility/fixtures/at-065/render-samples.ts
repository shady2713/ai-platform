/**
 * AT-065 渲染回归样例（Q08 前端切片）。
 *
 * <p>这些样例是**冻结回归夹具**：升级图表库/消息层/报表层后必须重跑并保证结构不变。
 * 它们不是新协议：`FROZEN_CHART_SPEC` 与 F07 冻结样例
 * `docs/contracts/ai/samples/result-block.chart-money.valid.json` 的 `spec` 逐字段一致
 * （由 `at-065-frozen-candidate-render-regression.test.ts` 断言）；
 * 报表样例使用 R07 当前支持的 v1 形状（与 AT-049 的 `SUPPORTED_SPEC` 同形，字段名对齐
 * `packages/ai-chat-ui/src/report/reportSpec.ts` 的解析器）。
 */
import type { ChartSpec } from '../../../../packages/ai-contracts/src/index';
import type {
  ReportData,
  ReportSpec,
} from '../../../../packages/ai-chat-ui/src/report/reportSpec';

/** 图表：十进制字符串金额（精度无损），来自 F07 冻结样例。 */
export const FROZEN_CHART_SPEC: ChartSpec = {
  categories: ['一月', '二月'],
  series: [
    {
      data: ['12345678901234.56', '98765432109876.54'],
      name: '销售额',
    },
  ],
  title: '月度销售额',
  type: 'bar' as const,
};

/** 单系列趋势样例（line）：升级后折线渲染契约不变。 */
export const FROZEN_LINE_SPEC: ChartSpec = {
  categories: ['一月', '二月', '三月'],
  series: [
    {
      data: [120, 200, 150],
      name: '订单数',
    },
  ],
  title: '订单趋势',
  type: 'line' as const,
};

/** 报表：v1 形状（12 列栅格、四类块中的三类 + 图表块）。 */
export const REPORT_SPEC_SAMPLE: ReportSpec = {
  schemaVersion: '1.0',
  title: '2026年8月华东客户净销售额',
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
    {
      id: 'intro',
      title: '统计口径',
      text: '2026年8月、华东、已付款订单，按客户汇总扣除退款后的净额。',
      type: 'text',
    },
    {
      id: 'total',
      title: '净销售额合计',
      datasetRef: 'sales_result',
      format: 'CURRENCY',
      metricField: 'net_amount',
      rowIndex: 0,
      type: 'metric',
      unit: 'CNY',
    },
    {
      id: 'sales_chart',
      title: '客户净销售额',
      chart: {
        categoryField: 'customer_name',
        chartType: 'column',
        legend: false,
        valueField: 'net_amount',
      },
      datasetRef: 'sales_result',
      type: 'chart',
    },
    {
      id: 'detail',
      title: '客户明细',
      columns: [
        { field: 'customer_name', format: 'TEXT', label: '客户' },
        { field: 'net_amount', format: 'CURRENCY', label: '净销售额（元）' },
      ],
      datasetRef: 'sales_result',
      pageSize: 10,
      type: 'table',
    },
  ],
  datasetRefs: [
    {
      columns: [
        { dataType: 'STRING', field: 'customer_name', label: '客户' },
        {
          dataType: 'DECIMAL',
          field: 'net_amount',
          label: '净销售额',
          unit: 'CNY',
        },
      ],
      completeness: 'COMPLETE',
      id: 'sales_result',
      queryRef: 'sales_query',
      resultRef: 'run_sales_demo/result/0',
      rowCount: 2,
    },
  ],
  queryRefs: [
    {
      id: 'sales_query',
      plan: {
        datasetId: 'sales_demo',
        datasetVersion: 1,
        schemaVersion: '1.0',
      },
    },
  ],
  sources: [
    {
      description: '合成销售数据集；数据截至时间为测试固定时点。',
      id: 'sales_source',
      kind: 'DATASET',
      queryRef: 'sales_query',
      resourceId: 'sales_demo',
      resourceVersion: 1,
    },
  ],
};

/** 报表版本数据：数字块带真实取值；结果里夹带一列未声明列（不得进入界面）。 */
export const REPORT_DATA_SAMPLE: ReportData = {
  kind: 'REPORT',
  data: [
    { blockId: 'intro', type: 'text', verified: false },
    { blockId: 'total', type: 'metric', value: '740.00', verified: true },
    {
      blockId: 'sales_chart',
      points: [
        { customer_name: 'alice', net_amount: '290.00' },
        { customer_name: 'bob', net_amount: '450.00' },
      ],
      type: 'chart',
      verified: true,
    },
    {
      blockId: 'detail',
      rows: [
        {
          customer_name: 'alice',
          internal_note: '不可见列',
          net_amount: '290.00',
        },
        { customer_name: 'bob', net_amount: '450.00' },
      ],
      type: 'table',
      verified: true,
    },
  ],
  datasets: [
    {
      columns: [
        { dataType: 'STRING', field: 'customer_name', label: '客户' },
        {
          dataType: 'DECIMAL',
          field: 'net_amount',
          label: '净销售额',
          unit: 'CNY',
        },
      ],
      completeness: 'COMPLETE',
      datasetRef: 'sales_result',
      rows: [
        { customer_name: 'alice', net_amount: '290.00' },
        { customer_name: 'bob', net_amount: '450.00' },
      ],
    },
  ],
};

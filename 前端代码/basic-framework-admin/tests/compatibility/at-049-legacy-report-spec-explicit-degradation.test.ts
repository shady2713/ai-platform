/**
 * AT-049（Q08 前端切片）：旧 ReportSpec 加载 —— 明确降级，不静默按新版本渲染。
 *
 * 口径（必须明文保留，禁止把本结论说成"真实 N-1 报表联调"）：
 *  - 仓库里**没有** `schemaVersion` 早于 `1.0` 的真实历史报表产物：`docs/contracts/ai/samples/report-spec.valid.json`
 *    是 F07（2026-09-17）冻结的 v1 样例，版本戳仍是 `1.0`；
 *  - 因此"旧版本"用**冻结样例的等值变体**（只替换 schemaVersion 戳）驱动，验证"不支持的版本必须明确提示"；
 *  - 冻结样例本身（真实冻结产物）与后端 R03 的**规范序列化产物**也逐条驱动：当前实现必须显式拒绝
 *    （不抛未捕获异常、不渲染任何块），而不是静默按新版本尽力渲染。
 *
 * 断言对象是 `packages/ai-chat-ui/src/report`（只读引用；本切片未改一行产品源码）。
 * 若本文件里的"记录既有实现"用例变红，说明实现已变化：必须同步更新 `it.fails` tripwire 与交接记录。
 */
import { join } from 'node:path';

import { mount } from '@vue/test-utils';

import { describe, expect, it } from 'vitest';

import AiReportView from '../../packages/ai-chat-ui/src/report/AiReportView.vue';
import {
  parseReportSpec,
  safeParseReportSpec,
} from '../../packages/ai-chat-ui/src/report/reportSpec';
import { readJsonFile, repositoryRoot } from './support/workspace';

interface ReportSpecFixture {
  [key: string]: unknown;
  layout: { columns: number; gap: number; items: unknown[] };
  schemaVersion: string;
}

/** F07 冻结的 v1 报表样例（权威 Schema 的合法产物；只读引用 docs/contracts）。 */
const FROZEN_V1_SAMPLE = readJsonFile<ReportSpecFixture>(
  join(repositoryRoot(), 'docs/contracts/ai/samples/report-spec.valid.json'),
);

/** 前端 R07 当前支持的 v1 形状（用来隔离"版本戳"这一个变量）。 */
const SUPPORTED_SPEC: ReportSpecFixture = {
  schemaVersion: '1.0',
  title: '2026年8月华东客户净销售额',
  themeRef: { themeId: 'thm_default', revision: 1 },
  layout: {
    columns: 12,
    gap: 16,
    items: [
      { blockId: 'intro', row: 0, column: 0, span: 12 },
      { blockId: 'total', row: 1, column: 0, span: 4 },
      { blockId: 'detail', row: 1, column: 4, span: 8 },
    ],
  },
  blocks: [
    {
      id: 'intro',
      title: '统计口径',
      type: 'text',
      text: '2026年8月、华东、已付款订单，按客户汇总扣除退款后的净额。',
    },
    {
      id: 'total',
      title: '净销售额合计',
      type: 'metric',
      datasetRef: 'sales_result',
      metricField: 'net_amount',
      rowIndex: 0,
      format: 'CURRENCY',
      unit: 'CNY',
    },
    {
      id: 'detail',
      title: '客户明细',
      type: 'table',
      datasetRef: 'sales_result',
      columns: [
        { field: 'customer_name', label: '客户', format: 'TEXT' },
        { field: 'net_amount', label: '净销售额（元）', format: 'CURRENCY' },
      ],
      pageSize: 10,
    },
  ],
  datasetRefs: [
    {
      id: 'sales_result',
      resultRef: 'run_sales_demo/result/0',
      queryRef: 'sales_query',
      columns: [
        { field: 'customer_name', label: '客户', dataType: 'STRING' },
        {
          field: 'net_amount',
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
      plan: {
        schemaVersion: '1.0',
        datasetId: 'sales_demo',
        datasetVersion: 1,
      },
    },
  ],
  sources: [
    {
      id: 'sales_source',
      kind: 'DATASET',
      resourceId: 'sales_demo',
      resourceVersion: 1,
      queryRef: 'sales_query',
      description: '合成销售数据集；数据截至时间为测试固定时点。',
    },
  ],
};

const SUPPORTED_DATA = {
  kind: 'REPORT',
  data: [
    { blockId: 'intro', type: 'text', verified: false },
    { blockId: 'total', type: 'metric', verified: true, value: '740.00' },
    {
      blockId: 'detail',
      type: 'table',
      verified: true,
      rows: [
        { customer_name: 'alice', net_amount: '290.00' },
        {
          customer_name: 'bob',
          net_amount: '450.00',
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
          field: 'net_amount',
          label: '净销售额',
          dataType: 'DECIMAL',
          unit: 'CNY',
        },
      ],
      rows: [
        { customer_name: 'alice', net_amount: '290.00' },
        { customer_name: 'bob', net_amount: '450.00' },
      ],
      completeness: 'COMPLETE',
    },
  ],
};

/**
 * 后端 R03 的规范序列化产物（逐键对齐 `AiReportGenerationStep.specJson`：
 * metric 块写 `binding{datasetRef,field}`、`layout` 只有 `{gap,items}`、`datasetRefs` 无 `asOf`）。
 * 这里只是把该实现**已冻结的产物形状**变成可执行探针，不是新造协议。
 */
const BACKEND_R03_CANONICAL_PROBE = {
  schemaVersion: '1.0',
  title: '2026年8月华东客户净销售额',
  themeRef: { themeId: 'thm_default', revision: 1 },
  blocks: [
    {
      id: 'intro',
      type: 'text',
      title: '统计口径',
      text: '2026年8月、华东、已付款订单。',
    },
    {
      id: 'total',
      type: 'metric',
      title: '净销售额合计',
      datasetRef: 'sales_result',
      binding: { datasetRef: 'sales_result', field: 'net_amount' },
      rowIndex: 0,
      format: 'CURRENCY',
      unit: 'CNY',
    },
  ],
  datasetRefs: [
    {
      id: 'sales_result',
      resultRef: 'run_sales_demo/result/0',
      queryRef: 'sales_query',
      columns: [
        {
          field: 'net_amount',
          label: '净销售额',
          dataType: 'DECIMAL',
          unit: 'CNY',
        },
      ],
      rowCount: 2,
      completeness: 'COMPLETE',
    },
  ],
  layout: {
    gap: 16,
    items: [
      { blockId: 'intro', row: 0, column: 0, span: 12 },
      { blockId: 'total', row: 1, column: 0, span: 4 },
    ],
  },
  queryRefs: [
    {
      id: 'sales_query',
      plan: { schemaVersion: '1.0', datasetId: 'sales_demo' },
    },
  ],
  sources: [
    {
      id: 'sales_source',
      kind: 'DATASET',
      resourceId: 'sales_demo',
      resourceVersion: 1,
      queryRef: 'sales_query',
      description: '合成销售数据集；数据截至时间为测试固定时点。',
    },
  ],
};

/** 抓取解析器抛出的错误（用于断言"明确提示"的原文，而不是只看"抛没抛"）。 */
function captureError(run: () => unknown): Error {
  try {
    run();
  } catch (error) {
    return error as Error;
  }
  throw new Error('预期解析器抛错，但实际通过');
}

describe('旧报表规格（AT-049）：明确降级 —— 基线夹具验证，非真实 N-1 产物', () => {
  it('不支持的 schemaVersion 明确报"不支持的契约版本"（不静默降级）', () => {
    const probes = [
      {
        label: '0.9（更早版本戳）',
        spec: { ...FROZEN_V1_SAMPLE, schemaVersion: '0.9' },
      },
      {
        label: '2.0（未知/未来版本）',
        spec: { ...FROZEN_V1_SAMPLE, schemaVersion: '2.0' },
      },
      {
        label: '1.0.0（非 x.y 版本形状）',
        spec: { ...FROZEN_V1_SAMPLE, schemaVersion: '1.0.0' },
      },
      {
        label: '1.00（等值但非法文本）',
        spec: { ...FROZEN_V1_SAMPLE, schemaVersion: '1.00' },
      },
    ];
    for (const probe of probes) {
      const error = captureError(() => parseReportSpec(probe.spec));
      expect(error.message, probe.label).toBe(
        '报表数据不合法：不支持的契约版本',
      );
      expect(safeParseReportSpec(probe.spec), probe.label).toBeNull();
    }
    // 版本戳必须是字符串：数字 1 不能被当成 1.0 接受
    expect(
      safeParseReportSpec({ ...SUPPORTED_SPEC, schemaVersion: 1 }),
    ).toBeNull();
  });

  it('组件对旧版本规格：不抛未捕获异常、显示明确拒绝提示、不渲染任何块', () => {
    const legacy = { ...SUPPORTED_SPEC, schemaVersion: '0.9' };
    let wrapper: ReturnType<typeof mount> | undefined;
    expect(() => {
      wrapper = mount(AiReportView, {
        props: { data: SUPPORTED_DATA, spec: JSON.stringify(legacy) },
      });
    }).not.toThrow();
    if (!wrapper) {
      throw new Error('未挂载组件');
    }
    const rendered = wrapper;
    expect(rendered.get('[data-testid="ai-report-invalid"]').text()).toContain(
      '拒绝渲染',
    );
    // 不静默按新版本渲染：标题、栅格、任何块都不出现
    expect(rendered.find('[data-testid="ai-report-grid"]').exists()).toBe(
      false,
    );
    expect(rendered.find('[data-testid="ai-report-title"]').exists()).toBe(
      false,
    );
    expect(rendered.html()).not.toContain('华东');
    expect(rendered.html()).not.toContain('740');
  });

  it('支持的版本行为不变（同一份规格只改版本戳 → 正常渲染）', () => {
    expect(parseReportSpec(SUPPORTED_SPEC).title).toBe(
      '2026年8月华东客户净销售额',
    );
    const wrapper = mount(AiReportView, {
      props: { data: SUPPORTED_DATA, spec: JSON.stringify(SUPPORTED_SPEC) },
    });
    expect(wrapper.get('[data-testid="ai-report-title"]').text()).toBe(
      '2026年8月华东客户净销售额',
    );
    expect(wrapper.get('[data-testid="ai-report-metric"]').text()).toBe(
      '740 CNY',
    );
    expect(wrapper.get('[data-testid="ai-report-table"]').text()).toContain(
      'alice',
    );
    expect(wrapper.get('[data-testid="ai-report-table"]').text()).not.toContain(
      '不可见列',
    );
  });

  /**
   * 以下两条是"记录既有实现"的**缺陷证据**，不是验收通过项：
   * 冻结的权威 v1 样例（F07）与后端 R03 的规范产物都无法被当前前端解析。
   * 影响：报表页/消息层会用"报表规格不合法"提示替代整张报表（不静默，但用户看不到报表）。
   * 修复必须在契约拥有方（前端解析器或后端序列化，二选一先改权威 Schema）落地，本切片不改源码。
   */
  it('记录既有实现：F07 冻结 v1 样例被前端拒绝（拒绝原因可复核）', () => {
    const error = captureError(() => parseReportSpec(FROZEN_V1_SAMPLE));
    expect(error.message).toBe('报表数据不合法：未知字段 asOf');
    expect(safeParseReportSpec(FROZEN_V1_SAMPLE)).toBeNull();
  });

  it('记录既有实现：后端 R03 规范序列化产物被前端拒绝（布局缺 columns，拒绝原因可复核）', () => {
    const error = captureError(() =>
      parseReportSpec(BACKEND_R03_CANONICAL_PROBE),
    );
    expect(error.message).toBe('报表数据不合法：布局必须是 12 列栅格');
    expect(safeParseReportSpec(BACKEND_R03_CANONICAL_PROBE)).toBeNull();
  });

  it('记录既有实现：metric 块的 binding 形状单独驱动也被前端拒绝（未知字段 binding）', () => {
    // 只补上 R03 缺失的 layout.columns，让 metric 形状成为唯一变量：
    // 冻结 Schema 与后端 R03 写的是 binding{datasetRef,field}，前端 R07 要求 metricField。
    const withColumns = {
      ...BACKEND_R03_CANONICAL_PROBE,
      layout: { ...BACKEND_R03_CANONICAL_PROBE.layout, columns: 12 },
    };
    const error = captureError(() => parseReportSpec(withColumns));
    expect(error.message).toBe('报表数据不合法：未知字段 binding');
    expect(safeParseReportSpec(withColumns)).toBeNull();
  });

  it.fails(
    '【已确认缺陷 tripwire】冻结 v1 样例与后端 R03 产物都应可被前端加载（修复后本用例会转红，须同步更新记录）',
    () => {
      expect(safeParseReportSpec(FROZEN_V1_SAMPLE)).not.toBeNull();
      expect(safeParseReportSpec(BACKEND_R03_CANONICAL_PROBE)).not.toBeNull();
    },
  );
});

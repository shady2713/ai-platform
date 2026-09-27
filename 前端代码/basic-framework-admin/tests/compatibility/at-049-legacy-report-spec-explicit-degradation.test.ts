/**
 * AT-049（Q08 前端切片）：ReportSpec 版本与形状兼容 —— 冻结样例/后端产物可加载，旧版本明确拒绝。
 *
 * 口径（必须明文保留，禁止把本结论说成"真实 N-1 报表联调"）：
 *  - 仓库里**没有** `schemaVersion` 早于 `1.0` 的真实历史报表产物：`docs/contracts/ai/samples/report-spec.valid.json`
 *    是 F07（2026-09-17）冻结的 v1 样例，版本戳仍是 `1.0`；
 *  - 因此"旧版本"用**冻结样例的等值变体**（只替换 schemaVersion 戳）驱动，验证"不支持的版本必须明确提示"；
 *  - 冻结样例本身（真实冻结产物）与后端 R03 的**规范序列化产物**（metric 用 `binding`、`layout.columns`、
 *    `datasetRefs[].asOf`）必须能被前端加载与渲染 —— 这是 2026-09-27 契约缺陷修复后的正式断言；
 *  - 修复前的错误行为（冻结样例被 `未知字段 asOf` 拒绝、R03 产物被 `布局必须是 12 列栅格` /
 *    `未知字段 binding` 拒绝）不再以 tripwire 形式固化；渲染期"未知字段拒绝"的严格性由独立用例保持。
 *
 * 断言对象是 `packages/ai-chat-ui/src/report`（修复后只读引用其产品源码）。
 */
import { join } from 'node:path';

import { mount } from '@vue/test-utils';

import { describe, expect, it, vi } from 'vitest';

import AiReportView from '../../packages/ai-chat-ui/src/report/AiReportView.vue';
import {
  parseReportSpec,
  safeParseReportSpec,
} from '../../packages/ai-chat-ui/src/report/reportSpec';
import { readJsonFile, repositoryRoot } from './support/workspace';

// 图表适配层在测试里用替身：本套件验证"规格能否加载/布局能否渲染"，厂商图表行为由 R02 测试覆盖
vi.mock('@antv/g2', () => ({
  Chart: class {
    public changeSize = vi.fn();
    public destroy = vi.fn();
    public options = vi.fn();
    public render = vi.fn();
    public constructor(public config: Record<string, unknown>) {}
  },
}));

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
 * 后端 R03 的规范序列化产物（逐键对齐修复后的 `AiReportGenerationStep.specJson`：
 * metric 块只写 `binding{datasetRef,field}`、`layout` 带 `columns=12`、`datasetRefs[]` 带 `asOf`）。
 * asOf 取固定时间戳（后端按生成时刻写入 RFC3339 date-time），这里只冻结形状、不冻结真实运行时刻。
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
      asOf: '2026-09-27T06:00:00Z',
      completeness: 'COMPLETE',
    },
  ],
  layout: {
    columns: 12,
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

/** 冻结样例（F07）对应的版本数据：图/表取值来自绑定结果。 */
const FROZEN_V1_DATA = {
  kind: 'REPORT',
  data: [
    { blockId: 'intro', type: 'text', verified: false },
    {
      blockId: 'sales_chart',
      type: 'chart',
      verified: true,
      points: [
        { customer_name: 'alice', net_amount: '290.00' },
        { customer_name: 'bob', net_amount: '450.00' },
      ],
    },
    {
      blockId: 'sales_table',
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

/** 后端 R03 产物对应的版本数据（metric 取绑定结果里的真实值）。 */
const BACKEND_R03_DATA = {
  kind: 'REPORT',
  data: [
    { blockId: 'intro', type: 'text', verified: false },
    { blockId: 'total', type: 'metric', verified: true, value: '740.00' },
  ],
  datasets: [
    {
      datasetRef: 'sales_result',
      columns: [
        {
          field: 'net_amount',
          label: '净销售额',
          dataType: 'DECIMAL',
          unit: 'CNY',
        },
      ],
      rows: [{ net_amount: '740.00' }],
      completeness: 'COMPLETE',
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
   * 以下两条是 2026-09-27 契约缺陷修复后的**正式断言**（修复前必红）：
   * 冻结的权威 v1 样例（F07）与后端 R03 的规范产物都必须能被前端加载并渲染；
   * 修复前它们分别被 `未知字段 asOf` 与 `布局必须是 12 列栅格` / `未知字段 binding` 拒绝，
   * 报表页会以"报表规格不合法"替代整张报表（用户看不到报表）——缺陷的根因与修复见交接记录。
   */
  it('冻结 v1 样例（F07）可被前端加载并渲染出图/表/文字块', () => {
    const spec = parseReportSpec(FROZEN_V1_SAMPLE);
    expect(spec.title).toBe('2026年8月华东客户净销售额');
    expect(spec.layout.columns).toBe(12);
    expect(spec.datasetRefs[0]?.asOf).toBe('2026-09-16T00:00:00Z');
    expect(safeParseReportSpec(FROZEN_V1_SAMPLE)).not.toBeNull();

    const wrapper = mount(AiReportView, {
      props: {
        data: JSON.stringify(FROZEN_V1_DATA),
        spec: JSON.stringify(FROZEN_V1_SAMPLE),
      },
    });
    expect(wrapper.find('[data-testid="ai-report-invalid"]').exists()).toBe(
      false,
    );
    expect(wrapper.get('[data-testid="ai-report-title"]').text()).toBe(
      '2026年8月华东客户净销售额',
    );
    expect(wrapper.find('[data-testid="ai-report-grid"]').exists()).toBe(true);
    expect(wrapper.find('[data-testid="ai-report-text"]').exists()).toBe(true);
    expect(wrapper.find('[data-testid="ai-report-chart"]').exists()).toBe(true);
    expect(wrapper.get('[data-testid="ai-report-table"]').text()).toContain(
      'alice',
    );
    expect(wrapper.get('[data-testid="ai-report-table"]').text()).not.toContain(
      '不可见列',
    );
  });

  it('后端 R03 规范产物（binding + layout.columns + asOf）可被前端加载并渲染指标', () => {
    const spec = parseReportSpec(BACKEND_R03_CANONICAL_PROBE);
    expect(spec.title).toBe('2026年8月华东客户净销售额');
    expect(spec.layout.columns).toBe(12);
    expect(safeParseReportSpec(BACKEND_R03_CANONICAL_PROBE)).not.toBeNull();

    const wrapper = mount(AiReportView, {
      props: {
        data: JSON.stringify(BACKEND_R03_DATA),
        spec: JSON.stringify(BACKEND_R03_CANONICAL_PROBE),
      },
    });
    expect(wrapper.find('[data-testid="ai-report-invalid"]').exists()).toBe(
      false,
    );
    expect(wrapper.get('[data-testid="ai-report-metric"]').text()).toBe(
      '740 CNY',
    );
  });

  it('既有库旧行兼容：metricField（含缺 asOf 的历史行）仍可加载，不被回退修复误伤', () => {
    // SUPPORTED_SPEC 即旧行形状：metric 块用顶层 datasetRef + metricField，datasetRefs 无 asOf
    const legacy = parseReportSpec(SUPPORTED_SPEC);
    const metric = legacy.blocks.find((block) => block.type === 'metric');
    expect(metric).toMatchObject({
      type: 'metric',
      datasetRef: 'sales_result',
      metricField: 'net_amount',
    });
    expect(legacy.datasetRefs[0]?.asOf).toBeUndefined();
  });

  it('渲染期严格性保持：binding 内与数据集引用里的未知键仍被拒绝', () => {
    const metricWithUnknownBindingKey = {
      ...BACKEND_R03_CANONICAL_PROBE,
      blocks: [
        {
          id: 'total',
          type: 'metric',
          title: '净销售额合计',
          binding: {
            datasetRef: 'sales_result',
            field: 'net_amount',
            note: '未知键',
          },
          rowIndex: 0,
          format: 'CURRENCY',
        },
      ],
      layout: {
        columns: 12,
        gap: 16,
        items: [{ blockId: 'total', row: 0, column: 0, span: 4 }],
      },
    };
    expect(
      captureError(() => parseReportSpec(metricWithUnknownBindingKey)).message,
    ).toBe('报表数据不合法：未知字段 note');

    const datasetRefWithUnknownKey = {
      ...BACKEND_R03_CANONICAL_PROBE,
      datasetRefs: [
        { ...BACKEND_R03_CANONICAL_PROBE.datasetRefs[0], as_of: '别名键' },
      ],
    };
    expect(
      captureError(() => parseReportSpec(datasetRefWithUnknownKey)).message,
    ).toBe('报表数据不合法：未知字段 as_of');
    expect(safeParseReportSpec(datasetRefWithUnknownKey)).toBeNull();
  });
});

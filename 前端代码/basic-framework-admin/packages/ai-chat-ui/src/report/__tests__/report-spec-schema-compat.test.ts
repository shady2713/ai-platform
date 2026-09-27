import { describe, expect, it } from 'vitest';

import { parseReportSpec, safeParseReportSpec } from '../reportSpec';

/**
 * ReportSpec 形状兼容（2026-09-27 契约缺陷修复的包内回归）：
 *  - 权威是 `docs/contracts/ai/report-spec.schema.json`：metric 块用 `binding{datasetRef,field}`，
 *    `layout.columns` 固定 12，`datasetRefs[]` 带 `asOf`（date-time）；
 *  - 前端解析器必须接受该形状，同时兼容既有库旧行的顶层 `datasetRef` + `metricField`（缺 `asOf` 的
 *    历史行不被回退修复误伤）；
 *  - "未知字段拒绝"的严格性保持不变。
 */
const SCHEMA_SHAPED_SPEC = {
  schemaVersion: '1.0',
  title: '华东 8 月销售',
  themeRef: { themeId: 'thm_default', revision: 1 },
  layout: {
    columns: 12,
    gap: 16,
    items: [{ blockId: 'total', row: 0, column: 0, span: 4 }],
  },
  blocks: [
    {
      id: 'total',
      title: '净销售额合计',
      type: 'metric',
      binding: { datasetRef: 'sales_result', field: 'net_amount' },
      rowIndex: 0,
      format: 'CURRENCY',
      unit: 'CNY',
    },
  ],
  datasetRefs: [
    {
      id: 'sales_result',
      resultRef: 'run_1/result/0',
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
  queryRefs: [{ id: 'sales_query', plan: { datasetId: 'dset_sales' } }],
  sources: [
    {
      id: 'src',
      kind: 'DATASET',
      resourceId: 'dset_sales',
      resourceVersion: 1,
      description: '语义数据集',
    },
  ],
};

function captureError(run: () => unknown): Error {
  try {
    run();
  } catch (error) {
    return error as Error;
  }
  throw new Error('预期解析器抛错，但实际通过');
}

describe('reportSpec 形状兼容（冻结 Schema 为权威）', () => {
  it('metric 的 binding{datasetRef,field} 被接受并归一化为内部 metricField', () => {
    const spec = parseReportSpec(SCHEMA_SHAPED_SPEC);
    expect(spec.blocks[0]).toMatchObject({
      type: 'metric',
      datasetRef: 'sales_result',
      metricField: 'net_amount',
      rowIndex: 0,
      format: 'CURRENCY',
      unit: 'CNY',
    });
    expect(spec.datasetRefs[0]?.asOf).toBe('2026-09-27T06:00:00Z');
  });

  it('既有库旧行（顶层 datasetRef + metricField、无 asOf）仍可加载', () => {
    const legacy = {
      ...SCHEMA_SHAPED_SPEC,
      blocks: [
        {
          id: 'total',
          title: '净销售额合计',
          type: 'metric',
          datasetRef: 'sales_result',
          metricField: 'net_amount',
          rowIndex: 0,
          format: 'CURRENCY',
        },
      ],
      datasetRefs: SCHEMA_SHAPED_SPEC.datasetRefs.map(
        ({ asOf: _asOf, ...ref }) => ref,
      ),
    };
    const spec = parseReportSpec(legacy);
    expect(spec.blocks[0]).toMatchObject({
      type: 'metric',
      datasetRef: 'sales_result',
      metricField: 'net_amount',
    });
    expect(spec.datasetRefs[0]?.asOf).toBeUndefined();
  });

  it('修复前 R03 的混合产物（binding + 一致的顶层 datasetRef）仍可加载', () => {
    const mixed = {
      ...SCHEMA_SHAPED_SPEC,
      blocks: [
        {
          ...SCHEMA_SHAPED_SPEC.blocks[0],
          datasetRef: 'sales_result',
        },
      ],
    };
    expect(parseReportSpec(mixed).blocks[0]).toMatchObject({
      type: 'metric',
      datasetRef: 'sales_result',
      metricField: 'net_amount',
    });
  });

  it('asOf 必须是 RFC3339 date-time（非法文本/非字符串一律拒绝）', () => {
    for (const asOf of [
      '2026-09-27',
      '2026/09/27 06:00',
      '2026-13-01T00:00:00Z',
      '昨天',
      1_790_000_000,
    ]) {
      const tampered = {
        ...SCHEMA_SHAPED_SPEC,
        datasetRefs: [{ ...SCHEMA_SHAPED_SPEC.datasetRefs[0], asOf }],
      };
      expect(captureError(() => parseReportSpec(tampered)).message).toBe(
        '报表数据不合法：数据截至时间不合法',
      );
      expect(safeParseReportSpec(tampered)).toBeNull();
    }
  });

  it('binding 缺字段/未知键、与顶层 datasetRef 不一致时拒绝（严格性保持）', () => {
    const missingField = {
      ...SCHEMA_SHAPED_SPEC,
      blocks: [
        {
          ...SCHEMA_SHAPED_SPEC.blocks[0],
          binding: { datasetRef: 'sales_result' },
        },
      ],
    };
    expect(captureError(() => parseReportSpec(missingField)).message).toBe(
      '报表数据不合法：逻辑标识不合法',
    );

    const unknownBindingKey = {
      ...SCHEMA_SHAPED_SPEC,
      blocks: [
        {
          ...SCHEMA_SHAPED_SPEC.blocks[0],
          binding: {
            datasetRef: 'sales_result',
            field: 'net_amount',
            extra: true,
          },
        },
      ],
    };
    expect(captureError(() => parseReportSpec(unknownBindingKey)).message).toBe(
      '报表数据不合法：未知字段 extra',
    );

    const conflictingDatasetRef = {
      ...SCHEMA_SHAPED_SPEC,
      blocks: [
        {
          ...SCHEMA_SHAPED_SPEC.blocks[0],
          datasetRef: 'another_result',
        },
      ],
      datasetRefs: [
        ...SCHEMA_SHAPED_SPEC.datasetRefs,
        {
          ...SCHEMA_SHAPED_SPEC.datasetRefs[0],
          id: 'another_result',
        },
      ],
    };
    expect(
      captureError(() => parseReportSpec(conflictingDatasetRef)).message,
    ).toBe('报表数据不合法：指标绑定数据集不一致');
    expect(safeParseReportSpec(conflictingDatasetRef)).toBeNull();
  });

  it('metric 既无 binding 也无 metricField 时明确拒绝', () => {
    const noBinding = {
      ...SCHEMA_SHAPED_SPEC,
      blocks: [
        {
          id: 'total',
          title: '净销售额合计',
          type: 'metric',
          datasetRef: 'sales_result',
          rowIndex: 0,
          format: 'CURRENCY',
        },
      ],
    };
    expect(captureError(() => parseReportSpec(noBinding)).message).toBe(
      '报表数据不合法：逻辑标识不合法',
    );
  });
});

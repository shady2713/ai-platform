import { describe, expect, it } from 'vitest';

import {
  AI_REPORT_PERMISSIONS,
  canRefresh,
  previewFrom,
  refreshResultText,
  REPORT_MODE_TEXT,
  revisionResultText,
  versionOptions,
} from './data';

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

const data = {
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

describe('个人报表页面数据层', () => {
  it('预览组装：规格与数据都再解析一次，非法即拒绝', () => {
    const preview = previewFrom(
      {
        id: 1,
        reportId: 71,
        versionNo: 2,
        mode: 'SNAPSHOT',
        specJson: JSON.stringify(spec),
        dataJson: JSON.stringify(data),
        completeness: 'COMPLETE',
        asOf: new Date('2026-09-23T10:00:00'),
      },
      undefined,
    );
    expect(preview.spec?.title).toBe('华东 8 月销售');
    expect(preview.data?.data).toHaveLength(1);
    expect(preview.completeness).toBe('COMPLETE');
    expect(preview.asOfText).toBe('2026-09-23 10:00');
    expect(preview.failureReason).toBe('');

    const broken = previewFrom(
      {
        id: 1,
        reportId: 71,
        versionNo: 1,
        mode: 'SNAPSHOT',
        specJson: '{"schemaVersion":"2.0"}',
      },
      undefined,
    );
    expect(broken.spec).toBeNull();
    expect(broken.data).toBeNull();
  });

  it('可刷新版本的数据来自刷新尝试，失败状态带原因与时间', () => {
    const preview = previewFrom(
      {
        id: 1,
        reportId: 71,
        versionNo: 2,
        mode: 'REFRESHABLE',
        specJson: JSON.stringify(spec),
      },
      {
        attempted: true,
        status: 'FAILED',
        reason: '1003006029',
        asOf: new Date('2026-09-23T11:30:00'),
        completeness: 'PARTIAL',
        dataJson: JSON.stringify(data),
      },
    );
    expect(preview.data?.data).toHaveLength(1);
    expect(preview.completeness).toBe('PARTIAL');
    expect(preview.failureReason).toContain('刷新失败：1003006029');
    expect(preview.failureReason).toContain('2026-09-23 11:30');
    expect(preview.asOfText).toBe('2026-09-23 11:30');
  });

  it('刷新与修订结果映射为可读文案（失败原因与澄清追问都要能读懂）', () => {
    expect(
      refreshResultText({
        status: 'OK',
        asOf: new Date('2026-09-23T10:00:00'),
      }),
    ).toContain('刷新成功');
    expect(refreshResultText({ status: 'UNCHANGED' })).toContain('数据未变化');
    const failed = refreshResultText({
      status: 'FAILED',
      reason: '1003006018',
    });
    expect(failed).toContain('已保留上一次结果');
    expect(failed).toContain('1003006018');

    const applied = revisionResultText({
      outcome: 'APPLIED',
      diff: { modifiedBlocks: ['sales_chart'], addedBlocks: ['rev_block_1'] },
    });
    expect(applied).toContain('已应用');
    expect(applied).toContain('改 sales_chart');
    expect(applied).toContain('增 rev_block_1');

    const clarification = revisionResultText({
      outcome: 'CLARIFICATION',
      clarificationQuestion: '“销售额”指净额还是含退款金额？',
      clarificationCandidates: [{ code: 'total_net_amount', label: '净额' }],
    });
    expect(clarification).toContain('需要澄清');
    expect(clarification).toContain('净额');
  });

  it('版本选项与刷新可用性来自服务端事实', () => {
    expect(
      versionOptions([
        { id: 1, versionNo: 2, mode: 'REFRESHABLE', completeness: 'PARTIAL' },
        { id: 2, versionNo: 1, mode: 'REFRESHABLE' },
      ]),
    ).toEqual([
      { label: 'v2 · PARTIAL', value: 2 },
      { label: 'v1', value: 1 },
    ]);

    expect(canRefresh({ mode: 'REFRESHABLE' } as never)).toBe(true);
    expect(canRefresh({ mode: 'SNAPSHOT' } as never)).toBe(false);
    expect(canRefresh(undefined)).toBe(false);
    expect(REPORT_MODE_TEXT.REFRESHABLE).toContain('按当前权限重新执行');
    expect(AI_REPORT_PERMISSIONS.preview).toBe('ai:report:preview');
  });
});

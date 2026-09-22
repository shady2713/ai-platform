import { describe, expect, it } from 'vitest';

import {
  AI_KNOWLEDGE_PERMISSIONS,
  describeDocumentStatus,
  describeFailureReason,
  DOCUMENT_STATUS_TEXT,
  FAILURE_REASON_TEXT,
  needsAttention,
  useDocumentGridColumns,
  useFormSchema,
  useGridColumns,
  VISIBILITY_OPTIONS,
} from './data';

describe('ai knowledge data', () => {
  it('权限码与 V72/V75 迁移种子一致', () => {
    expect(AI_KNOWLEDGE_PERMISSIONS).toEqual({
      create: 'ai:knowledge:create',
      debug: 'ai:knowledge:debug',
      delete: 'ai:knowledge:delete',
      ingest: 'ai:knowledge:ingest',
      query: 'ai:knowledge:query',
      update: 'ai:knowledge:update',
      version: 'ai:knowledge:version',
    });
  });

  it('文档状态与失败原因都有可读文案，未知值原样返回', () => {
    expect(describeDocumentStatus('READY')).toContain('可用');
    expect(describeDocumentStatus('FAILED')).toContain('可重试');
    expect(describeDocumentStatus('DELETING')).toContain('删除中');
    expect(describeDocumentStatus('PENDING')).toContain('排队');
    expect(describeDocumentStatus('NEW_STATE')).toBe('NEW_STATE');
    expect(describeDocumentStatus(undefined)).toBe('-');

    expect(describeFailureReason('parse-ocr_required')).toContain('OCR');
    expect(describeFailureReason('embed-dimension_mismatch')).toContain('维度');
    expect(describeFailureReason('unknown-reason')).toBe('unknown-reason');
    expect(describeFailureReason(undefined)).toBe('-');
    expect(Object.keys(DOCUMENT_STATUS_TEXT)).toEqual(
      expect.arrayContaining(['FAILED', 'DELETING', 'READY']),
    );
    expect(Object.keys(FAILURE_REASON_TEXT).length).toBeGreaterThan(5);
  });

  it('需要关注的状态（失败/删除中/需 OCR）被标记', () => {
    expect(needsAttention({ id: 1, status: 'FAILED' } as never)).toBe(true);
    expect(needsAttention({ id: 1, status: 'DELETING' } as never)).toBe(true);
    expect(
      needsAttention({
        id: 1,
        parseNote: '扫描件需要 OCR',
        status: 'READY',
      } as never),
    ).toBe(true);
    expect(needsAttention({ id: 1, status: 'READY' } as never)).toBe(false);
  });

  it('可见性只有共享与应用专用两种（没有"公开"选项）', () => {
    expect(VISIBILITY_OPTIONS.map((option) => option.value)).toEqual([
      'SHARED',
      'APPLICATION',
    ]);
  });

  it('知识库表单把标识/可见性/嵌入模型与维度在编辑态禁用', () => {
    const editSchema = useFormSchema(true);
    const createSchema = useFormSchema(false);
    for (const field of [
      'code',
      'visibility',
      'embeddingModel',
      'embeddingDimension',
    ]) {
      const editField = editSchema.find((item) => item.fieldName === field);
      const createField = createSchema.find((item) => item.fieldName === field);
      expect(
        (editField?.componentProps as { disabled?: boolean })?.disabled,
      ).toBe(true);
      expect(
        (createField?.componentProps as { disabled?: boolean })?.disabled,
      ).toBeFalsy();
    }
  });

  it('表格列覆盖状态与失败原因（可读映射）', () => {
    const baseColumns = (useGridColumns() ?? []).map(
      (column) => column.field ?? '',
    );
    expect(baseColumns).toEqual(
      expect.arrayContaining(['code', 'embeddingModel', 'activeGenerationNo']),
    );
    const documentColumns = useDocumentGridColumns() ?? [];
    expect(documentColumns.map((column) => column.field ?? '')).toEqual(
      expect.arrayContaining(['status', 'failureReason', 'activeVersionNo']),
    );
    const statusColumn = documentColumns.find(
      (column) => column.field === 'status',
    );
    const format = statusColumn?.formatter as
      | ((params: { row: { status: string } }) => string)
      | undefined;
    expect(format?.({ row: { status: 'FAILED' } }) ?? '').toContain('可重试');
  });
});

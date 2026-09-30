import { describe, expect, it } from 'vitest';

import {
  AI_SEMANTIC_PERMISSIONS,
  BLOCKING_HINT,
  formatWindow,
  MATCH_METHOD_OPTIONS,
  NO_NAME_INFERENCE_HINT,
  nowIsoSeconds,
  OBJECT_STATUS_LABELS,
  OBJECT_TYPE_OPTIONS,
  PROBLEM_LABELS,
  problemTagType,
  reverseConclusion,
  REVISION_STATUS_LABELS,
  toIsoSeconds,
  useEntryFormSchema,
  useGridColumns,
  useGridFormSchema,
  useObjectFormSchema,
  useRevisionFormSchema,
} from './data';

describe('主数据映射页面契约（Y02）', () => {
  it('权限码与 V93 菜单种子一致', () => {
    expect(AI_SEMANTIC_PERMISSIONS.query).toBe('ai:semantic:query');
    expect(AI_SEMANTIC_PERMISSIONS.manage).toBe('ai:semantic:manage');
  });

  it('词表与服务端枚举一一对应，且没有"按名称匹配"的入口', () => {
    expect(OBJECT_TYPE_OPTIONS.map((option) => option.value)).toEqual([
      'CUSTOMER',
      'SUPPLIER',
      'PRODUCT',
      'EMPLOYEE',
      'ORGANIZATION',
      'OTHER',
    ]);
    expect(MATCH_METHOD_OPTIONS.map((option) => option.value)).toEqual([
      'MANUAL',
      'TRUSTED_FEED',
    ]);
    expect(Object.keys(PROBLEM_LABELS)).toEqual([
      'CONFLICT',
      'EXPIRED',
      'NONE',
    ]);
    expect(Object.keys(REVISION_STATUS_LABELS)).toEqual(['DRAFT', 'PUBLISHED']);
    expect(OBJECT_STATUS_LABELS.DISABLED).toContain('阻断');
    expect(NO_NAME_INFERENCE_HINT).toContain('同名不会合并');
    expect(BLOCKING_HINT).toContain('不会替你挑一个');
  });

  it('时间与有效期格式化与后端语义一致（为空=长期有效）', () => {
    const value = toIsoSeconds(new Date(2026, 0, 2, 3, 4, 5));
    expect(value).toBe('2026-01-02T03:04:05');
    expect(nowIsoSeconds()).toMatch(/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}$/);
    expect(formatWindow('2026-01-01T00:00:00', undefined)).toBe(
      '2026-01-01 00:00:00 ~ 长期有效',
    );
    expect(formatWindow('2026-01-01T00:00:00', '2026-06-01T00:00:00')).toBe(
      '2026-01-01 00:00:00 ~ 2026-06-01 00:00:00',
    );
    expect(formatWindow(undefined, undefined)).toBe('未设置 ~ 长期有效');
  });

  it('问题用颜色区分但不隐藏，未登记也是有效结论', () => {
    expect(problemTagType('CONFLICT')).toBe('danger');
    expect(problemTagType('EXPIRED')).toBe('warning');
    expect(problemTagType('NONE')).toBe('success');
    expect(
      reverseConclusion({
        applicationId: 7,
        asOf: '2026-09-01T00:00:00',
        entityType: 'customer',
        mapped: false,
        reason: 'NOT_REGISTERED',
        sourceKey: 'C-1003',
      }),
    ).toContain('未登记');
    expect(
      reverseConclusion({
        applicationId: 7,
        asOf: '2026-09-01T00:00:00',
        entityType: 'customer',
        mapped: true,
        objectCode: 'md_cloud_qi',
        objectName: '云启科技（统一客户）',
        sourceKey: 'C-1001',
      }),
    ).toContain('md_cloud_qi');
  });

  it('表单与列定义覆盖必填事实（源键、有效期、判定时刻）', () => {
    const entrySchema = useEntryFormSchema();
    const fields = entrySchema.map((item) => item.fieldName);
    expect(fields).toEqual([
      'applicationId',
      'entityType',
      'sourceKey',
      'sourceName',
      'matchMethod',
      'validFrom',
      'validTo',
    ]);
    expect(useRevisionFormSchema().map((item) => item.fieldName)).toEqual([
      'validFrom',
      'validTo',
    ]);
    expect(useObjectFormSchema().map((item) => item.fieldName)).toContain(
      'objectCode',
    );
    expect(useGridColumns()?.length ?? 0).toBeGreaterThanOrEqual(6);
    expect(useGridFormSchema().map((item) => item.fieldName)).toEqual([
      'keyword',
      'objectType',
      'status',
    ]);
  });
});

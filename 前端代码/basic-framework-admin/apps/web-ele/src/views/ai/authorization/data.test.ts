import { describe, expect, it } from 'vitest';

import {
  buildResourceOptions,
  GRANT_ACTIONS,
  GRANT_RESOURCE_TYPES,
  NEW_RESOURCE_CONFIRM,
  PERMISSIONS,
  SCOPE_WARNING,
  useFormSchema,
  useGridColumns,
  useGridFormSchema,
} from './data';

/** A09 授权页面数据契约：独立权限码、作用范围提示、多选资源只列有权项。 */
describe('ai authorization page data', () => {
  it('uses dedicated grant permissions matching migration seeds', () => {
    expect(PERMISSIONS).toEqual({
      create: 'ai:grant:create',
      query: 'ai:grant:query',
      revoke: 'ai:grant:revoke',
      update: 'ai:grant:update',
    });
  });

  it('warns about the blast radius of authorization changes', () => {
    expect(SCOPE_WARNING).toContain('立即生效');
    expect(SCOPE_WARNING).toContain('历史产物');
    expect(NEW_RESOURCE_CONFIRM).toContain('确认');
  });

  it('only lists already-authorized resources as options', () => {
    const options = buildResourceOptions([
      { resourceKey: 'report-1', resourceType: 'REPORT' },
      { resourceKey: 'report-1', resourceType: 'REPORT' },
      { resourceKey: 'kb-1', resourceType: 'KNOWLEDGE_BASE' },
    ]);

    expect(options.map((option) => option.value)).toEqual(['report-1', 'kb-1']);
    expect(options.every((option) => option.label.includes('已授权'))).toBe(
      true,
    );
    // 未授权资源不会出现在选项里（只能显式输入并二次确认）
    expect(options.map((option) => option.value)).not.toContain('report-2');
  });

  it('exposes complete search, form and column schemas', () => {
    expect(useGridFormSchema().map((item) => item.fieldName)).toEqual([
      'applicationId',
      'subjectType',
      'externalUserId',
      'resourceType',
    ]);
    expect(useFormSchema().map((item) => item.fieldName)).toEqual([
      'id',
      'applicationId',
      'subjectType',
      'externalUserId',
      'resourceType',
      'resourceKey',
      'actions',
      'version',
    ]);
    const columns = useGridColumns() ?? [];
    expect(columns.map((column) => column.field)).toEqual([
      'applicationId',
      'subjectType',
      'externalUserId',
      'resourceType',
      'resourceKey',
      'actions',
      'status',
      'authzRevision',
    ]);
    // 动作列把白名单渲染成可读文本
    const actionsColumn = columns.find((column) => column.field === 'actions');
    expect(
      (actionsColumn?.formatter as (params: { cellValue: unknown }) => string)({
        cellValue: ['READ', 'EXPORT'],
      }),
    ).toBe('READ、EXPORT');
    expect(
      (actionsColumn?.formatter as (params: { cellValue: unknown }) => string)({
        cellValue: undefined,
      }),
    ).toBe('');
  });

  it('offers the catalogue vocabulary for actions and resource types', () => {
    expect(GRANT_ACTIONS.map((item) => item.value)).toEqual([
      'READ',
      'EXECUTE',
      'EXPORT',
    ]);
    expect(GRANT_RESOURCE_TYPES.map((item) => item.value)).toEqual([
      'REPORT',
      'KNOWLEDGE_BASE',
      'FILE',
      'TOOL',
      'DATASET',
    ]);
    const schema = useFormSchema();
    expect(schema.find((item) => item.fieldName === 'actions')?.rules).toBe(
      'required',
    );
    expect(
      schema.find((item) => item.fieldName === 'resourceType')?.rules,
    ).toBe('required');
  });
});

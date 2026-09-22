import { describe, expect, it } from 'vitest';

import {
  AI_TOOL_PERMISSIONS,
  describePolicy,
  TOOL_POLICY_OPTIONS,
  TOOL_TYPE_OPTIONS,
  useFormSchema,
  useGridColumns,
  useGridFormSchema,
  useVersionFormSchema,
} from './data';

describe('ai tool data', () => {
  it('权限码与 V70 迁移种子一致', () => {
    expect(AI_TOOL_PERMISSIONS).toEqual({
      create: 'ai:tool:create',
      delete: 'ai:tool:delete',
      query: 'ai:tool:query',
      update: 'ai:tool:update',
      version: 'ai:tool:version',
    });
  });

  it('政策默认 DENY 且首期只允许读工具（界面显式选择，没有"临时放开"旁路）', () => {
    const versionSchema = useVersionFormSchema();
    const policy = versionSchema.find(
      (schema) => schema.fieldName === 'policy',
    );
    expect(policy?.defaultValue).toBe('DENY');
    expect(TOOL_POLICY_OPTIONS.map((option) => option.value)).toEqual([
      'DENY',
      'CONFIRM',
      'AUTO',
    ]);
    expect(TOOL_TYPE_OPTIONS.map((option) => option.value)).toEqual([
      'READ',
      'WRITE',
    ]);
    const toolType = versionSchema.find(
      (schema) => schema.fieldName === 'toolType',
    );
    expect(toolType?.defaultValue).toBe('READ');
  });

  it('政策与类型说明可读，DENY 明确标为默认', () => {
    expect(describePolicy('DENY', 'READ')).toContain('禁止执行（默认）');
    expect(describePolicy('CONFIRM', 'READ')).toContain('需人工确认后执行');
    expect(describePolicy('AUTO', 'WRITE')).toContain('写工具');
  });

  it('表单与版本 schema 都是声明式字段（无 SQL/脚本输入面）', () => {
    const fields = [
      ...useFormSchema().map((schema) => schema.fieldName),
      ...useVersionFormSchema().map((schema) => schema.fieldName),
    ];
    expect(fields).toContain('sourceRef');
    expect(fields).toContain('inputSchemaJson');
    expect(
      fields.some((field) =>
        /(?:^|[^a-z])(?:sql|script|statement)(?:[^a-z]|$)/i.test(String(field)),
      ),
    ).toBe(false);
  });

  it('列与搜索表单覆盖连接器与状态', () => {
    const columns = useGridColumns() ?? [];
    expect(columns.map((column) => column.field)).toContain('connectorId');
    const statusColumn = columns.find((column) => column.field === 'status');
    expect(
      (statusColumn?.formatter as (params: { cellValue: string }) => string)({
        cellValue: 'ENABLED',
      }),
    ).toBe('启用');
    expect(useGridFormSchema().map((schema) => schema.fieldName)).toEqual([
      'code',
      'connectorId',
      'status',
    ]);
  });
});

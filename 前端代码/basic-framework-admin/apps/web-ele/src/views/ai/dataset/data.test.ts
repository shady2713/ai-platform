import { describe, expect, it } from 'vitest';

import {
  AI_DATASET_PERMISSIONS,
  describeVerification,
  useFormSchema,
  useGridColumns,
  useGridFormSchema,
  useVersionFormSchema,
} from './data';

describe('ai dataset data', () => {
  it('权限码与 V68 迁移种子一致', () => {
    expect(AI_DATASET_PERMISSIONS).toEqual({
      create: 'ai:dataset:create',
      delete: 'ai:dataset:delete',
      query: 'ai:dataset:query',
      update: 'ai:dataset:update',
      versionCreate: 'ai:dataset:version:create',
      versionPublish: 'ai:dataset:version:publish',
      versionVerify: 'ai:dataset:version:verify',
    });
  });

  it('来源对象与语义定义都是声明式字段（无 SQL/脚本输入面）', () => {
    const fields = [
      ...useFormSchema().map((schema) => schema.fieldName),
      ...useVersionFormSchema().map((schema) => schema.fieldName),
    ];
    expect(fields).toContain('sourceObject');
    expect(fields).toContain('definitionJson');
    expect(
      fields.some((field) =>
        /(?:^|[^a-z])(?:sql|script|statement)(?:[^a-z]|$)/i.test(String(field)),
      ),
    ).toBe(false);
  });

  it('列与搜索表单覆盖来源对象与状态', () => {
    const columns = useGridColumns() ?? [];
    expect(columns.map((column) => column.field)).toContain('sourceObject');
    const statusColumn = columns.find((column) => column.field === 'status');
    expect(
      (statusColumn?.formatter as (params: { cellValue: string }) => string)({
        cellValue: 'DISABLED',
      }),
    ).toBe('停用');
    expect(useGridFormSchema().map((schema) => schema.fieldName)).toEqual([
      'code',
      'connectorId',
      'status',
    ]);
  });

  it('验证结论把不可发布原因说清楚（缺列/类型不兼容/仅新增列）', () => {
    expect(
      describeVerification({
        addedColumns: [],
        missingColumns: [],
        publishable: true,
        schemaHash: 'a',
        status: 'DRAFT',
        typeChangedColumns: [],
        verificationStatus: 'VERIFIED',
        versionId: 1,
        versionNo: 1,
      }),
    ).toBe('可发布');

    expect(
      describeVerification({
        addedColumns: ['channel'],
        missingColumns: [],
        publishable: true,
        schemaHash: 'a',
        status: 'DRAFT',
        typeChangedColumns: [],
        verificationStatus: 'VERIFIED',
        versionId: 1,
        versionNo: 1,
      }),
    ).toContain('上游新增列：channel');

    const blocked = describeVerification({
      addedColumns: [],
      missingColumns: ['status'],
      publishable: false,
      schemaHash: 'a',
      status: 'DRAFT',
      typeChangedColumns: ['amount'],
      verificationStatus: 'DRIFTED',
      versionId: 1,
      versionNo: 1,
    });
    expect(blocked).toContain('上游缺少列：status');
    expect(blocked).toContain('类型不再兼容：amount');

    expect(
      describeVerification({
        addedColumns: [],
        missingColumns: [],
        publishable: false,
        schemaHash: 'a',
        status: 'DRAFT',
        typeChangedColumns: [],
        verificationStatus: 'UNVERIFIED',
        versionId: 1,
        versionNo: 1,
      }),
    ).toBe('不可发布');
  });
});

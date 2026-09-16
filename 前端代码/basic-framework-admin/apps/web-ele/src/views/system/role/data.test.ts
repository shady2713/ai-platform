import { describe, expect, it, vi } from 'vitest';

import {
  useAssignDataPermissionFormSchema,
  useAssignMenuFormSchema,
  useFormSchema,
  useGridColumns,
  useGridFormSchema,
} from './data';

vi.mock('@vben/constants', () => ({
  CommonStatusEnum: { ENABLE: 0 },
  DICT_TYPE: {
    COMMON_STATUS: 'common_status',
    SYSTEM_DATA_SCOPE: 'system_data_scope',
    SYSTEM_ROLE_TYPE: 'system_role_type',
  },
  SystemDataScopeEnum: { DEPT_CUSTOM: 2 },
}));

vi.mock('@vben/hooks', () => ({ getDictOptions: vi.fn(() => []) }));

vi.mock('#/adapter/form', async () => {
  const { z } = await import('@vben/common-ui');
  return { z };
});

vi.mock('#/utils', () => ({
  getRangePickerDefaultProps: () => ({ format: 'YYYY-MM-DD HH:mm:ss' }),
}));

type ValuesPredicate = (values: Record<string, unknown>) => boolean;

describe('system role schemas', () => {
  it('shows department selection only for custom department scope', () => {
    const schema = useAssignDataPermissionFormSchema();
    const departmentIds = schema.find(
      ({ fieldName }) => fieldName === 'dataScopeDeptIds',
    );
    const show = departmentIds?.dependencies?.show as ValuesPredicate;

    expect(show({ dataScope: 2 })).toBe(true);
    expect(show({ dataScope: 1 })).toBe(false);
    expect(schema.find(({ fieldName }) => fieldName === 'name')).toMatchObject({
      componentProps: { disabled: true },
    });
  });

  it('keeps create, assignment, search and table field contracts complete', () => {
    expect(useFormSchema().map(({ fieldName }) => fieldName)).toEqual([
      'id',
      'name',
      'code',
      'sort',
      'status',
      'remark',
    ]);
    expect(useAssignMenuFormSchema().map(({ fieldName }) => fieldName)).toEqual(
      ['id', 'name', 'code', 'menuIds'],
    );

    const createTime = useGridFormSchema().find(
      ({ fieldName }) => fieldName === 'createTime',
    );
    expect(createTime?.componentProps).toMatchObject({
      clearable: true,
      format: 'YYYY-MM-DD HH:mm:ss',
    });
    expect(useGridColumns()?.some(({ field }) => field === 'code')).toBe(true);
  });
});

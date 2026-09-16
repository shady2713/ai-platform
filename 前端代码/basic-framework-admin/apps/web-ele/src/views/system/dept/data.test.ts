import { beforeEach, describe, expect, it, vi } from 'vitest';

import { getDeptList } from '#/api/system/dept';
import { getSimpleUserList } from '#/api/system/user';

import {
  attachDepartmentLeaderNames,
  useFormSchema,
  useGridColumns,
} from './data';

vi.mock('@vben/constants', () => ({
  CommonStatusEnum: { ENABLE: 0 },
  DICT_TYPE: { COMMON_STATUS: 'common_status' },
}));

vi.mock('@vben/hooks', () => ({ getDictOptions: vi.fn(() => []) }));
vi.mock('@vben/utils', () => ({ handleTree: (value: unknown) => value }));

vi.mock('#/adapter/form', async () => {
  const { z } = await import('@vben/common-ui');
  return {
    buildOptionalEmailSchema: () => 'optionalEmail',
    buildOptionalMobileSchema: () => 'optionalMobile',
    z,
  };
});

vi.mock('#/api/system/dept', () => ({ getDeptList: vi.fn() }));
vi.mock('#/api/system/user', () => ({ getSimpleUserList: vi.fn() }));

describe('system department view data', () => {
  beforeEach(() => {
    vi.mocked(getDeptList).mockResolvedValue([
      {
        createTime: new Date('2026-01-01T00:00:00Z'),
        email: '',
        id: 7,
        leaderUserId: 11,
        name: '研发部',
        phone: '',
        sort: 1,
        status: 0,
      },
    ]);
    vi.mocked(getSimpleUserList).mockResolvedValue([]);
  });

  it('joins leader display names without mutating API records', () => {
    const departments = [
      {
        createTime: new Date('2026-01-01T00:00:00Z'),
        email: '',
        id: 7,
        leaderUserId: 11,
        name: '研发部',
        phone: '',
        sort: 1,
        status: 0,
      },
      {
        createTime: new Date('2026-01-01T00:00:00Z'),
        email: '',
        id: 8,
        leaderUserId: null,
        name: '未分配部门',
        phone: '',
        sort: 2,
        status: 0,
      },
    ];

    const result = attachDepartmentLeaderNames(departments, [
      { id: 11, nickname: '负责人' },
    ]);

    expect(result.map(({ leaderUserName }) => leaderUserName)).toEqual([
      '负责人',
      '-',
    ]);
    expect(departments[0]).not.toHaveProperty('leaderUserName');
  });

  it('builds parent choices and uses the enriched leader column', async () => {
    const form = useFormSchema();
    const parent = form.find(({ fieldName }) => fieldName === 'parentId');
    const parentApi = (
      parent?.componentProps as { api: () => Promise<unknown> }
    ).api;

    await expect(parentApi()).resolves.toEqual([
      { id: 0, name: '顶级部门' },
      expect.objectContaining({ id: 7, name: '研发部' }),
    ]);
    expect(form.find(({ fieldName }) => fieldName === 'phone')?.rules).toBe(
      'optionalMobile',
    );
    expect(useGridColumns()?.map(({ field }) => field)).toContain(
      'leaderUserName',
    );
  });
});

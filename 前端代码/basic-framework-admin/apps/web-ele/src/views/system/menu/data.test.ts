import { beforeEach, describe, expect, it, vi } from 'vitest';

import { getMenuList } from '#/api/system/menu';

import { useFormSchema, useGridColumns } from './data';

vi.mock('@vben/constants', () => ({
  CommonStatusEnum: { ENABLE: 0 },
  DICT_TYPE: {
    COMMON_STATUS: 'common_status',
    SYSTEM_MENU_TYPE: 'system_menu_type',
  },
  SystemMenuTypeEnum: { DIR: 1, MENU: 2, BUTTON: 3 },
}));

vi.mock('@vben/hooks', () => ({
  getDictOptions: vi.fn(() => []),
}));

vi.mock('@vben/icons', () => ({ IconifyIcon: 'IconifyIcon' }));

vi.mock('@vben/utils', () => ({
  handleTree: vi.fn((value: unknown) => value),
  isHttpUrl: (value: unknown) =>
    typeof value === 'string' && /^https?:\/\//.test(value),
}));

vi.mock('#/adapter/form', async () => {
  const { z } = await import('@vben/common-ui');
  return { z };
});

vi.mock('#/api/system/menu', () => ({
  getMenuList: vi.fn(),
}));

vi.mock('#/locales', () => ({
  $t: (value: string) => (value === 'menu.user' ? '用户管理' : value),
}));

vi.mock('#/router/routes', () => ({
  componentKeys: ['SystemUser', 'SystemRole'],
}));

type ValuesPredicate = (values: Record<string, unknown>) => boolean;
type DynamicRules = (values: Record<string, unknown>) => {
  safeParse: (value: unknown) => { success: boolean };
};

describe('system menu schema', () => {
  beforeEach(() => {
    vi.mocked(getMenuList).mockResolvedValue([
      {
        alwaysShow: true,
        component: 'system/user/index',
        createTime: new Date('2026-01-01T00:00:00Z'),
        icon: 'users',
        id: 1,
        keepAlive: true,
        name: 'menu.user',
        parentId: 0,
        path: 'user',
        permission: 'system:user:list',
        sort: 1,
        status: 0,
        type: 2,
        visible: true,
      },
    ]);
  });

  it('enforces menu-type visibility and route path semantics', () => {
    const schema = useFormSchema();
    const fields = schema.map(({ fieldName }) => fieldName);

    expect(new Set(fields).size).toBe(fields.length);
    expect(fields).toEqual(
      expect.arrayContaining([
        'parentId',
        'name',
        'type',
        'path',
        'component',
        'permission',
      ]),
    );

    const icon = schema.find(({ fieldName }) => fieldName === 'icon');
    const component = schema.find(({ fieldName }) => fieldName === 'component');
    expect((icon?.dependencies?.show as ValuesPredicate)({ type: 1 })).toBe(
      true,
    );
    expect((icon?.dependencies?.show as ValuesPredicate)({ type: 3 })).toBe(
      false,
    );
    expect((icon?.dependencies?.show as ValuesPredicate)({ type: '1' })).toBe(
      false,
    );
    expect(
      (component?.dependencies?.show as ValuesPredicate)({ type: 2 }),
    ).toBe(true);

    const path = schema.find(({ fieldName }) => fieldName === 'path');
    const rules = path?.dependencies?.rules as unknown as DynamicRules;
    expect(
      rules({ parentId: 0, path: '/system' }).safeParse('/system').success,
    ).toBe(true);
    expect(
      rules({ parentId: 0, path: 'system' }).safeParse('system').success,
    ).toBe(false);
    expect(rules({ parentId: 1, path: 'user' }).safeParse('user').success).toBe(
      true,
    );
    expect(
      rules({ parentId: 1, path: '/user' }).safeParse('/user').success,
    ).toBe(false);
    expect(
      rules({ parentId: 0, path: 'https://example.com' }).safeParse(
        'https://example.com',
      ).success,
    ).toBe(true);
  });

  it('builds parent options, localized filtering, autocomplete and grid columns', async () => {
    const schema = useFormSchema();
    const parent = schema.find(({ fieldName }) => fieldName === 'parentId');
    const parentProps = parent?.componentProps as {
      api: () => Promise<Array<{ id: number; name: string }>>;
      filterTreeNode: (input: string, node: { label?: string }) => boolean;
    };

    await expect(parentProps.api()).resolves.toEqual([
      { id: 0, name: '顶级部门' },
      expect.objectContaining({ id: 1, name: 'menu.user', parentId: 0 }),
    ]);
    expect(parentProps.filterTreeNode('', {})).toBe(true);
    expect(parentProps.filterTreeNode('用户', { label: 'menu.user' })).toBe(
      true,
    );
    expect(parentProps.filterTreeNode('missing', {})).toBe(false);

    const componentName = schema.find(
      ({ fieldName }) => fieldName === 'componentName',
    );
    const suggestions = componentName?.componentProps as {
      fetchSuggestions: (
        query: string,
        callback: (options: Array<{ value: string }>) => void,
      ) => void;
    };
    const callback = vi.fn();
    suggestions.fetchSuggestions('role', callback);
    expect(callback).toHaveBeenCalledWith([{ value: 'SystemRole' }]);
    suggestions.fetchSuggestions('', callback);
    expect(callback).toHaveBeenLastCalledWith([
      { value: 'SystemUser' },
      { value: 'SystemRole' },
    ]);

    expect(useGridColumns()?.map((column) => column.field)).toEqual([
      'name',
      'type',
      'sort',
      'permission',
      'path',
      'componentName',
      'status',
      undefined,
    ]);
  });
});

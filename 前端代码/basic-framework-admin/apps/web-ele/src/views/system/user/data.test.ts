import { describe, expect, it, vi } from 'vitest';

import { buildRequiredPasswordSchema } from '#/adapter/form';

import {
  useAssignRoleFormSchema,
  useFormSchema,
  useGridColumns,
  useGridFormSchema,
  useImportFormSchema,
  useResetPasswordFormSchema,
} from './data';

function createRuleChain() {
  return {
    default: vi.fn(() => createRuleChain()),
    email: vi.fn(() => createRuleChain()),
    max: vi.fn(() => createRuleChain()),
    min: vi.fn(() => createRuleChain()),
    optional: vi.fn(() => createRuleChain()),
    or: vi.fn(() => createRuleChain()),
    regex: vi.fn(() => createRuleChain()),
    refine: vi.fn(() => createRuleChain()),
  };
}

vi.mock('@vben/constants', () => ({
  CommonStatusEnum: {
    DISABLE: 1,
    ENABLE: 0,
  },
  DICT_TYPE: {
    COMMON_STATUS: 'common_status',
    SYSTEM_USER_SEX: 'system_user_sex',
  },
}));

vi.mock('@vben/hooks', () => ({
  getDictOptions: vi.fn(() => []),
}));

vi.mock('@vben/utils', () => ({
  handleTree: (data: unknown) => data,
  MOBILE_REGEX: /^1[3-9]\d{9}$/,
}));

vi.mock('#/api/system/dept', () => ({
  getDeptList: vi.fn(async () => []),
}));

vi.mock('#/api/system/post', () => ({
  getSimplePostList: vi.fn(async () => []),
}));

vi.mock('#/api/system/role', () => ({
  getSimpleRoleList: vi.fn(async () => []),
}));

vi.mock('#/utils', () => ({
  getRangePickerDefaultProps: () => ({}),
}));

vi.mock('#/adapter/form', () => ({
  buildOptionalEmailSchema: vi.fn(() => 'optionalEmail'),
  buildOptionalMobileSchema: vi.fn(() => 'optionalMobile'),
  buildOptionalRemarkSchema: vi.fn(() => 'optionalRemark'),
  buildRequiredNicknameSchema: vi.fn(() => 'nicknameRequired'),
  buildRequiredPasswordSchema: vi.fn(() => 'passwordRequired'),
  buildRequiredUsernameSchema: vi.fn(() => 'usernameRequired'),
  z: {
    boolean: vi.fn(() => createRuleChain()),
    literal: vi.fn(() => createRuleChain()),
    number: vi.fn(() => createRuleChain()),
    string: vi.fn(() => createRuleChain()),
  },
}));

describe('system user form schema', () => {
  it('用户名与新增密码使用共享规则', () => {
    const schema = useFormSchema();
    const usernameField = schema.find((item) => item.fieldName === 'username');
    const passwordField = schema.find((item) => item.fieldName === 'password');

    expect(usernameField?.rules).toBe('usernameRequired');
    // 锁定新增用户密码项，避免后续回退成仅必填但不校验复杂度的表单配置。
    expect(passwordField).toMatchObject({
      component: 'VbenInputPassword',
      rules: 'passwordRequired',
    });
    expect(passwordField?.componentProps).toMatchObject({
      passwordStrength: true,
    });
  });

  it('编辑表单不携带 status 字段，状态变更只走 update-status 专用接口', () => {
    const schema = useFormSchema();
    // 后端 update 接口忽略 status；表单若重新引入该字段会静默不生效
    expect(schema.find((item) => item.fieldName === 'status')).toBeUndefined();
  });

  it('执行表单内联回调：密码显隐、部门树加载、岗位禁用映射与 id 隐藏', async () => {
    const schema = useFormSchema();

    const passwordField = schema.find((item) => item.fieldName === 'password');
    const passwordShow = (
      passwordField?.dependencies as unknown as {
        show: (values: { id?: number }) => boolean;
      }
    ).show;
    expect(passwordShow({})).toBe(true);
    expect(passwordShow({ id: 1 })).toBe(false);

    const deptField = schema.find((item) => item.fieldName === 'deptId');
    const deptApi = (
      deptField?.componentProps as { api: () => Promise<unknown> }
    ).api;
    await expect(deptApi()).resolves.toEqual([]);

    const postField = schema.find((item) => item.fieldName === 'postIds');
    const postAfterFetch = (
      postField?.componentProps as {
        afterFetch: (
          posts: Array<{ name: string; status: number }>,
        ) => Promise<
          Array<{ disabled: boolean; name: string; status: number }>
        >;
      }
    ).afterFetch;
    await expect(
      postAfterFetch([{ name: '停用岗位', status: 1 }]),
    ).resolves.toEqual([{ disabled: true, name: '停用岗位', status: 1 }]);

    // 各表单的 id 字段恒隐藏
    for (const factory of [
      useFormSchema,
      useResetPasswordFormSchema,
      useAssignRoleFormSchema,
    ]) {
      const idField = factory().find((item) => item.fieldName === 'id');
      const idShow = (
        idField?.dependencies as unknown as { show: () => boolean }
      ).show;
      expect(idShow()).toBe(false);
    }

    // 确认密码的依赖规则在共享密码规则上叠加一致性 refine，
    // mock 必须真实执行回调以锁定比较语义（=== 改为 !== 等变异必须变红）
    const confirmField = useResetPasswordFormSchema().find(
      (item) => item.fieldName === 'confirmPassword',
    );
    const confirmRules = (
      confirmField?.dependencies as unknown as {
        rules: (values: { newPassword: string }) => unknown;
      }
    ).rules;
    const refine = vi.fn(
      (check: (value: string) => boolean, _message: string) => ({ check }),
    );
    vi.mocked(buildRequiredPasswordSchema).mockReturnValueOnce({
      refine,
    } as never);
    const rules = confirmRules({ newPassword: 'a' }) as {
      check: (value: string) => boolean;
    };
    expect(refine).toHaveBeenCalledOnce();
    expect(rules.check('a')).toBe(true);
    expect(rules.check('b')).toBe(false);
  });

  it('keeps password reset, role assignment, import and grid contracts', async () => {
    expect(
      useResetPasswordFormSchema().map(({ fieldName }) => fieldName),
    ).toEqual(['id', 'newPassword', 'confirmPassword']);
    expect(useImportFormSchema().map(({ fieldName }) => fieldName)).toEqual([
      'file',
      'updateSupport',
    ]);
    expect(useGridFormSchema().map(({ fieldName }) => fieldName)).toEqual([
      'username',
      'mobile',
      'createTime',
    ]);

    const roleIds = useAssignRoleFormSchema().find(
      ({ fieldName }) => fieldName === 'roleIds',
    );
    const afterFetch = (
      roleIds?.componentProps as {
        afterFetch: (
          roles: Array<{ name: string; status: number }>,
        ) => Promise<
          Array<{ disabled: boolean; name: string; status: number }>
        >;
      }
    ).afterFetch;
    await expect(
      afterFetch([
        { name: '管理员', status: 0 },
        { name: '停用角色', status: 1 },
      ]),
    ).resolves.toEqual([
      { disabled: false, name: '管理员', status: 0 },
      { disabled: true, name: '停用角色', status: 1 },
    ]);

    const statusChange = vi.fn(async () => true);
    const statusColumn = useGridColumns(statusChange)?.find(
      ({ field }) => field === 'status',
    );
    expect(statusColumn?.cellRender).toMatchObject({
      attrs: { beforeChange: statusChange },
      name: 'CellSwitch',
    });
  });
});

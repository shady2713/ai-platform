import type { PageParam, PageResult } from '@vben/request';

import { requestClient } from '#/api/request';

export namespace SystemUserApi {
  /** 用户信息 */
  export interface User {
    id: number;
    username: string;
    nickname: string;
    deptId: number;
    postIds: string[];
    email: string;
    mobile: string;
    sex: number;
    avatar: string;
    loginIp: string;
    /** 密码，仅创建用户时提交，编辑时不携带 */
    password?: string;
    status: number;
    remark: string;
    createTime?: Date;
  }

  /** 创建/更新提交体：状态不随表单提交，禁用/启用只走 update-status 专用接口 */
  export type UserPayload = Omit<User, 'status'>;

  /** 用户导入结果 */
  export interface UserImportResp {
    createUsernames: string[];
    updateUsernames: string[];
    failureUsernames: Record<string, string>;
  }
}

/** 查询用户管理列表 */
export function getUserPage(params: PageParam) {
  return requestClient.get<PageResult<SystemUserApi.User>>(
    '/system/user/page',
    { params },
  );
}

/** 查询用户详情 */
export function getUser(id: number) {
  return requestClient.get<SystemUserApi.User>(`/system/user/get?id=${id}`);
}

/** 新增用户 */
export function createUser(data: SystemUserApi.UserPayload) {
  return requestClient.post('/system/user/create', data);
}

/** 修改用户 */
export function updateUser(data: SystemUserApi.UserPayload) {
  return requestClient.put('/system/user/update', data);
}

/** 删除用户 */
export function deleteUser(id: number) {
  return requestClient.delete(`/system/user/delete?id=${id}`);
}

/** 批量删除用户 */
export function deleteUserList(ids: number[]) {
  return requestClient.delete(`/system/user/delete-list?ids=${ids.join(',')}`);
}

/** 导出用户 */
export function exportUser(params: PageParam) {
  return requestClient.download('/system/user/export-excel', { params });
}

/** 下载用户导入模板 */
export function importUserTemplate() {
  return requestClient.download('/system/user/get-import-template');
}

/** 导入用户 */
export function importUser(file: File, updateSupport: boolean) {
  return requestClient.upload<
    SystemUserApi.UserImportResp,
    { file: File; updateSupport: boolean }
  >('/system/user/import', {
    file,
    updateSupport,
  });
}

/** 用户密码重置 */
export function resetUserPassword(id: number, password: string) {
  return requestClient.put('/system/user/update-password', { id, password });
}

/** 用户状态修改 */
export function updateUserStatus(id: number, status: number) {
  return requestClient.put('/system/user/update-status', { id, status });
}

/** 解除用户的临时登录锁定 */
export function unlockUserLogin(id: number) {
  return requestClient.put(`/system/user/unlock-login?id=${id}`);
}

/** 获取用户精简信息列表 */
export function getSimpleUserList() {
  return requestClient.get<SystemUserApi.User[]>('/system/user/simple-list');
}

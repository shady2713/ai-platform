import { beforeEach, describe, expect, it, vi } from 'vitest';

import {
  createUser,
  deleteUser,
  deleteUserList,
  exportUser,
  getSimpleUserList,
  getUser,
  getUserPage,
  importUser,
  importUserTemplate,
  resetUserPassword,
  unlockUserLogin,
  updateUser,
  updateUserStatus,
} from './index';

const requestClient = vi.hoisted(() => ({
  delete: vi.fn(),
  download: vi.fn(),
  get: vi.fn(),
  post: vi.fn(),
  put: vi.fn(),
  upload: vi.fn(),
}));

vi.mock('#/api/request', () => ({ requestClient }));

describe('system user API contracts', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('maps CRUD, import, and status operations without changing payloads', async () => {
    const page = { pageNo: 2, pageSize: 20 };
    const user = {
      avatar: '',
      deptId: 1,
      email: 'user@example.com',
      id: 7,
      loginIp: '',
      mobile: '13800138000',
      nickname: 'user',
      postIds: ['1'],
      remark: '',
      sex: 1,
      status: 0,
      username: 'user',
    };
    const file = new File(['users'], 'users.xlsx');

    await getUserPage(page);
    await getUser(7);
    await createUser(user);
    await updateUser(user);
    await deleteUser(7);
    await deleteUserList([7, 8]);
    await exportUser(page);
    await importUserTemplate();
    await importUser(file, true);
    await resetUserPassword(7, 'new-password');
    await updateUserStatus(7, 1);
    await unlockUserLogin(7);
    await getSimpleUserList();

    expect(requestClient.get.mock.calls).toEqual([
      ['/system/user/page', { params: page }],
      ['/system/user/get?id=7'],
      ['/system/user/simple-list'],
    ]);
    expect(requestClient.post).toHaveBeenCalledWith(
      '/system/user/create',
      user,
    );
    expect(requestClient.put.mock.calls).toEqual([
      ['/system/user/update', user],
      ['/system/user/update-password', { id: 7, password: 'new-password' }],
      ['/system/user/update-status', { id: 7, status: 1 }],
      ['/system/user/unlock-login?id=7'],
    ]);
    expect(requestClient.delete.mock.calls).toEqual([
      ['/system/user/delete?id=7'],
      ['/system/user/delete-list?ids=7,8'],
    ]);
    expect(requestClient.download.mock.calls).toEqual([
      ['/system/user/export-excel', { params: page }],
      ['/system/user/get-import-template'],
    ]);
    expect(requestClient.upload).toHaveBeenCalledWith('/system/user/import', {
      file,
      updateSupport: true,
    });
  });
});

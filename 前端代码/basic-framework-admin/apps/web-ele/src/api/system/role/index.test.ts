import { beforeEach, describe, expect, it, vi } from 'vitest';

import {
  createRole,
  deleteRole,
  deleteRoleList,
  exportRole,
  getRole,
  getRolePage,
  getSimpleRoleList,
  updateRole,
} from './index';

const requestClient = vi.hoisted(() => ({
  delete: vi.fn(),
  download: vi.fn(),
  get: vi.fn(),
  post: vi.fn(),
  put: vi.fn(),
}));

vi.mock('#/api/request', () => ({ requestClient }));

describe('system role API contracts', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('maps role CRUD, simple list, and export without changing payloads', () => {
    const page = { pageNo: 2, pageSize: 20 };
    const role = {
      code: 'auditor',
      dataScope: 1,
      dataScopeDeptIds: [],
      id: 7,
      name: '审计员',
      sort: 10,
      status: 0,
      type: 2,
    };

    getRolePage(page);
    getSimpleRoleList();
    getRole(7);
    createRole(role);
    updateRole(role);
    deleteRole(7);
    deleteRoleList([7, 9]);
    exportRole(page);

    expect(requestClient.get.mock.calls).toEqual([
      ['/system/role/page', { params: page }],
      ['/system/role/simple-list'],
      ['/system/role/get?id=7'],
    ]);
    expect(requestClient.post).toHaveBeenCalledWith(
      '/system/role/create',
      role,
    );
    expect(requestClient.put).toHaveBeenCalledWith('/system/role/update', role);
    expect(requestClient.delete.mock.calls).toEqual([
      ['/system/role/delete?id=7'],
      ['/system/role/delete-list?ids=7,9'],
    ]);
    expect(requestClient.download).toHaveBeenCalledWith(
      '/system/role/export-excel',
      { params: page },
    );
  });
});

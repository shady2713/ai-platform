import { beforeEach, describe, expect, it, vi } from 'vitest';

import {
  createConfig,
  deleteConfig,
  deleteConfigList,
  exportConfig,
  getConfig,
  getConfigKey,
  getConfigPage,
  updateConfig,
} from './infra/config';
import {
  createFileConfig,
  deleteFileConfig,
  deleteFileConfigList,
  getFileConfig,
  getFileConfigPage,
  testFileConfig,
  updateFileConfig,
  updateFileConfigMaster,
} from './infra/file-config';
import { exportJobLog, getJobLog, getJobLogPage } from './infra/job-log';
import {
  createDept,
  deleteDept,
  deleteDeptList,
  getDept,
  getDeptList,
  getSimpleDeptList,
  updateDept,
} from './system/dept';
import {
  createDictData,
  deleteDictData,
  deleteDictDataList,
  exportDictData,
  getDictData,
  getDictDataPage,
  getSimpleDictDataList,
  updateDictData,
} from './system/dict/data';
import {
  createDictType,
  deleteDictType,
  deleteDictTypeList,
  exportDictType,
  getDictType,
  getDictTypePage,
  getSimpleDictTypeList,
  updateDictType,
} from './system/dict/type';
import {
  createMenu,
  deleteMenu,
  deleteMenuList,
  getMenu,
  getMenuList,
  getSimpleMenusList,
  updateMenu,
} from './system/menu';
import {
  assignRoleDataScope,
  assignRoleMenu,
  assignUserRole,
  getRoleMenuList,
  getUserRoleList,
} from './system/permission';
import {
  createPost,
  deletePost,
  deletePostList,
  exportPost,
  getPost,
  getPostPage,
  getSimplePostList,
  updatePost,
} from './system/post';
import {
  getSessionPage,
  revokeSession,
  revokeSessionList,
} from './system/session';
import {
  createSmsChannel,
  deleteSmsChannel,
  getSmsChannel,
  getSmsChannelPage,
  updateSmsChannel,
} from './system/sms/channel';
import {
  createSmsTemplate,
  deleteSmsTemplate,
  getSmsTemplate,
  getSmsTemplatePage,
  updateSmsTemplate,
} from './system/sms/template';

const requestClient = vi.hoisted(() => ({
  delete: vi.fn(),
  download: vi.fn(),
  get: vi.fn(),
  post: vi.fn(),
  put: vi.fn(),
}));

vi.mock('#/api/request', () => ({ requestClient }));

const page = { pageNo: 2, pageSize: 20 };

describe('management API contracts', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('maps organization, menu, permission and session endpoints', () => {
    const dept = {
      createTime: new Date('2026-01-01T00:00:00Z'),
      email: 'dev@example.com',
      id: 7,
      leaderUserId: null,
      name: '研发部',
      phone: '13800138000',
      sort: 1,
      status: 0,
    };
    const menu = {
      alwaysShow: true,
      component: 'system/user/index',
      createTime: new Date('2026-01-01T00:00:00Z'),
      icon: 'users',
      id: 7,
      keepAlive: true,
      name: '用户管理',
      parentId: 1,
      path: 'user',
      permission: 'system:user:list',
      sort: 1,
      status: 0,
      type: 2,
      visible: true,
    };
    const post = {
      code: 'developer',
      id: 7,
      name: '开发工程师',
      remark: '',
      sort: 1,
      status: 0,
    };

    getSimpleDeptList();
    getDeptList();
    getDept(7);
    createDept(dept);
    updateDept(dept);
    deleteDept(7);
    deleteDeptList([7, 9]);
    getSimpleMenusList();
    getMenuList({ name: '用户' });
    getMenu(7);
    createMenu(menu);
    updateMenu(menu);
    deleteMenu(7);
    deleteMenuList([7, 9]);
    getPostPage(page);
    getSimplePostList();
    getPost(7);
    createPost(post);
    updatePost(post);
    deletePost(7);
    deletePostList([7, 9]);
    exportPost(page);
    getRoleMenuList(3);
    assignRoleMenu({ menuIds: [7, 9], roleId: 3 });
    assignRoleDataScope({ dataScope: 2, dataScopeDeptIds: [7], roleId: 3 });
    getUserRoleList(5);
    assignUserRole({ roleIds: [3], userId: 5 });
    getSessionPage(page);
    revokeSession(11);
    revokeSessionList([11, 12]);

    expect(requestClient.get).toHaveBeenCalledWith('/system/dept/simple-list');
    expect(requestClient.get).toHaveBeenCalledWith('/system/dept/list');
    expect(requestClient.get).toHaveBeenCalledWith('/system/dept/get?id=7');
    expect(requestClient.get).toHaveBeenCalledWith('/system/menu/list', {
      params: { name: '用户' },
    });
    expect(requestClient.get).toHaveBeenCalledWith('/system/post/page', {
      params: page,
    });
    expect(requestClient.get).toHaveBeenCalledWith(
      '/system/permission/list-role-menus?roleId=3',
    );
    expect(requestClient.get).toHaveBeenCalledWith('/system/session/page', {
      params: page,
    });
    expect(requestClient.post).toHaveBeenCalledWith(
      '/system/dept/create',
      dept,
    );
    expect(requestClient.post).toHaveBeenCalledWith(
      '/system/menu/create',
      menu,
    );
    expect(requestClient.post).toHaveBeenCalledWith(
      '/system/post/create',
      post,
    );
    expect(requestClient.put).toHaveBeenCalledWith('/system/dept/update', dept);
    expect(requestClient.delete).toHaveBeenCalledWith(
      '/system/menu/delete-list?ids=7,9',
    );
    expect(requestClient.delete).toHaveBeenCalledWith(
      '/system/session/revoke-list?ids=11,12',
    );
    expect(requestClient.download).toHaveBeenCalledWith(
      '/system/post/export-excel',
      { params: page },
    );
  });

  it('maps dictionary type and value endpoints without changing payloads', () => {
    const type = {
      createTime: new Date('2026-01-01T00:00:00Z'),
      id: 7,
      name: '状态',
      remark: '',
      status: 0,
      type: 'status',
    };
    const data = {
      colorType: 'success',
      createTime: new Date('2026-01-01T00:00:00Z'),
      cssClass: '',
      dictType: 'status',
      id: 8,
      label: '正常',
      remark: '',
      status: 0,
      value: '0',
    };

    getSimpleDictTypeList();
    getDictTypePage(page);
    getDictType(7);
    createDictType(type);
    updateDictType(type);
    deleteDictType(7);
    deleteDictTypeList([7, 9]);
    exportDictType(page);
    getSimpleDictDataList();
    getDictDataPage(page);
    getDictData(8);
    createDictData(data);
    updateDictData(data);
    deleteDictData(8);
    deleteDictDataList([8, 10]);
    exportDictData(page);

    expect(requestClient.get).toHaveBeenCalledWith(
      '/system/dict-type/simple-list',
    );
    expect(requestClient.get).toHaveBeenCalledWith('/system/dict-type/page', {
      params: page,
    });
    expect(requestClient.get).toHaveBeenCalledWith(
      '/system/dict-data/simple-list',
    );
    expect(requestClient.post).toHaveBeenCalledWith(
      '/system/dict-type/create',
      type,
    );
    expect(requestClient.post).toHaveBeenCalledWith(
      '/system/dict-data/create',
      data,
    );
    expect(requestClient.delete).toHaveBeenCalledWith(
      '/system/dict-type/delete-list?ids=7,9',
    );
    expect(requestClient.delete).toHaveBeenCalledWith(
      '/system/dict-data/delete-list?ids=8,10',
    );
    expect(requestClient.download).toHaveBeenCalledWith(
      '/system/dict-data/export-excel',
      { params: page },
    );
  });

  it('maps infrastructure and credential-bearing SMS endpoints', () => {
    const config = {
      category: 'system',
      id: 7,
      key: 'site.name',
      name: '站点名称',
      remark: '',
      type: 1,
      value: '管理后台',
      visible: true,
    };
    const fileConfig = {
      config: { basePath: '/files', domain: 'https://files.example.com' },
      id: 7,
      master: true,
      name: 'S3',
      remark: '',
      visible: true,
    };
    const channel = {
      apiKey: 'key',
      callbackUrl: 'https://example.com/sms/callback',
      code: 'ALIYUN',
      id: 7,
      remark: '',
      signature: '测试',
      status: 0,
    };
    const template = {
      apiTemplateId: 'TPL_1',
      channelCode: 'ALIYUN',
      channelId: 1,
      code: 'login',
      content: String.raw`验证码 \${code}`,
      id: 9,
      name: '登录验证码',
      params: ['code'],
      remark: '',
      status: 0,
      type: 1,
    };

    getConfigPage(page);
    getConfig(7);
    getConfigKey('site.name');
    createConfig(config);
    updateConfig(config);
    deleteConfig(7);
    deleteConfigList([7, 9]);
    exportConfig(page);
    getFileConfigPage(page);
    getFileConfig(8);
    updateFileConfigMaster(8);
    createFileConfig(fileConfig);
    updateFileConfig(fileConfig);
    deleteFileConfig(8);
    deleteFileConfigList([8, 10]);
    testFileConfig(8);
    getJobLogPage(page);
    getJobLog(11);
    exportJobLog(page);
    getSmsChannelPage(page);
    getSmsChannel(12);
    createSmsChannel(channel);
    updateSmsChannel(channel);
    deleteSmsChannel(12);
    getSmsTemplatePage(page);
    getSmsTemplate(13);
    createSmsTemplate(template);
    updateSmsTemplate(template);
    deleteSmsTemplate(13);

    expect(requestClient.get).toHaveBeenCalledWith(
      '/infra/config/get-value-by-key?key=site.name',
    );
    expect(requestClient.get).toHaveBeenCalledWith(
      '/infra/file-config/test?id=8',
    );
    expect(requestClient.put).toHaveBeenCalledWith(
      '/infra/file-config/update-master?id=8',
    );
    expect(requestClient.delete).toHaveBeenCalledWith(
      '/infra/config/delete-list?ids=7,9',
    );
    expect(requestClient.delete).toHaveBeenCalledWith(
      '/infra/file-config/delete-list?ids=8,10',
    );
    expect(requestClient.download).toHaveBeenCalledWith(
      '/infra/job-log/export-excel',
      { params: page },
    );
  });
});

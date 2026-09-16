import { beforeEach, describe, expect, it, vi } from 'vitest';

import { exportLoginLog, getLoginLogPage } from './login-log';
import {
  getNotifyMessagePage,
  getUnreadNotifyMessageCount,
  getUnreadNotifyMessageList,
  updateAllNotifyMessageRead,
  updateNotifyMessageRead,
} from './notify/message';
import {
  createNotifyTemplate,
  deleteNotifyTemplate,
  getNotifyTemplate,
  getNotifyTemplatePage,
  updateNotifyTemplate,
} from './notify/template';
import { exportOperateLog, getOperateLogPage } from './operate-log';

const requestClient = vi.hoisted(() => ({
  delete: vi.fn(),
  download: vi.fn(),
  get: vi.fn(),
  post: vi.fn(),
  put: vi.fn(),
}));

vi.mock('#/api/request', () => ({ requestClient }));

describe('system audit and notification API contracts', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('maps audit log queries and exports', () => {
    const page = { pageNo: 2, pageSize: 20 };

    getLoginLogPage(page);
    exportLoginLog(page);
    getOperateLogPage(page);
    exportOperateLog(page);

    expect(requestClient.get.mock.calls).toEqual([
      ['/system/login-log/page', { params: page }],
      ['/system/operate-log/page', { params: page }],
    ]);
    expect(requestClient.download.mock.calls).toEqual([
      ['/system/login-log/export-excel', { params: page }],
      ['/system/operate-log/export-excel', { params: page }],
    ]);
  });

  it('maps user notification reads without changing identifiers', () => {
    const page = { pageNo: 1, pageSize: 10 };

    getNotifyMessagePage(page);
    getUnreadNotifyMessageCount();
    getUnreadNotifyMessageList();
    updateNotifyMessageRead(7);
    updateAllNotifyMessageRead();

    expect(requestClient.get.mock.calls).toEqual([
      ['/system/notify-message/page', { params: page }],
      ['/system/notify-message/get-unread-count'],
      ['/system/notify-message/get-unread-list'],
    ]);
    expect(requestClient.put.mock.calls).toEqual([
      ['/system/notify-message/update-read?id=7'],
      ['/system/notify-message/update-all-read'],
    ]);
  });

  it('maps notification template CRUD', () => {
    const page = { pageNo: 1, pageSize: 10 };
    const template = {
      code: 'account-disabled',
      content: '账号已停用',
      id: 9,
      name: '账号停用通知',
      nickname: '系统管理员',
      params: [],
      remark: '',
      status: 0,
      type: 2,
    };

    getNotifyTemplatePage(page);
    getNotifyTemplate(9);
    createNotifyTemplate(template);
    updateNotifyTemplate(template);
    deleteNotifyTemplate(9);

    expect(requestClient.get.mock.calls).toEqual([
      ['/system/notify-template/page', { params: page }],
      ['/system/notify-template/get?id=9'],
    ]);
    expect(requestClient.post.mock.calls).toEqual([
      ['/system/notify-template/create', template],
    ]);
    expect(requestClient.put).toHaveBeenCalledWith(
      '/system/notify-template/update',
      template,
    );
    expect(requestClient.delete).toHaveBeenCalledWith(
      '/system/notify-template/delete?id=9',
    );
  });
});

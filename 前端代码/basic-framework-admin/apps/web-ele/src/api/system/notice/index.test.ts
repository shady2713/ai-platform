import { beforeEach, describe, expect, it, vi } from 'vitest';

import {
  createNotice,
  deleteNotice,
  deleteNoticeList,
  getNotice,
  getNoticePage,
  pushNotice,
  updateNotice,
} from './index';

const requestClient = vi.hoisted(() => ({
  delete: vi.fn(),
  get: vi.fn(),
  post: vi.fn(),
  put: vi.fn(),
}));

vi.mock('#/api/request', () => ({ requestClient }));

describe('system notice API contracts', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('maps CRUD and push operations to backend routes', () => {
    const page = { pageNo: 2, pageSize: 20 };
    const notice = {
      content: '<p>维护通知</p>',
      id: 7,
      status: 0,
      title: '维护通知',
      type: 1 as const,
    };

    getNoticePage(page);
    getNotice(7);
    createNotice(notice);
    updateNotice(notice);
    deleteNotice(7);
    deleteNoticeList([7, 9]);
    pushNotice(7);

    expect(requestClient.get.mock.calls).toEqual([
      ['/system/notice/page', { params: page }],
      ['/system/notice/get?id=7'],
    ]);
    expect(requestClient.post.mock.calls).toEqual([
      ['/system/notice/create', notice],
      ['/system/notice/push?id=7'],
    ]);
    expect(requestClient.put).toHaveBeenCalledWith(
      '/system/notice/update',
      notice,
    );
    expect(requestClient.delete.mock.calls).toEqual([
      ['/system/notice/delete?id=7'],
      ['/system/notice/delete-list?ids=7,9'],
    ]);
  });
});

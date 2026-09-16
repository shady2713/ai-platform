import { beforeEach, describe, expect, it, vi } from 'vitest';

import {
  createJob,
  deleteJob,
  deleteJobList,
  exportJob,
  getJob,
  getJobNextTimes,
  getJobPage,
  runJob,
  updateJob,
  updateJobStatus,
} from './index';

const requestClient = vi.hoisted(() => ({
  delete: vi.fn(),
  download: vi.fn(),
  get: vi.fn(),
  post: vi.fn(),
  put: vi.fn(),
}));

vi.mock('#/api/request', () => ({ requestClient }));

describe('infra job API contracts', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('maps job CRUD, status, trigger, schedule preview, and export routes', () => {
    const page = { pageNo: 2, pageSize: 20 };
    const job = {
      cronExpression: '0 0 * * * ?',
      handlerName: 'cleanupJob',
      handlerParam: '',
      id: 7,
      monitorTimeout: 60,
      name: '清理任务',
      retryCount: 3,
      retryInterval: 10,
      status: 0,
    };

    getJobPage(page);
    getJob(7);
    createJob(job);
    updateJob(job);
    deleteJob(7);
    deleteJobList([7, 9]);
    exportJob(page);
    updateJobStatus(7, 1);
    runJob(7);
    getJobNextTimes(7);

    expect(requestClient.get.mock.calls).toEqual([
      ['/infra/job/page', { params: page }],
      ['/infra/job/get?id=7'],
      ['/infra/job/next-times?id=7'],
    ]);
    expect(requestClient.post).toHaveBeenCalledWith('/infra/job/create', job);
    expect(requestClient.put.mock.calls).toEqual([
      ['/infra/job/update', job],
      ['/infra/job/update-status', undefined, { params: { id: 7, status: 1 } }],
      ['/infra/job/trigger?id=7'],
    ]);
    expect(requestClient.delete.mock.calls).toEqual([
      ['/infra/job/delete?id=7'],
      ['/infra/job/delete-list?ids=7,9'],
    ]);
    expect(requestClient.download).toHaveBeenCalledWith(
      '/infra/job/export-excel',
      { params: page },
    );
  });
});

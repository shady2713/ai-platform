import { beforeEach, describe, expect, it, vi } from 'vitest';

import {
  createApplication,
  deleteApplication,
  getApplication,
  getApplicationPage,
  revokeCredential,
  rotateCredential,
  updateApplication,
  updateApplicationStatus,
} from './index';

const requestClient = vi.hoisted(() => ({
  delete: vi.fn(),
  get: vi.fn(),
  post: vi.fn(),
  put: vi.fn(),
}));

vi.mock('#/api/request', () => ({ requestClient }));

/** A09 应用接入 API 契约：路径、方法与载荷与 A01 控制器一一对应。 */
describe('ai application api contracts', () => {
  beforeEach(() => vi.clearAllMocks());

  it('maps query endpoints', async () => {
    await getApplicationPage({ pageNo: 1, pageSize: 10 });
    await getApplication(5);

    expect(requestClient.get).toHaveBeenCalledWith('/ai/application/page', {
      params: { pageNo: 1, pageSize: 10 },
    });
    expect(requestClient.get).toHaveBeenCalledWith('/ai/application/get?id=5');
  });

  it('maps create and update without echoing stored credential', async () => {
    const payload = {
      appCode: 'crm-portal',
      name: 'CRM 门户',
      origins: ['https://crm.example.com'],
    };
    await createApplication(payload);
    await updateApplication({ ...payload, id: 5, version: 2 });

    expect(requestClient.post).toHaveBeenCalledWith(
      '/ai/application/create',
      payload,
    );
    expect(requestClient.put).toHaveBeenCalledWith('/ai/application/update', {
      ...payload,
      id: 5,
      version: 2,
    });
  });

  it('maps lifecycle and credential commands with optimistic lock version', async () => {
    await updateApplicationStatus(5, 2, false);
    await rotateCredential(5, 3);
    await revokeCredential(5, 4);
    await deleteApplication(5, 5);

    expect(requestClient.put).toHaveBeenCalledWith(
      '/ai/application/update-status',
      {
        enabled: false,
        id: 5,
        version: 2,
      },
    );
    expect(requestClient.put).toHaveBeenCalledWith(
      '/ai/application/rotate-credential?id=5&version=3',
    );
    expect(requestClient.put).toHaveBeenCalledWith(
      '/ai/application/revoke-credential?id=5&version=4',
    );
    expect(requestClient.delete).toHaveBeenCalledWith(
      '/ai/application/delete?id=5&version=5',
    );
  });
});

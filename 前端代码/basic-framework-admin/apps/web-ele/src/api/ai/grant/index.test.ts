import { beforeEach, describe, expect, it, vi } from 'vitest';

import {
  createGrant,
  getGrant,
  getGrantPage,
  revokeGrant,
  updateGrant,
} from './index';

const requestClient = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
  put: vi.fn(),
}));

vi.mock('#/api/request', () => ({ requestClient }));

/** A09 资源授权 API 契约：与 A03 控制器一致，撤销与修改都带乐观锁版本。 */
describe('ai grant api contracts', () => {
  beforeEach(() => vi.clearAllMocks());

  it('maps query endpoints', async () => {
    await getGrantPage({ applicationId: 5, pageNo: 1, pageSize: 20 });
    await getGrant(3);

    expect(requestClient.get).toHaveBeenCalledWith('/ai/grant/page', {
      params: { applicationId: 5, pageNo: 1, pageSize: 20 },
    });
    expect(requestClient.get).toHaveBeenCalledWith('/ai/grant/get?id=3');
  });

  it('maps create update and revoke', async () => {
    const payload = {
      actions: ['READ'],
      applicationId: 5,
      externalUserId: 'alice',
      resourceKey: 'report-1',
      resourceType: 'REPORT',
      subjectType: 'USER',
    };
    await createGrant(payload);
    await updateGrant({ actions: ['READ', 'EXPORT'], id: 3, version: 1 });
    await revokeGrant(3, 2);

    expect(requestClient.post).toHaveBeenCalledWith(
      '/ai/grant/create',
      payload,
    );
    expect(requestClient.put).toHaveBeenCalledWith('/ai/grant/update', {
      actions: ['READ', 'EXPORT'],
      id: 3,
      version: 1,
    });
    expect(requestClient.put).toHaveBeenCalledWith(
      '/ai/grant/revoke?id=3&version=2',
    );
  });
});

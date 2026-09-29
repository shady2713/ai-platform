import { beforeEach, describe, expect, it, vi } from 'vitest';

import {
  approveSubjectFederation,
  getSubjectFederation,
  getSubjectFederationPage,
  getSystemCatalog,
  revokeSubjectFederation,
  selectAnalysisScope,
  submitSubjectFederation,
  verifyAnalysisScope,
} from './discovery';

const requestClient = vi.hoisted(() => ({
  delete: vi.fn(),
  get: vi.fn(),
  post: vi.fn(),
  put: vi.fn(),
}));

vi.mock('#/api/request', () => ({ requestClient }));

/** Y01 跨系统发现 API 契约：路径、方法与载荷与 AiApplicationDiscoveryController 一一对应。 */
describe('ai cross-system discovery api contracts', () => {
  beforeEach(() => vi.clearAllMocks());

  it('maps discovery query with the observed identity only', async () => {
    await getSystemCatalog({
      applicationId: 5,
      externalUserId: 'alice',
      subjectType: 'USER',
    });

    expect(requestClient.get).toHaveBeenCalledWith(
      '/ai/application/discovery/get',
      {
        params: {
          applicationId: 5,
          externalUserId: 'alice',
          subjectType: 'USER',
        },
      },
    );
  });

  it('maps selection and verification with fingerprints verbatim', async () => {
    await selectAnalysisScope({
      applicationId: 5,
      catalogFingerprint: 'catalog-fp',
      externalUserId: 'alice',
      mode: 'CROSS_SYSTEM',
      subjectType: 'USER',
      targetSystemCodes: ['crm', 'erp'],
    });
    await verifyAnalysisScope({
      applicationId: 5,
      catalogFingerprint: 'catalog-fp',
      externalUserId: 'alice',
      mode: 'CROSS_SYSTEM',
      selectionFingerprint: 'selection-fp',
      subjectType: 'USER',
      targetSystemCodes: ['crm', 'erp'],
    });

    expect(requestClient.post).toHaveBeenCalledWith(
      '/ai/application/discovery/select',
      expect.objectContaining({ catalogFingerprint: 'catalog-fp' }),
    );
    expect(requestClient.post).toHaveBeenCalledWith(
      '/ai/application/discovery/verify',
      expect.objectContaining({ selectionFingerprint: 'selection-fp' }),
    );
  });

  it('maps federation lifecycle commands without self-reported approver', async () => {
    await submitSubjectFederation({
      sourceApplicationId: 5,
      sourceExternalUserId: 'alice',
      sourceSubjectType: 'USER',
      targetApplicationId: 9,
      targetExternalUserId: 'alice',
      targetSubjectType: 'USER',
    });
    await approveSubjectFederation({
      approvalNote: '复核通过',
      id: 42,
      version: 0,
    });
    await revokeSubjectFederation(42, 1);

    expect(requestClient.post).toHaveBeenCalledWith(
      '/ai/application/federation/submit',
      expect.objectContaining({ targetApplicationId: 9 }),
    );
    expect(requestClient.put).toHaveBeenCalledWith(
      '/ai/application/federation/approve',
      { approvalNote: '复核通过', id: 42, version: 0 },
    );
    expect(requestClient.put).toHaveBeenCalledWith(
      '/ai/application/federation/revoke',
      { id: 42, version: 1 },
    );
  });

  it('maps federation queries', async () => {
    await getSubjectFederation(42);
    await getSubjectFederationPage({
      pageNo: 1,
      pageSize: 10,
      sourceApplicationId: 5,
      status: 'PENDING',
    });

    expect(requestClient.get).toHaveBeenCalledWith(
      '/ai/application/federation/get?id=42',
    );
    expect(requestClient.get).toHaveBeenCalledWith(
      '/ai/application/federation/page',
      {
        params: {
          pageNo: 1,
          pageSize: 10,
          sourceApplicationId: 5,
          status: 'PENDING',
        },
      },
    );
  });
});

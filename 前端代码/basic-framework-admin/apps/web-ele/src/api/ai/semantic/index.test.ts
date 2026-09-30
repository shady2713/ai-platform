import { beforeEach, describe, expect, it, vi } from 'vitest';

import {
  createMappingEntry,
  createMasterObject,
  createRevision,
  deleteMappingEntry,
  getMasterObjectCatalog,
  getMasterObjectPage,
  getRevisionDetail,
  publishRevision,
  resolveObjectKey,
  resolveSourceKey,
  updateMasterObjectStatus,
} from './index';

const requestClient = vi.hoisted(() => ({
  delete: vi.fn(),
  get: vi.fn(),
  post: vi.fn(),
  put: vi.fn(),
}));

vi.mock('#/api/request', () => ({ requestClient }));

/** Y02 主数据映射 API 契约：路径、方法与载荷与 AiMasterObjectController 一一对应。 */
describe('ai semantic master-data api contracts', () => {
  beforeEach(() => vi.clearAllMocks());

  it('maps object queries and mutations', async () => {
    await getMasterObjectPage({ keyword: 'md', pageNo: 1, pageSize: 20 });
    expect(requestClient.get).toHaveBeenCalledWith('/ai/semantic/object/page', {
      params: { keyword: 'md', pageNo: 1, pageSize: 20 },
    });

    await createMasterObject({
      objectCode: 'md_customer',
      objectName: '企业客户',
      objectType: 'CUSTOMER',
    });
    expect(requestClient.post).toHaveBeenCalledWith(
      '/ai/semantic/object/create',
      {
        objectCode: 'md_customer',
        objectName: '企业客户',
        objectType: 'CUSTOMER',
      },
    );

    await updateMasterObjectStatus(5, 3, false);
    expect(requestClient.put).toHaveBeenCalledWith(
      '/ai/semantic/object/update-status',
      { enabled: false, id: 5, version: 3 },
    );
  });

  it('maps revision and mapping entry mutations', async () => {
    await createRevision({
      masterObjectId: 5,
      validFrom: '2026-01-01T00:00:00',
    });
    expect(requestClient.post).toHaveBeenCalledWith(
      '/ai/semantic/revision/create',
      {
        masterObjectId: 5,
        validFrom: '2026-01-01T00:00:00',
      },
    );

    await createMappingEntry({
      applicationId: 7,
      entityType: 'customer',
      masterObjectId: 5,
      matchMethod: 'MANUAL',
      revisionNo: 1,
      sourceKey: 'C-1001',
      sourceName: '杭州云启科技有限公司',
      validFrom: '2026-01-01T00:00:00',
    });
    expect(requestClient.post).toHaveBeenCalledWith(
      '/ai/semantic/mapping/create',
      {
        applicationId: 7,
        entityType: 'customer',
        masterObjectId: 5,
        matchMethod: 'MANUAL',
        revisionNo: 1,
        sourceKey: 'C-1001',
        sourceName: '杭州云启科技有限公司',
        validFrom: '2026-01-01T00:00:00',
      },
    );

    await deleteMappingEntry(77, 0);
    expect(requestClient.delete).toHaveBeenCalledWith(
      '/ai/semantic/mapping/delete?id=77&version=0',
    );

    await publishRevision(5, 1, 0);
    expect(requestClient.put).toHaveBeenCalledWith(
      '/ai/semantic/revision/publish',
      {
        masterObjectId: 5,
        revisionNo: 1,
        version: 0,
      },
    );

    await getRevisionDetail(5, 1);
    expect(requestClient.get).toHaveBeenCalledWith(
      '/ai/semantic/revision/detail?masterObjectId=5&revisionNo=1',
    );
  });

  it('always sends the pinned version and asOf with judgement requests', async () => {
    await resolveObjectKey({
      applicationId: 7,
      asOf: '2026-09-01T00:00:00',
      entityType: 'customer',
      objectCode: 'md_cloud_qi',
      revisionNo: 1,
    });
    expect(requestClient.post).toHaveBeenCalledWith(
      '/ai/semantic/resolve/object-key',
      {
        applicationId: 7,
        asOf: '2026-09-01T00:00:00',
        entityType: 'customer',
        objectCode: 'md_cloud_qi',
        revisionNo: 1,
      },
    );

    await resolveSourceKey({
      applicationId: 7,
      asOf: '2026-09-01T00:00:00',
      entityType: 'customer',
      sourceKey: 'C-1001',
    });
    expect(requestClient.post).toHaveBeenCalledWith(
      '/ai/semantic/resolve/source-key',
      {
        applicationId: 7,
        asOf: '2026-09-01T00:00:00',
        entityType: 'customer',
        sourceKey: 'C-1001',
      },
    );

    await getMasterObjectCatalog({
      applicationId: 7,
      asOf: '2026-09-01T00:00:00',
      externalUserId: 'alice',
      objectCode: 'md_cloud_qi',
      revisionNo: 1,
      subjectType: 'USER',
    });
    expect(requestClient.get).toHaveBeenCalledWith('/ai/semantic/catalog/get', {
      params: {
        applicationId: 7,
        asOf: '2026-09-01T00:00:00',
        externalUserId: 'alice',
        objectCode: 'md_cloud_qi',
        revisionNo: 1,
        subjectType: 'USER',
      },
    });
  });
});

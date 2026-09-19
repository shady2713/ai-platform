import { beforeEach, describe, expect, it, vi } from 'vitest';

import {
  bindServiceResource,
  checkReleaseReadiness,
  checkServiceCapabilities,
  createReleaseCandidate,
  createService,
  deleteService,
  disableRelease,
  getService,
  getServicePage,
  listReleaseBindings,
  listReleaseEvaluations,
  listReleases,
  listServiceResources,
  markServiceReady,
  publishRelease,
  recordReleaseEvaluation,
  resolveRunSnapshot,
  rollbackRelease,
  runServiceDebug,
  unbindServiceResource,
  updateService,
} from './index';

const requestClient = vi.hoisted(() => ({
  delete: vi.fn(),
  get: vi.fn(),
  post: vi.fn(),
  put: vi.fn(),
}));

vi.mock('#/api/request', () => ({ requestClient }));

/** S05 前端 API 契约：路径、方法与载荷与后端控制器一一对应。 */
describe('ai service api contracts', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('maps draft query and detail endpoints', async () => {
    await getServicePage({ pageNo: 1, pageSize: 10 });
    await getService(9);
    await checkServiceCapabilities(9);
    await listServiceResources(9);

    expect(requestClient.get).toHaveBeenCalledWith('/ai/service/page', {
      params: { pageNo: 1, pageSize: 10 },
    });
    expect(requestClient.get).toHaveBeenCalledWith('/ai/service/get?id=9');
    expect(requestClient.get).toHaveBeenCalledWith(
      '/ai/service/check-capabilities?id=9',
    );
    expect(requestClient.get).toHaveBeenCalledWith(
      '/ai/service/resources?id=9',
    );
  });

  it('maps draft writes with optimistic lock versions', async () => {
    const payload = {
      appId: 5,
      code: 'svc_order_qa',
      evalThreshold: 80,
      inputSchema: '{"type":"object"}',
      modelEndpointId: 1,
      name: '订单问答',
      promptTemplate: '你是订单助手',
      requiredCapabilities: ['TEXT'],
      runSubjectType: 'USER',
    };

    await createService(payload);
    await updateService({ ...payload, id: 9, version: 4 });
    await markServiceReady(9, 4);
    await deleteService(9, 5);

    expect(requestClient.post).toHaveBeenCalledWith(
      '/ai/service/create',
      payload,
    );
    expect(requestClient.put).toHaveBeenCalledWith('/ai/service/update', {
      ...payload,
      id: 9,
      version: 4,
    });
    expect(requestClient.put).toHaveBeenCalledWith(
      '/ai/service/mark-ready?id=9&version=4',
    );
    expect(requestClient.delete).toHaveBeenCalledWith(
      '/ai/service/delete?id=9&version=5',
    );
  });

  it('maps resource binding endpoints', async () => {
    const payload = {
      actions: ['READ'],
      resourceKey: 'report-1',
      resourceType: 'REPORT',
      serviceId: 9,
    };

    await bindServiceResource(payload);
    await unbindServiceResource(11, 2);

    expect(requestClient.post).toHaveBeenCalledWith(
      '/ai/service/bind-resource',
      payload,
    );
    expect(requestClient.put).toHaveBeenCalledWith(
      '/ai/service/unbind-resource?bindingId=11&version=2',
    );
  });

  it('maps release lifecycle endpoints', async () => {
    await createReleaseCandidate(9, 4);
    await recordReleaseEvaluation(21, 92, 12, '回归集');
    await publishRelease(21, 0);
    await rollbackRelease(21, 3);
    await disableRelease(9, 5);
    await checkReleaseReadiness(21);

    expect(requestClient.post).toHaveBeenCalledWith(
      '/ai/service/release/create-candidate',
      { serviceId: 9, version: 4 },
    );
    expect(requestClient.post).toHaveBeenCalledWith(
      '/ai/service/release/evaluate',
      { caseCount: 12, notes: '回归集', releaseId: 21, score: 92 },
    );
    expect(requestClient.post).toHaveBeenCalledWith(
      '/ai/service/release/publish',
      { releaseId: 21, version: 0 },
    );
    expect(requestClient.post).toHaveBeenCalledWith(
      '/ai/service/release/rollback',
      { releaseId: 21, version: 3 },
    );
    expect(requestClient.post).toHaveBeenCalledWith(
      '/ai/service/release/disable',
      { serviceId: 9, version: 5 },
    );
    expect(requestClient.get).toHaveBeenCalledWith(
      '/ai/service/release/check-publish',
      { params: { releaseId: 21 } },
    );
  });

  it('maps release queries and run resolution', async () => {
    await listReleases(9);
    await listReleaseBindings(21);
    await listReleaseEvaluations(21);
    await resolveRunSnapshot(9);

    expect(requestClient.get).toHaveBeenCalledWith('/ai/service/release/list', {
      params: { serviceId: 9 },
    });
    expect(requestClient.get).toHaveBeenCalledWith(
      '/ai/service/release/bindings',
      { params: { releaseId: 21 } },
    );
    expect(requestClient.get).toHaveBeenCalledWith(
      '/ai/service/release/evaluations',
      { params: { releaseId: 21 } },
    );
    expect(requestClient.get).toHaveBeenCalledWith(
      '/ai/service/release/resolve',
      { params: { serviceId: 9 } },
    );
  });

  it('maps debug run with explicit test subject', async () => {
    const payload = {
      dataLevel: 'L2_INTERNAL',
      serviceId: 9,
      testSubjectId: 'u-1001',
      testSubjectType: 'USER',
      userMessage: '帮我查订单',
    };

    await runServiceDebug(payload);

    expect(requestClient.post).toHaveBeenCalledWith(
      '/ai/service/debug/run',
      payload,
    );
  });
});

import { beforeEach, describe, expect, it, vi } from 'vitest';

import {
  createModelEndpoint,
  deleteModelEndpoint,
  getModelEndpoint,
  getModelEndpointCapabilities,
  getModelEndpointPage,
  getModelEndpointProbeResults,
  getModelEndpointRevisions,
  probeModelEndpoint,
  rotateModelEndpointCredential,
  updateModelEndpoint,
  updateModelEndpointStatus,
} from './index';

const requestClient = vi.hoisted(() => ({
  delete: vi.fn(),
  get: vi.fn(),
  post: vi.fn(),
  put: vi.fn(),
}));

vi.mock('#/api/request', () => ({ requestClient }));

/** M06 前端 API 契约：路径、方法与载荷与后端控制器一一对应，凭据只在提交时出现。 */
describe('ai model endpoint api contracts', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('maps query and detail endpoints', async () => {
    await getModelEndpointPage({ pageNo: 1, pageSize: 10 });
    await getModelEndpoint(9);

    expect(requestClient.get).toHaveBeenCalledWith('/ai/model-endpoint/page', {
      params: { pageNo: 1, pageSize: 10 },
    });
    expect(requestClient.get).toHaveBeenCalledWith(
      '/ai/model-endpoint/get?id=9',
    );
  });

  it('maps create and update payloads without echo fields', async () => {
    const payload = {
      baseUrl: 'https://api.example.com/v1',
      capabilities: ['TEXT'],
      credential: 'sk-new',
      modelId: 'gpt-4o-mini',
      name: 'openai-生产',
      provider: 'openai_compatible',
    };

    await createModelEndpoint(payload);
    await updateModelEndpoint({ ...payload, id: 9, version: 3 });

    expect(requestClient.post).toHaveBeenCalledWith(
      '/ai/model-endpoint/create',
      payload,
    );
    expect(requestClient.put).toHaveBeenCalledWith(
      '/ai/model-endpoint/update',
      { ...payload, id: 9, version: 3 },
    );
  });

  it('maps lifecycle commands with optimistic lock versions', async () => {
    await updateModelEndpointStatus(9, 3, false);
    await rotateModelEndpointCredential(9, 4, 'sk-rotated');
    await deleteModelEndpoint(9, 5);

    expect(requestClient.put).toHaveBeenCalledWith(
      '/ai/model-endpoint/update-status',
      { enabled: false, id: 9, version: 3 },
    );
    expect(requestClient.put).toHaveBeenCalledWith(
      '/ai/model-endpoint/rotate-credential',
      { credential: 'sk-rotated', id: 9, version: 4 },
    );
    expect(requestClient.delete).toHaveBeenCalledWith(
      '/ai/model-endpoint/delete?id=9&version=5',
    );
  });

  it('maps revisions and capability probe endpoints', async () => {
    await getModelEndpointRevisions(9);
    await probeModelEndpoint(9);
    await getModelEndpointProbeResults(9);
    await getModelEndpointCapabilities(9);

    expect(requestClient.get).toHaveBeenCalledWith(
      '/ai/model-endpoint/revisions?id=9',
    );
    expect(requestClient.post).toHaveBeenCalledWith(
      '/ai/model-endpoint/9/probe',
    );
    expect(requestClient.get).toHaveBeenCalledWith(
      '/ai/model-endpoint/9/probe',
    );
    expect(requestClient.get).toHaveBeenCalledWith(
      '/ai/model-endpoint/9/capabilities',
    );
  });
});

import { beforeEach, describe, expect, it, vi } from 'vitest';

const request = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
}));

vi.mock('#/api/request', () => ({
  requestClient: request,
}));

const { getRunDetail, getRunPage, getRunTimeline, retryRun } =
  await import('./index');

describe('运行监控 API 客户端（Q03）', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    request.get.mockResolvedValue(null);
    request.post.mockResolvedValue(true);
  });

  it('每个操作打到与控制器一致的路径与参数', async () => {
    await getRunPage({ applicationId: 1, pageNo: 2, pageSize: 20 });
    expect(request.get).toHaveBeenCalledWith('/ai/observability/run/page', {
      params: { applicationId: 1, pageNo: 2, pageSize: 20 },
    });

    await getRunDetail(21);
    expect(request.get).toHaveBeenCalledWith('/ai/observability/run/get', {
      params: { runId: 21 },
    });

    await getRunTimeline({ afterSeq: 3, limit: 50, runId: 21 });
    expect(request.get).toHaveBeenCalledWith('/ai/observability/run/timeline', {
      params: { afterSeq: 3, limit: 50, runId: 21 },
    });

    await retryRun({ runId: 21, version: 6 });
    expect(request.post).toHaveBeenCalledWith('/ai/observability/run/retry', {
      runId: 21,
      version: 6,
    });
  });

  it('返回值原样透传（不在客户端层做二次解释）', async () => {
    request.get.mockResolvedValue({ runId: 21, runVersion: 6 });
    await expect(getRunDetail(21)).resolves.toStrictEqual({
      runId: 21,
      runVersion: 6,
    });
    request.post.mockResolvedValue(true);
    await expect(retryRun({ runId: 21, version: 6 })).resolves.toBe(true);
  });
});

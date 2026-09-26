import { beforeEach, describe, expect, it, vi } from 'vitest';

const request = vi.hoisted(() => ({
  get: vi.fn(),
}));

vi.mock('#/api/request', () => ({
  requestClient: request,
}));

const {
  getQuotaActive,
  getUsagePage,
  getUsageServiceSummary,
  getUsageSummary,
} = await import('./usage');

describe('用量与限额 API 客户端（Q03 页面对 Q02 接口的消费）', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    request.get.mockResolvedValue(null);
  });

  it('每个操作打到与 Q02 控制器一致的路径与参数', async () => {
    await getUsagePage({
      applicationId: 1,
      from: '2026-09-01T00:00:00',
      pageNo: 1,
      pageSize: 20,
      to: '2026-09-26T00:00:00',
    });
    expect(request.get).toHaveBeenCalledWith('/ai/usage/page', {
      params: {
        applicationId: 1,
        from: '2026-09-01T00:00:00',
        pageNo: 1,
        pageSize: 20,
        to: '2026-09-26T00:00:00',
      },
    });

    await getUsageSummary({
      applicationId: 1,
      from: '2026-09-01T00:00:00',
      to: '2026-09-26T00:00:00',
    });
    expect(request.get).toHaveBeenCalledWith('/ai/usage/summary', {
      params: {
        applicationId: 1,
        from: '2026-09-01T00:00:00',
        to: '2026-09-26T00:00:00',
      },
    });

    await getUsageServiceSummary({
      applicationId: 1,
      from: '2026-09-01T00:00:00',
      to: '2026-09-26T00:00:00',
    });
    expect(request.get).toHaveBeenCalledWith('/ai/usage/service-summary', {
      params: {
        applicationId: 1,
        from: '2026-09-01T00:00:00',
        to: '2026-09-26T00:00:00',
      },
    });

    await getQuotaActive(1);
    expect(request.get).toHaveBeenCalledWith('/ai/usage/quota-active', {
      params: { applicationId: 1 },
    });
  });

  it('返回值原样透传（含未知计量条数）', async () => {
    request.get.mockResolvedValue({
      sources: [{ usageSource: 'UNKNOWN' }],
      unknownInvocations: 1,
    });
    await expect(
      getUsageSummary({ applicationId: 1, from: 'a', to: 'b' }),
    ).resolves.toStrictEqual({
      sources: [{ usageSource: 'UNKNOWN' }],
      unknownInvocations: 1,
    });
  });
});

import { beforeEach, describe, expect, it, vi } from 'vitest';

const request = vi.hoisted(() => ({
  delete: vi.fn(),
  get: vi.fn(),
  post: vi.fn(),
  put: vi.fn(),
}));

vi.mock('#/api/request', () => ({
  requestClient: request,
}));

const {
  createTarget,
  deleteTarget,
  getDeliveryAttempts,
  getDeliveryPage,
  getTargetPage,
  redeliverDelivery,
  rotateTargetSecret,
  updateTargetStatus,
} = await import('./api');

/**
 * Webhook 控制台 API 客户端（X10）：只断言"请求打到哪个端点、带什么参数"。
 *
 * <p>为什么不靠组件用例覆盖：组件用例为了隔离会把本模块整体 mock 掉（`vi.mock('./api')`），
 * 真实请求映射就永远不会执行。这里用真实模块 + mock 的 `requestClient` 把 8 个端点钉住，
 * 路径写错（例如漏了 `/ai/webhook` 前缀或用了 GET 而不是 PUT）会直接红。
 */
describe('webhook 管理 API 客户端（X10）', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    request.get.mockResolvedValue(null);
    request.post.mockResolvedValue(1);
    request.put.mockResolvedValue(true);
    request.delete.mockResolvedValue(true);
  });

  it('目标管理：分页/创建/启停/轮换/删除都打到登记过的端点', async () => {
    await getTargetPage({ pageNo: 1, pageSize: 20, status: 'ENABLED' });
    expect(request.get).toHaveBeenCalledWith('/ai/webhook/target/page', {
      params: { pageNo: 1, pageSize: 20, status: 'ENABLED' },
    });

    await createTarget({
      applicationId: 1,
      code: 'sink-a',
      eventTypes: ['RUN.SUCCEEDED'],
      maxAttempts: 3,
      name: '接收端',
      targetUrl: 'https://sink.example.com/hook',
    });
    expect(request.post).toHaveBeenCalledWith('/ai/webhook/target/create', {
      applicationId: 1,
      code: 'sink-a',
      eventTypes: ['RUN.SUCCEEDED'],
      maxAttempts: 3,
      name: '接收端',
      targetUrl: 'https://sink.example.com/hook',
    });

    await updateTargetStatus({ enabled: false, id: 7, version: 3 });
    expect(request.put).toHaveBeenCalledWith(
      '/ai/webhook/target/update-status',
      {
        enabled: false,
        id: 7,
        version: 3,
      },
    );

    await rotateTargetSecret({ id: 7, secret: 'new-secret-value', version: 3 });
    expect(request.put).toHaveBeenCalledWith(
      '/ai/webhook/target/rotate-secret',
      {
        id: 7,
        secret: 'new-secret-value',
        version: 3,
      },
    );

    await deleteTarget(7, 3);
    expect(request.delete).toHaveBeenCalledWith('/ai/webhook/target/delete', {
      params: { id: 7, version: 3 },
    });
  });

  it('投递管理：分页/尝试明细/人工重投都打到登记过的端点', async () => {
    await getDeliveryPage({
      pageNo: 2,
      pageSize: 50,
      status: 'DEAD_LETTER',
      targetId: 7,
    });
    expect(request.get).toHaveBeenCalledWith('/ai/webhook/delivery/page', {
      params: { pageNo: 2, pageSize: 50, status: 'DEAD_LETTER', targetId: 7 },
    });

    await getDeliveryAttempts(88);
    expect(request.get).toHaveBeenCalledWith('/ai/webhook/delivery/attempts', {
      params: { deliveryId: 88 },
    });

    await redeliverDelivery(88);
    expect(request.put).toHaveBeenCalledWith('/ai/webhook/delivery/redeliver', {
      id: 88,
    });
  });

  it('返回值原样透传（客户端层不做二次解释）', async () => {
    request.get.mockResolvedValue([{ attemptNo: 1 }]);
    await expect(getDeliveryAttempts(1)).resolves.toStrictEqual([
      { attemptNo: 1 },
    ]);
    request.post.mockResolvedValue(42);
    await expect(
      createTarget({
        applicationId: 1,
        code: 'sink-n',
        eventTypes: ['RUN.FAILED'],
        maxAttempts: 3,
        name: 'n',
        targetUrl: 'https://sink.example.com/hook',
      }),
    ).resolves.toBe(42);
  });
});

import { beforeEach, describe, expect, it, vi } from 'vitest';

const request = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
}));

vi.mock('#/api/request', () => ({
  requestClient: request,
}));

const { createQueryPlan, getQueryDatasetSummary } = await import('./index');

/** 测试助手：最近一次调用必须存在（不存在直接失败，避免非空断言）。 */
function lastCall(method: 'get' | 'post'): unknown[] {
  const call = request[method].mock.calls.at(-1);
  if (!call) {
    throw new Error(`${method} 未被调用`);
  }
  return call;
}

/** 测试助手：取请求体（GET 的 config 也走这里，形状不一致由断言负责暴露）。 */
function lastBody(method: 'get' | 'post'): Record<string, unknown> {
  const body = lastCall(method)[1];
  if (typeof body !== 'object' || body === null) {
    throw new Error(`${method} 的第二个参数不是对象`);
  }
  return body as Record<string, unknown>;
}

/** 测试助手：取 query 参数对象。 */
function lastParams(method: 'get' | 'post'): Record<string, unknown> {
  const config = lastBody(method);
  if (!config.params || typeof config.params !== 'object') {
    throw new Error(`${method} 没有携带 params`);
  }
  return config.params as Record<string, unknown>;
}

/**
 * AI 查询规划 API 客户端（D05）：只断言"请求打到哪个端点、用什么 method、带什么参数"。
 *
 * <p>为什么不靠页面用例覆盖：这个域没有列表接口，页面把整个 api 模块 mock 掉之后
 * 请求映射就永远不会执行。这里用真实模块 + mock 的 requestClient 把两个端点钉住，
 * 路径写错（例如把 summary 写成 POST、或漏掉 /ai/query 前缀）会直接红。
 */
describe('ai query api', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    request.get.mockResolvedValue(null);
    request.post.mockResolvedValue(null);
  });

  it('生成计划：POST /ai/query/plan，返回值原样透传', async () => {
    request.post.mockResolvedValue({
      kind: 'PLAN',
      planDatasetId: 'dset_81',
      planHash: 'hash-1',
      planJson: '{"nodes":[]}',
    });

    await expect(
      createQueryPlan({
        datasetId: 81,
        endpointId: 7,
        question: '上月各渠道的订单总额',
      }),
    ).resolves.toStrictEqual({
      kind: 'PLAN',
      planDatasetId: 'dset_81',
      planHash: 'hash-1',
      planJson: '{"nodes":[]}',
    });

    expect(request.post).toHaveBeenCalledWith('/ai/query/plan', {
      datasetId: 81,
      endpointId: 7,
      question: '上月各渠道的订单总额',
    });
    expect(lastCall('post')[0]).toBe('/ai/query/plan');
  });

  it('生成计划：可选字段缺省时请求体里真的不出现', async () => {
    await createQueryPlan({
      datasetId: 81,
      endpointId: 7,
      question: '上月各渠道的订单总额',
    });

    // 客户端不得替后端补默认值：datasetVersionId / allowedFieldCodes / maxRepairs
    // 缺省时后端要按"取最新已发布版本 / 全部授权字段 / 夹取 0~2"处理，
    // 一旦客户端擅自填上就把这个语义改掉了，所以断言键集合而不是只断言带了值时正确。
    expect(Object.keys(lastBody('post'))).toStrictEqual([
      'datasetId',
      'endpointId',
      'question',
    ]);
    expect(lastBody('post')).not.toHaveProperty('datasetVersionId');
    expect(lastBody('post')).not.toHaveProperty('allowedFieldCodes');
    expect(lastBody('post')).not.toHaveProperty('maxRepairs');
    // 契约里没有任何 SQL 字段：客户端不得凭空造一个给模型输出回填
    expect(lastBody('post')).not.toHaveProperty('sql');
  });

  it('生成计划：可选字段带齐时逐个透传，不改名也不丢字段', async () => {
    await createQueryPlan({
      allowedFieldCodes: ['amount', 'channel'],
      datasetId: 81,
      datasetVersionId: 91,
      endpointId: 7,
      maxRepairs: 2,
      question: '上月各渠道的订单总额',
    });

    expect(request.post).toHaveBeenCalledWith('/ai/query/plan', {
      allowedFieldCodes: ['amount', 'channel'],
      datasetId: 81,
      datasetVersionId: 91,
      endpointId: 7,
      maxRepairs: 2,
      question: '上月各渠道的订单总额',
    });
  });

  it('数据集摘要：GET /ai/query/summary，数组参数留在 query 而不拼进路径', async () => {
    request.get.mockResolvedValue({
      datasetId: 81,
      summaryJson: '{"fields":[]}',
    });

    await expect(
      getQueryDatasetSummary({
        allowedFieldCodes: ['amount'],
        datasetId: 81,
        datasetVersionId: 91,
      }),
    ).resolves.toStrictEqual({ datasetId: 81, summaryJson: '{"fields":[]}' });

    expect(request.get).toHaveBeenCalledWith('/ai/query/summary', {
      params: {
        allowedFieldCodes: ['amount'],
        datasetId: 81,
        datasetVersionId: 91,
      },
    });
    expect(lastCall('get')[0]).toBe('/ai/query/summary');
    // 后端按 List<String> 绑定 query repeat，路径里出现数组说明序列化方式被改坏了
    expect(lastCall('get')[0]).not.toContain('amount');
  });

  it('数据集摘要：只给数据集编号时版本与字段码都不出现', async () => {
    await getQueryDatasetSummary({ datasetId: 81 });

    expect(request.get).toHaveBeenCalledWith('/ai/query/summary', {
      params: { datasetId: 81 },
    });
    expect(Object.keys(lastParams('get'))).toStrictEqual(['datasetId']);
    expect(lastParams('get')).not.toHaveProperty('datasetVersionId');
    expect(lastParams('get')).not.toHaveProperty('allowedFieldCodes');
  });

  it('两个端点不串门：计划只发 POST，摘要只发 GET', async () => {
    await createQueryPlan({ datasetId: 81, endpointId: 7, question: 'q' });
    expect(request.get).not.toHaveBeenCalled();
    expect(request.post).toHaveBeenCalledTimes(1);

    await getQueryDatasetSummary({ datasetId: 81 });
    expect(request.post).toHaveBeenCalledTimes(1);
    expect(request.get).toHaveBeenCalledTimes(1);
  });
});

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
  acceptWorkflowRun,
  createWorkflow,
  createWorkflowDraft,
  deleteWorkflow,
  discardWorkflowDraft,
  getOpenWorkflowDraft,
  getWorkflow,
  getWorkflowPage,
  getWorkflowRun,
  getWorkflowRunNodeList,
  getWorkflowRunPage,
  getWorkflowVersion,
  getWorkflowVersionPage,
  publishWorkflowVersion,
  updateWorkflow,
  updateWorkflowDraft,
  updateWorkflowStatus,
} = await import('./index');

type Method = 'delete' | 'get' | 'post' | 'put';

/** 测试助手：最近一次调用必须存在（不存在直接失败，避免非空断言）。 */
function lastCall(method: Method): unknown[] {
  const call = request[method].mock.calls.at(-1);
  if (!call) {
    throw new Error(`${method} 未被调用`);
  }
  return call;
}

/** 测试助手：取第二个参数（请求体或 config）。 */
function secondArg(method: Method): Record<string, unknown> {
  const value = lastCall(method)[1];
  if (typeof value !== 'object' || value === null) {
    throw new Error(`${method} 的第二个参数不是对象`);
  }
  return value as Record<string, unknown>;
}

/** 测试助手：取 query 参数对象（delete / get 走这条）。 */
function lastParams(method: Method): Record<string, unknown> {
  const config = secondArg(method);
  if (typeof config.params !== 'object' || config.params === null) {
    throw new Error(`${method} 没有携带 params`);
  }
  return config.params as Record<string, unknown>;
}

/**
 * 流程编排 API 客户端（X08）：钉住三个控制器上的 17 个端点。
 *
 * <p>为什么不靠组件用例覆盖：三个面板的用例都把本模块整体 mock 掉（`vi.mock('#/api/ai/workflow')`），
 * 请求映射永远不执行。这里用真实模块 + mock 的 requestClient 逐个比对路径与 method，
 * 字段名/绑定方式（@RequestBody 与 @RequestParam 的区别）写错会直接红。
 */
describe('ai workflow api', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    request.delete.mockResolvedValue(true);
    request.get.mockResolvedValue(null);
    request.post.mockResolvedValue(1);
    request.put.mockResolvedValue(true);
  });

  it('流程定义读侧：分页与详情都走 GET /ai/workflow', async () => {
    await getWorkflowPage({
      applicationId: 71,
      code: 'order',
      pageNo: 1,
      pageSize: 10,
      status: 'ENABLED',
    });
    expect(request.get).toHaveBeenCalledWith('/ai/workflow/page', {
      params: {
        applicationId: 71,
        code: 'order',
        pageNo: 1,
        pageSize: 10,
        status: 'ENABLED',
      },
    });

    await getWorkflow(81);
    expect(request.get).toHaveBeenCalledWith('/ai/workflow/get', {
      params: { id: 81 },
    });
    expect(Object.keys(lastParams('get'))).toStrictEqual(['id']);
  });

  it('流程定义分页：过滤条件缺省时 query 里只剩分页参数', async () => {
    await getWorkflowPage({ pageNo: 2, pageSize: 20 });

    expect(request.get).toHaveBeenCalledWith('/ai/workflow/page', {
      params: { pageNo: 2, pageSize: 20 },
    });
    expect(Object.keys(lastParams('get'))).toStrictEqual([
      'pageNo',
      'pageSize',
    ]);
    expect(lastParams('get')).not.toHaveProperty('applicationId');
    expect(lastParams('get')).not.toHaveProperty('code');
    expect(lastParams('get')).not.toHaveProperty('status');
  });

  it('流程定义写侧：新增 POST /create、修改 PUT /update', async () => {
    await createWorkflow({ name: '订单摘要生成流程' });
    expect(request.post).toHaveBeenCalledWith('/ai/workflow/create', {
      name: '订单摘要生成流程',
    });
    // 新增时不能带 id/version：带上后端会走成"修改"，且没有乐观锁基线
    expect(Object.keys(secondArg('post'))).toStrictEqual(['name']);
    expect(secondArg('post')).not.toHaveProperty('id');
    expect(secondArg('post')).not.toHaveProperty('version');
    expect(secondArg('post')).not.toHaveProperty('applicationId');
    expect(secondArg('post')).not.toHaveProperty('code');

    await updateWorkflow({
      applicationId: 71,
      code: 'order-summary-flow',
      description: '每晚汇总',
      id: 81,
      name: '订单摘要生成流程',
      version: 3,
    });
    expect(request.put).toHaveBeenCalledWith('/ai/workflow/update', {
      applicationId: 71,
      code: 'order-summary-flow',
      description: '每晚汇总',
      id: 81,
      name: '订单摘要生成流程',
      version: 3,
    });
  });

  it('启停：update-status 用 @RequestBody 整体做请求体，不塞进 query', async () => {
    await updateWorkflowStatus({ enabled: false, id: 81, version: 3 });

    // 与 dataset 的 @RequestParam 写法不同：这里是纯请求体，参数个数必须是 2
    expect(request.put).toHaveBeenCalledWith('/ai/workflow/update-status', {
      enabled: false,
      id: 81,
      version: 3,
    });
    expect(request.put.mock.calls.at(-1)).toHaveLength(2);
    expect(secondArg('put')).not.toHaveProperty('params');
    expect(Object.keys(secondArg('put'))).toStrictEqual([
      'enabled',
      'id',
      'version',
    ]);
  });

  it('删除：id 与乐观锁版本都作为 query 参数（@RequestParam）', async () => {
    await deleteWorkflow(81, 3);

    expect(request.delete).toHaveBeenCalledWith('/ai/workflow/delete', {
      params: { id: 81, version: 3 },
    });
    expect(Object.keys(lastParams('delete'))).toStrictEqual(['id', 'version']);
  });

  it('版本写侧：建草稿 POST，草稿编辑/发布/废弃都用 PUT', async () => {
    await createWorkflowDraft({ graphJson: '{"nodes":[]}', workflowId: 81 });
    expect(request.post).toHaveBeenCalledWith(
      '/ai/workflow-version/create-draft',
      {
        graphJson: '{"nodes":[]}',
        workflowId: 81,
      },
    );
    // 建草稿不收乐观锁版本：草稿是新建的，没有可比较的基线
    expect(Object.keys(secondArg('post'))).toStrictEqual([
      'graphJson',
      'workflowId',
    ]);
    expect(secondArg('post')).not.toHaveProperty('version');
    expect(secondArg('post')).not.toHaveProperty('versionId');

    await updateWorkflowDraft({
      graphJson: '{"nodes":[]}',
      version: 2,
      versionId: 91,
      workflowId: 81,
    });
    expect(request.put).toHaveBeenCalledWith(
      '/ai/workflow-version/update-draft',
      {
        graphJson: '{"nodes":[]}',
        version: 2,
        versionId: 91,
        workflowId: 81,
      },
    );

    await publishWorkflowVersion({ id: 91, version: 2 });
    expect(request.put).toHaveBeenCalledWith('/ai/workflow-version/publish', {
      id: 91,
      version: 2,
    });

    await discardWorkflowDraft({ id: 91, version: 2 });
    expect(request.put).toHaveBeenCalledWith('/ai/workflow-version/discard', {
      id: 91,
      version: 2,
    });
    // 三条命令共用 PUT，不能把其中一条误改成 POST 或 DELETE
    expect(request.put).toHaveBeenCalledTimes(3);
    expect(request.post).toHaveBeenCalledTimes(1);
  });

  it('版本读侧：详情只带 id、打开草稿只带流程编号', async () => {
    await getWorkflowVersion(91);
    expect(request.get).toHaveBeenCalledWith('/ai/workflow-version/get', {
      params: { id: 91 },
    });
    expect(Object.keys(lastParams('get'))).toStrictEqual(['id']);

    await getOpenWorkflowDraft(81);
    expect(request.get).toHaveBeenCalledWith(
      '/ai/workflow-version/open-draft',
      {
        params: { workflowId: 81 },
      },
    );
    // 参数名必须是 workflowId：写成 id 的话后端会按"版本编号"去查，永远返回空
    expect(Object.keys(lastParams('get'))).toStrictEqual(['workflowId']);
  });

  it('版本分页：状态与流程编号缺省时不出现，带上时逐个透传', async () => {
    await getWorkflowVersionPage({ pageNo: 1, pageSize: 20 });
    expect(request.get).toHaveBeenCalledWith('/ai/workflow-version/page', {
      params: { pageNo: 1, pageSize: 20 },
    });
    expect(Object.keys(lastParams('get'))).toStrictEqual([
      'pageNo',
      'pageSize',
    ]);
    expect(lastParams('get')).not.toHaveProperty('status');
    expect(lastParams('get')).not.toHaveProperty('workflowId');

    await getWorkflowVersionPage({
      pageNo: 1,
      pageSize: 20,
      status: 'DRAFT',
      workflowId: 81,
    });
    expect(request.get).toHaveBeenLastCalledWith('/ai/workflow-version/page', {
      params: { pageNo: 1, pageSize: 20, status: 'DRAFT', workflowId: 81 },
    });
  });

  it('受理运行：POST /ai/workflow-run/accept，预算缺省时不进请求体', async () => {
    await acceptWorkflowRun({
      dataLevel: 'L2_INTERNAL',
      idempotencyKey: 'idem-key-0000000001',
      workflowId: 81,
    });

    expect(request.post).toHaveBeenCalledWith('/ai/workflow-run/accept', {
      dataLevel: 'L2_INTERNAL',
      idempotencyKey: 'idem-key-0000000001',
      workflowId: 81,
    });
    // 预算留空表示"用平台默认/平台封顶"，填 0 语义完全不同，所以必须真的不出现
    expect(Object.keys(secondArg('post'))).toStrictEqual([
      'dataLevel',
      'idempotencyKey',
      'workflowId',
    ]);
    expect(secondArg('post')).not.toHaveProperty('inputText');
    expect(secondArg('post')).not.toHaveProperty('maxSteps');
    expect(secondArg('post')).not.toHaveProperty('maxDurationMillis');
  });

  it('受理运行：预算与输入带上时按数值透传', async () => {
    await acceptWorkflowRun({
      dataLevel: 'L1_PUBLIC',
      idempotencyKey: 'idem-key-0000000002',
      inputText: '订单 1001',
      maxDurationMillis: 30_000,
      maxSteps: 6,
      workflowId: 81,
    });

    expect(request.post).toHaveBeenCalledWith('/ai/workflow-run/accept', {
      dataLevel: 'L1_PUBLIC',
      idempotencyKey: 'idem-key-0000000002',
      inputText: '订单 1001',
      maxDurationMillis: 30_000,
      maxSteps: 6,
      workflowId: 81,
    });
  });

  it('运行读侧：详情只带 id、节点留痕只带 runId', async () => {
    await getWorkflowRun(301);
    expect(request.get).toHaveBeenCalledWith('/ai/workflow-run/get', {
      params: { id: 301 },
    });
    expect(Object.keys(lastParams('get'))).toStrictEqual(['id']);

    await getWorkflowRunNodeList(301);
    expect(request.get).toHaveBeenCalledWith('/ai/workflow-run/node-list', {
      params: { runId: 301 },
    });
    // 参数名必须是 runId：写成 id 会被后端按"运行主键"之外的字段忽略
    expect(Object.keys(lastParams('get'))).toStrictEqual(['runId']);
  });

  it('运行分页：状态与流程编号缺省时不出现，带上时逐个透传', async () => {
    await getWorkflowRunPage({ pageNo: 1, pageSize: 20 });
    expect(request.get).toHaveBeenCalledWith('/ai/workflow-run/page', {
      params: { pageNo: 1, pageSize: 20 },
    });
    expect(Object.keys(lastParams('get'))).toStrictEqual([
      'pageNo',
      'pageSize',
    ]);
    expect(lastParams('get')).not.toHaveProperty('status');
    expect(lastParams('get')).not.toHaveProperty('workflowId');

    await getWorkflowRunPage({
      pageNo: 2,
      pageSize: 20,
      status: 'FAILED',
      workflowId: 81,
    });
    expect(request.get).toHaveBeenLastCalledWith('/ai/workflow-run/page', {
      params: { pageNo: 2, pageSize: 20, status: 'FAILED', workflowId: 81 },
    });
  });

  it('返回值原样透传：受理响应里的节点事实与受控结束码不加工', async () => {
    const run = {
      errorCode: 'AI_TOOL_CONFIRMATION_REQUIRED',
      id: 301,
      nodes: [
        {
          nodeKey: 'tool',
          nodeType: 'TOOL',
          status: 'SKIPPED',
        },
      ],
      status: 'FAILED',
      workflowId: 81,
    };
    request.post.mockResolvedValue(run);

    await expect(
      acceptWorkflowRun({
        dataLevel: 'L2_INTERNAL',
        idempotencyKey: 'idem-key-0000000003',
        workflowId: 81,
      }),
    ).resolves.toStrictEqual(run);

    // 打开草稿没有时服务端返回 null，客户端不能兜底成空对象（那会让页面误判"有草稿"）
    request.get.mockResolvedValue(null);
    await expect(getOpenWorkflowDraft(81)).resolves.toBeNull();
  });
});

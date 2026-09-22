import { beforeEach, describe, expect, it, vi } from 'vitest';

import {
  createConnector,
  createDataset,
  createDatasetVersion,
  createTool,
  createToolVersion,
  deleteConnector,
  deleteDataset,
  deleteTool,
  executeConnectorOperation,
  getConnector,
  getConnectorPage,
  getDataset,
  getDatasetPage,
  getDatasetVersion,
  getDatasetVersionPage,
  getTool,
  getToolPage,
  getToolVersion,
  getToolVersionPage,
  importConnectorOperations,
  listConnectorOperations,
  listConnectorProbes,
  probeConnector,
  publishConnectorOperation,
  publishDatasetVersion,
  publishToolVersion,
  rotateConnectorCredential,
  updateConnector,
  updateConnectorStatus,
  updateDataset,
  updateDatasetStatus,
  updateTool,
  updateToolStatus,
  verifyDatasetVersion,
} from './index';

const requestClient = vi.hoisted(() => ({
  delete: vi.fn(),
  get: vi.fn(),
  post: vi.fn(),
  put: vi.fn(),
}));

vi.mock('#/api/request', () => ({ requestClient }));

/** D10 前端 API 契约：路径、方法与载荷与后端控制器一一对应。 */
describe('ai data api contracts', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('连接器查询与连接测试路径', async () => {
    await getConnectorPage({ pageNo: 1, pageSize: 10, connectorType: 'MYSQL' });
    await getConnector(71);
    await listConnectorProbes(71);
    await probeConnector(71);

    expect(requestClient.get).toHaveBeenCalledWith('/ai/connector/page', {
      params: { connectorType: 'MYSQL', pageNo: 1, pageSize: 10 },
    });
    expect(requestClient.get).toHaveBeenCalledWith('/ai/connector/get', {
      params: { id: 71 },
    });
    expect(requestClient.get).toHaveBeenCalledWith('/ai/connector/71/probe');
    expect(requestClient.post).toHaveBeenCalledWith('/ai/connector/71/probe');
  });

  it('连接器写入带乐观锁版本，秘密只在写入时出现', async () => {
    await createConnector({
      code: 'crm-readonly',
      configJson: '{}',
      connectorType: 'MYSQL',
      name: 'CRM',
    });
    await updateConnector({
      code: 'crm-readonly',
      configJson: '{}',
      connectorType: 'MYSQL',
      id: 71,
      name: 'CRM',
      version: 2,
    });
    await rotateConnectorCredential(71, 2, 'secret-1');
    await updateConnectorStatus(71, 2, false);
    await deleteConnector(71, 2);

    expect(requestClient.post).toHaveBeenCalledWith('/ai/connector/create', {
      code: 'crm-readonly',
      configJson: '{}',
      connectorType: 'MYSQL',
      name: 'CRM',
    });
    expect(requestClient.put).toHaveBeenCalledWith('/ai/connector/update', {
      code: 'crm-readonly',
      configJson: '{}',
      connectorType: 'MYSQL',
      id: 71,
      name: 'CRM',
      version: 2,
    });
    expect(requestClient.put).toHaveBeenCalledWith(
      '/ai/connector/rotate-credential',
      null,
      { params: { credential: 'secret-1', id: 71, version: 2 } },
    );
    expect(requestClient.put).toHaveBeenCalledWith(
      '/ai/connector/update-status',
      null,
      { params: { enabled: false, id: 71, version: 2 } },
    );
    expect(requestClient.delete).toHaveBeenCalledWith('/ai/connector/delete', {
      params: { id: 71, version: 2 },
    });
  });

  it('接口导入/发布/试跑：试跑载荷没有 URL 与请求头字段', async () => {
    await importConnectorOperations(71, '{"openapi":"3.0.0"}');
    await listConnectorOperations(71);
    await publishConnectorOperation(81, 0);
    await executeConnectorOperation(71, 'getOrders', { region: 'EAST' });

    expect(requestClient.post).toHaveBeenCalledWith('/ai/connector/71/import', {
      documentJson: '{"openapi":"3.0.0"}',
    });
    expect(requestClient.get).toHaveBeenCalledWith(
      '/ai/connector/71/operations',
    );
    expect(requestClient.post).toHaveBeenCalledWith(
      '/ai/connector/operation/publish',
      { id: 81, version: 0 },
    );
    const executeCall = requestClient.post.mock.calls.find(
      (call) => call[0] === '/ai/connector/operation/execute',
    );
    expect(Object.keys(executeCall?.[1] as object).toSorted()).toEqual([
      'arguments',
      'connectorId',
      'operationKey',
    ]);
  });

  it('数据集与版本路径（验证/发布都带乐观锁版本）', async () => {
    await getDatasetPage({ pageNo: 1, pageSize: 10 });
    await getDataset(81);
    await createDataset({
      code: 'crm-orders',
      connectorId: 71,
      name: 'CRM 订单',
      sourceObject: 'crm.orders',
    });
    await updateDataset({
      code: 'crm-orders',
      connectorId: 71,
      id: 81,
      name: 'CRM 订单',
      sourceObject: 'crm.orders',
      version: 3,
    });
    await updateDatasetStatus(81, 3, false);
    await deleteDataset(81, 3);
    await createDatasetVersion(81, '{"grain":"一行一单"}');
    await verifyDatasetVersion(91, 0);
    await publishDatasetVersion(91, 1);
    await getDatasetVersion(91);
    await getDatasetVersionPage(81, { pageNo: 1, pageSize: 10 });

    expect(requestClient.post).toHaveBeenCalledWith('/ai/dataset/create', {
      code: 'crm-orders',
      connectorId: 71,
      name: 'CRM 订单',
      sourceObject: 'crm.orders',
    });
    expect(requestClient.post).toHaveBeenCalledWith(
      '/ai/dataset/version/create',
      { datasetId: 81, definitionJson: '{"grain":"一行一单"}' },
    );
    expect(requestClient.post).toHaveBeenCalledWith(
      '/ai/dataset/version/verify',
      { version: 0, versionId: 91 },
    );
    expect(requestClient.post).toHaveBeenCalledWith(
      '/ai/dataset/version/publish',
      { version: 1, versionId: 91 },
    );
    expect(requestClient.get).toHaveBeenCalledWith('/ai/dataset/version/get', {
      params: { versionId: 91 },
    });
    expect(requestClient.get).toHaveBeenCalledWith('/ai/dataset/version/page', {
      params: { datasetId: 81, pageNo: 1, pageSize: 10 },
    });
  });

  it('工具与版本路径（政策与来源都在版本里）', async () => {
    await getToolPage({ pageNo: 1, pageSize: 10 });
    await getTool(91);
    await createTool({
      code: 'query-orders',
      connectorId: 71,
      name: '查询订单',
    });
    await updateTool({
      code: 'query-orders',
      connectorId: 71,
      id: 91,
      name: '查询订单',
      version: 2,
    });
    await updateToolStatus(91, 2, false);
    await deleteTool(91, 2);
    await createToolVersion({
      inputSchemaJson: '{}',
      outputSchemaJson: '{}',
      policy: 'DENY',
      sourceKind: 'HTTP_OPERATION',
      sourceRef: 'getOrders',
      toolId: 91,
      toolType: 'READ',
    });
    await publishToolVersion(101, 0);
    await getToolVersion(101);
    await getToolVersionPage(91, { pageNo: 1, pageSize: 10 });

    expect(requestClient.post).toHaveBeenCalledWith('/ai/tool/version/create', {
      inputSchemaJson: '{}',
      outputSchemaJson: '{}',
      policy: 'DENY',
      sourceKind: 'HTTP_OPERATION',
      sourceRef: 'getOrders',
      toolId: 91,
      toolType: 'READ',
    });
    expect(requestClient.post).toHaveBeenCalledWith(
      '/ai/tool/version/publish',
      { version: 0, versionId: 101 },
    );
    expect(requestClient.get).toHaveBeenCalledWith('/ai/tool/version/page', {
      params: { pageNo: 1, pageSize: 10, toolId: 91 },
    });
    expect(requestClient.delete).toHaveBeenCalledWith('/ai/tool/delete', {
      params: { id: 91, version: 2 },
    });
  });
});

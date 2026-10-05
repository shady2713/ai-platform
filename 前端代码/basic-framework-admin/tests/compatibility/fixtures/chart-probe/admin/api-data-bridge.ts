/**
 * `#/api/ai/data` 的探针桥接（数据集 + 连接器共用该模块）。
 *
 * <p>同 `api-semantic-bridge.ts`：只换 HTTP 传输，页面自己的查询/提交函数保持生产实现。
 * 连接器的 `configJson`（声明式配置）与数据集的 `credentialConfigured`（凭据是否已配）
 * 都按**列表真实会返回的形状**给：只给"是否已配置"，不给凭据明文——
 * 这样"界面拿不到秘密"才是被真实断言的事实，而不是夹具自己没放秘密。
 */
import { getState, recordApiCall } from './state';

function page<T>(): { list: T[]; total: number } {
  const rows = getState().rows;
  return { list: rows as T[], total: rows.length };
}

/* --- 数据集 --- */

export function getDatasetPage(params: unknown) {
  recordApiCall('getDatasetPage', params);
  return Promise.resolve(page());
}

export function createDataset(data: unknown) {
  recordApiCall('createDataset', data);
  return Promise.resolve(1);
}

export function updateDataset(data: unknown) {
  recordApiCall('updateDataset', data);
  return Promise.resolve(true);
}

export function updateDatasetStatus(
  id: unknown,
  version: unknown,
  enabled: unknown,
) {
  recordApiCall('updateDatasetStatus', { enabled, id, version });
  return Promise.resolve(true);
}

export function deleteDataset(id: unknown, version: unknown) {
  recordApiCall('deleteDataset', { id, version });
  return Promise.resolve(true);
}

export function getDatasetVersionPage(params: unknown) {
  recordApiCall('getDatasetVersionPage', params);
  return Promise.resolve({ list: [], total: 0 });
}

export function createDatasetVersion(data: unknown) {
  recordApiCall('createDatasetVersion', data);
  return Promise.resolve(1);
}

export function verifyDatasetVersion(data: unknown) {
  recordApiCall('verifyDatasetVersion', data);
  return Promise.resolve(true);
}

export function publishDatasetVersion(data: unknown) {
  recordApiCall('publishDatasetVersion', data);
  return Promise.resolve(true);
}

/* --- 连接器 --- */

export function getConnectorPage(params: unknown) {
  recordApiCall('getConnectorPage', params);
  return Promise.resolve(page());
}

export function createConnector(data: unknown) {
  recordApiCall('createConnector', data);
  return Promise.resolve(1);
}

export function updateConnector(data: unknown) {
  recordApiCall('updateConnector', data);
  return Promise.resolve(true);
}

export function updateConnectorStatus(
  id: unknown,
  version: unknown,
  enabled: unknown,
) {
  recordApiCall('updateConnectorStatus', { enabled, id, version });
  return Promise.resolve(true);
}

export function deleteConnector(id: unknown, version: unknown) {
  recordApiCall('deleteConnector', { id, version });
  return Promise.resolve(true);
}

export function probeConnector(data: unknown) {
  recordApiCall('probeConnector', data);
  return Promise.resolve({ messages: [], success: true });
}

export function listConnectorOperations(params: unknown) {
  recordApiCall('listConnectorOperations', params);
  return Promise.resolve([]);
}

export function importConnectorOperations(data: unknown) {
  recordApiCall('importConnectorOperations', data);
  return Promise.resolve(0);
}

export function publishConnectorOperation(data: unknown) {
  recordApiCall('publishConnectorOperation', data);
  return Promise.resolve(true);
}

export function executeConnectorOperation(data: unknown) {
  recordApiCall('executeConnectorOperation', data);
  return Promise.resolve({});
}

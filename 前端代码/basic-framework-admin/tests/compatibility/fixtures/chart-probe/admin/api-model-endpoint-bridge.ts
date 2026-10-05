/**
 * `#/api/ai/model-endpoint` 的探针桥接：只换 HTTP 传输。
 *
 * <p>凭据相关动作（`rotateModelEndpointCredential`、`probeModelEndpoint`）的入参会被记进状态仓，
 * 于是"轮换凭据到底提交了什么"可以在真实浏览器里逐字段断言。
 * 列表行只含 `credentialConfigured`（布尔），与生产列表契约一致，不含凭据明文。
 */
import { getState, recordApiCall } from './state';

function page<T>(): { list: T[]; total: number } {
  const rows = getState().rows;
  return { list: rows as T[], total: rows.length };
}

export function getModelEndpointPage(params: unknown) {
  recordApiCall('getModelEndpointPage', params);
  return Promise.resolve(page());
}

export function getModelEndpoint(id: unknown) {
  recordApiCall('getModelEndpoint', id);
  const found = getState().rows.find((row) => row.id === id);
  return Promise.resolve(found);
}

export function createModelEndpoint(data: unknown) {
  recordApiCall('createModelEndpoint', data);
  return Promise.resolve(1);
}

export function updateModelEndpoint(data: unknown) {
  recordApiCall('updateModelEndpoint', data);
  return Promise.resolve(true);
}

export function updateModelEndpointStatus(
  id: unknown,
  version: unknown,
  enabled: unknown,
) {
  recordApiCall('updateModelEndpointStatus', { enabled, id, version });
  return Promise.resolve(true);
}

export function deleteModelEndpoint(id: unknown, version: unknown) {
  recordApiCall('deleteModelEndpoint', { id, version });
  return Promise.resolve(true);
}

export function rotateModelEndpointCredential(data: unknown) {
  recordApiCall('rotateModelEndpointCredential', data);
  return Promise.resolve({ rotatedAt: '2026-10-05T00:00:00', version: 2 });
}

export function probeModelEndpoint(data: unknown) {
  recordApiCall('probeModelEndpoint', data);
  return Promise.resolve({ messages: [], success: true });
}

export function getModelEndpointProbeResults(id: unknown) {
  recordApiCall('getModelEndpointProbeResults', id);
  return Promise.resolve([]);
}

export function getModelEndpointCapabilities(id: unknown) {
  recordApiCall('getModelEndpointCapabilities', id);
  return Promise.resolve([]);
}

/**
 * `#/api/ai/grant` 的探针桥接：**只替换 HTTP 传输**，返回冻结夹具行。
 *
 * <p>撤销（`revokeGrant`）是 Y05 语义的关键动作：生产 `handleRevoke` 调用
 * `revokeGrant(row.id, row.version)`——**必须携带乐观锁版本**。桥接把入参记进状态仓，
 * 于是"撤销到底提交了什么"在真实浏览器里可被逐字段断言。
 */
import { getState, recordApiCall } from './state';

export function getGrantPage(params: unknown) {
  recordApiCall('getGrantPage', params);
  const rows = getState().rows;
  return Promise.resolve({ list: rows, total: rows.length });
}

export function getGrant(id: unknown) {
  recordApiCall('getGrant', id);
  const found = getState().rows.find((row) => row.id === id);
  return Promise.resolve(found);
}

export function createGrant(data: unknown) {
  recordApiCall('createGrant', data);
  return Promise.resolve(1);
}

export function updateGrant(data: unknown) {
  recordApiCall('updateGrant', data);
  return Promise.resolve(true);
}

export function revokeGrant(id: unknown, version: unknown) {
  recordApiCall('revokeGrant', { id, version });
  return Promise.resolve(true);
}

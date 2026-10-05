/**
 * `#/api/ai/semantic` 的探针桥接：**只替换 HTTP 传输**，返回冻结夹具行。
 *
 * <p>页面自己的查询/提交函数（`views/ai/semantic/index.vue` 里的
 * `proxyConfig.ajax.query` 与 `handleSubmit`）保持生产实现，因此
 * "页面提交了哪些对象事实"是生产行为，可在浏览器里断言。
 */
import { getState, recordApiCall } from './state';

function page<T>(): { list: T[]; total: number } {
  const rows = getState().rows;
  return { list: rows as T[], total: rows.length };
}

export function getMasterObjectPage(params: unknown) {
  recordApiCall('getMasterObjectPage', params);
  return Promise.resolve(page());
}

export function getMasterObjectByCode(objectCode: string) {
  recordApiCall('getMasterObjectByCode', objectCode);
  const found = getState().rows.find((row) => row.objectCode === objectCode);
  return Promise.resolve(found);
}

export function createMasterObject(data: unknown) {
  recordApiCall('createMasterObject', data);
  return Promise.resolve(1);
}

export function updateMasterObject(data: unknown) {
  recordApiCall('updateMasterObject', data);
  return Promise.resolve(true);
}

export function updateMasterObjectStatus(
  id: unknown,
  version: unknown,
  status: unknown,
) {
  recordApiCall('updateMasterObjectStatus', { id, status, version });
  return Promise.resolve(true);
}

/* 以下为弹窗子组件（判定/发布）所需，浏览器验收不覆盖其流程，仅保证模块图可构建。 */

export function getRevisionPage(params: unknown) {
  recordApiCall('getRevisionPage', params);
  return Promise.resolve({ list: [], total: 0 });
}

export function getRevisionDetail(
  masterObjectId: unknown,
  revisionNo: unknown,
) {
  recordApiCall('getRevisionDetail', { masterObjectId, revisionNo });
  return Promise.resolve(undefined);
}

export function createRevision(data: unknown) {
  recordApiCall('createRevision', data);
  return Promise.resolve(1);
}

export function createMappingEntry(data: unknown) {
  recordApiCall('createMappingEntry', data);
  return Promise.resolve(1);
}

export function deleteMappingEntry(id: unknown, version: unknown) {
  recordApiCall('deleteMappingEntry', { id, version });
  return Promise.resolve(true);
}

export function publishRevision(data: unknown) {
  recordApiCall('publishRevision', data);
  return Promise.resolve(true);
}

export function resolveObjectKey(data: unknown) {
  recordApiCall('resolveObjectKey', data);
  return Promise.resolve(undefined);
}

export function resolveSourceKey(data: unknown) {
  recordApiCall('resolveSourceKey', data);
  return Promise.resolve(undefined);
}

export function getMasterObjectCatalog(params: unknown) {
  recordApiCall('getMasterObjectCatalog', params);
  return Promise.resolve([]);
}

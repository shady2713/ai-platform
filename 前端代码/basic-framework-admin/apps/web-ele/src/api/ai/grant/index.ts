import type { PageParam, PageResult } from '@vben/request';

import { requestClient } from '#/api/request';

export namespace AiGrantApi {
  /** 资源授权（动作白名单） */
  export interface Grant {
    id: number;
    applicationId: number;
    subjectType: string;
    externalUserId: string;
    resourceType: string;
    resourceKey: string;
    actions: string[];
    status: string;
    authzRevision: number;
    version: number;
    createTime?: Date;
  }

  /** 新增授权请求 */
  export interface GrantCreateReq {
    applicationId: number;
    subjectType: string;
    externalUserId?: string;
    resourceType: string;
    resourceKey: string;
    actions: string[];
  }

  /** 修改授权（只允许改动作白名单，递增授权版本） */
  export interface GrantUpdateReq {
    id: number;
    version: number;
    actions: string[];
  }
}

/** 查询授权分页 */
export function getGrantPage(params: PageParam) {
  return requestClient.get<PageResult<AiGrantApi.Grant>>('/ai/grant/page', {
    params,
  });
}

/** 查询授权详情 */
export function getGrant(id: number) {
  return requestClient.get<AiGrantApi.Grant>(`/ai/grant/get?id=${id}`);
}

/** 新增授权 */
export function createGrant(data: AiGrantApi.GrantCreateReq) {
  return requestClient.post<number>('/ai/grant/create', data);
}

/** 修改授权动作白名单（递增授权版本） */
export function updateGrant(data: AiGrantApi.GrantUpdateReq) {
  return requestClient.put<boolean>('/ai/grant/update', data);
}

/** 撤销授权（立即失效） */
export function revokeGrant(id: number, version: number) {
  return requestClient.put<boolean>(
    `/ai/grant/revoke?id=${id}&version=${version}`,
  );
}

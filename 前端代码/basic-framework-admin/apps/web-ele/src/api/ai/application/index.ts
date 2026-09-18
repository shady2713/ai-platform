import type { PageParam, PageResult } from '@vben/request';

import { requestClient } from '#/api/request';

export namespace AiApplicationApi {
  /** 应用（响应永不含凭据：只有 credentialConfigured 标识） */
  export interface Application {
    id: number;
    appCode: string;
    name: string;
    description: string;
    origins: string[];
    enabled: boolean;
    credentialConfigured: boolean;
    version: number;
    createTime?: Date;
  }

  /** 新增/修改请求（credential 只在提交非空时携带） */
  export interface ApplicationSaveReq {
    id?: number;
    appCode?: string;
    name: string;
    description?: string;
    origins: string[];
    credential?: string;
    version?: number;
  }

  /** 凭据签发结果：唯一会返回秘密明文的响应，只出现一次 */
  export interface CredentialIssue {
    applicationId: number;
    appCode: string;
    credentialId: number;
    secret: string;
  }
}

/** 查询应用分页 */
export function getApplicationPage(params: PageParam) {
  return requestClient.get<PageResult<AiApplicationApi.Application>>(
    '/ai/application/page',
    { params },
  );
}

/** 查询应用详情 */
export function getApplication(id: number) {
  return requestClient.get<AiApplicationApi.Application>(
    `/ai/application/get?id=${id}`,
  );
}

/** 创建应用（响应携带一次性秘密） */
export function createApplication(data: AiApplicationApi.ApplicationSaveReq) {
  return requestClient.post<AiApplicationApi.CredentialIssue>(
    '/ai/application/create',
    data,
  );
}

/** 修改应用（appCode 不可修改） */
export function updateApplication(data: AiApplicationApi.ApplicationSaveReq) {
  return requestClient.put<boolean>('/ai/application/update', data);
}

/** 启用/停用应用 */
export function updateApplicationStatus(
  id: number,
  version: number,
  enabled: boolean,
) {
  return requestClient.put<boolean>('/ai/application/update-status', {
    id,
    version,
    enabled,
  });
}

/** 轮换凭据（响应携带新秘密，旧凭据立即失效） */
export function rotateCredential(id: number, version: number) {
  return requestClient.put<AiApplicationApi.CredentialIssue>(
    `/ai/application/rotate-credential?id=${id}&version=${version}`,
  );
}

/** 吊销凭据（不签发新凭据） */
export function revokeCredential(id: number, version: number) {
  return requestClient.put<boolean>(
    `/ai/application/revoke-credential?id=${id}&version=${version}`,
  );
}

/** 删除应用（必须先吊销凭据） */
export function deleteApplication(id: number, version: number) {
  return requestClient.delete<boolean>(
    `/ai/application/delete?id=${id}&version=${version}`,
  );
}

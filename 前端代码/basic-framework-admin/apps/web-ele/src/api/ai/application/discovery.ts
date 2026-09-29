import type { PageParam, PageResult } from '@vben/request';

import { requestClient } from '#/api/request';

/**
 * 多系统授权发现与范围选择（Y01）。
 *
 * 三条前端纪律：
 * 1. 发现只回答"当前主体在哪些系统有哪些范围"——前端不做任何范围推断，也不缓存结论；
 * 2. 范围选择必须携带**发现接口给出的目录指纹**（选择依据可核验），选完得到选择指纹；
 * 3. 联邦映射的提交/批准/撤销使用独立权限 `ai:application:federation`，批准人由服务端登录态决定。
 */
export namespace AiDiscoveryApi {
  /** 分析范围模式（与服务端 AiAnalysisScopeMode 一一对应） */
  export type AnalysisScopeMode = 'CROSS_SYSTEM' | 'CURRENT_SYSTEM';

  /** 主体类型 */
  export type SubjectType = 'APP' | 'USER';

  /** 联邦映射状态 */
  export type FederationStatus = 'APPROVED' | 'PENDING' | 'REVOKED';

  /** 系统内的一条可访问范围 */
  export interface SystemScopeRow {
    actions: string[];
    resourceKey: string;
    resourceType: string;
  }

  /** 授权目录中的一个系统 */
  export interface SystemEntry {
    appCode: string;
    applicationId: number;
    currentSystem: boolean;
    externalUserId: string;
    federationId?: null | number;
    federationRevision?: null | number;
    scopeSource: string;
    scopeVersion: number;
    scopes: SystemScopeRow[];
    subjectType: string;
    systemFingerprint: string;
    systemName: string;
  }

  /** 多系统授权目录 */
  export interface Catalog {
    applicationId: number;
    catalogFingerprint: string;
    denied: boolean;
    entries: SystemEntry[];
    externalUserId: string;
    modelCatalog: string;
    subjectType: string;
  }

  /** 被选中的系统 */
  export interface SelectedSystem {
    appCode: string;
    applicationId: number;
    currentSystem: boolean;
    federationId?: null | number;
    systemFingerprint: string;
    systemName: string;
  }

  /** 范围选择结果（可核验事实） */
  export interface AnalysisScopeSelection {
    applicationId: number;
    catalogFingerprint: string;
    externalUserId: string;
    mode: AnalysisScopeMode;
    modelCatalog: string;
    selectionFingerprint: string;
    subjectType: string;
    systems: SelectedSystem[];
    targetSystemCodes: string[];
  }

  /** 范围选择请求 */
  export interface AnalysisScopeSelectReq {
    applicationId: number;
    catalogFingerprint: string;
    externalUserId?: string;
    mode: AnalysisScopeMode;
    subjectType: string;
    targetSystemCodes?: string[];
  }

  /** 历史选择再核验请求 */
  export interface AnalysisScopeVerifyReq extends AnalysisScopeSelectReq {
    selectionFingerprint: string;
  }

  /** 联邦映射 */
  export interface Federation {
    approvalNote?: null | string;
    approvedBy?: null | number;
    approvedTime?: null | string;
    id: number;
    requestedBy: number;
    requestedTime?: null | string;
    revision: number;
    sourceApplicationId: number;
    sourceExternalUserId: string;
    sourceSubjectType: string;
    status: FederationStatus;
    targetApplicationId: number;
    targetExternalUserId: string;
    targetSubjectType: string;
    version: number;
  }

  /** 联邦映射登记请求 */
  export interface FederationSubmitReq {
    sourceApplicationId: number;
    sourceExternalUserId?: string;
    sourceSubjectType: SubjectType;
    targetApplicationId: number;
    targetExternalUserId?: string;
    targetSubjectType: SubjectType;
  }
}

/** 发现当前主体可访问的系统与范围（无权系统不出现） */
export function getSystemCatalog(params: {
  applicationId: number;
  externalUserId?: string;
  subjectType: string;
}) {
  return requestClient.get<AiDiscoveryApi.Catalog>(
    '/ai/application/discovery/get',
    { params },
  );
}

/** 显式选择分析范围（必须携带看到的目录指纹） */
export function selectAnalysisScope(
  data: AiDiscoveryApi.AnalysisScopeSelectReq,
) {
  return requestClient.post<AiDiscoveryApi.AnalysisScopeSelection>(
    '/ai/application/discovery/select',
    data,
  );
}

/** 再核验历史范围选择（事实变化返回 409） */
export function verifyAnalysisScope(
  data: AiDiscoveryApi.AnalysisScopeVerifyReq,
) {
  return requestClient.post<AiDiscoveryApi.AnalysisScopeSelection>(
    '/ai/application/discovery/verify',
    data,
  );
}

/** 登记跨系统主体联邦映射（等待独立审批） */
export function submitSubjectFederation(
  data: AiDiscoveryApi.FederationSubmitReq,
) {
  return requestClient.post<number>('/ai/application/federation/submit', data);
}

/** 独立审批通过（批准人必须不同于提交人，由服务端登录态判定） */
export function approveSubjectFederation(data: {
  approvalNote?: string;
  id: number;
  version: number;
}) {
  return requestClient.put<boolean>('/ai/application/federation/approve', data);
}

/** 撤销联邦映射（立即不再参与发现） */
export function revokeSubjectFederation(id: number, version: number) {
  return requestClient.put<boolean>('/ai/application/federation/revoke', {
    id,
    version,
  });
}

/** 查询联邦映射详情 */
export function getSubjectFederation(id: number) {
  return requestClient.get<AiDiscoveryApi.Federation>(
    `/ai/application/federation/get?id=${id}`,
  );
}

/** 查询联邦映射分页 */
export function getSubjectFederationPage(
  params: PageParam & {
    sourceApplicationId?: number;
    status?: AiDiscoveryApi.FederationStatus;
  },
) {
  return requestClient.get<PageResult<AiDiscoveryApi.Federation>>(
    '/ai/application/federation/page',
    { params },
  );
}

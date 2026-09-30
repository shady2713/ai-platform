import type { PageParam, PageResult } from '@vben/request';

import { requestClient } from '#/api/request';

/**
 * 跨系统主数据映射（Y02）。
 *
 * 三条前端纪律：
 * 1. 判定与目录发现都**必须显式携带版本号与判定时刻**——前端不提供"用最新版本/当前时间"的
 *    省略入口，因为报表/产物要按受理时的版本与时刻解释；
 * 2. 冲突与过期由服务端事实判定：页面照实展示 `problem`（CONFLICT/EXPIRED）与错误码文案，
 *    不在前端"帮用户挑一个"；
 * 3. 维护动作（登记/发布）走独立权限 `ai:semantic:manage`，发布人由服务端登录态决定且必须
 *    不同于草稿创建人。
 */
export namespace AiSemanticApi {
  /** 对象类型（与服务端 AiMasterObjectType 词表一一对应） */
  export type MasterObjectType =
    | 'CUSTOMER'
    | 'EMPLOYEE'
    | 'ORGANIZATION'
    | 'OTHER'
    | 'PRODUCT'
    | 'SUPPLIER';

  /** 匹配方式（与服务端 AiMasterMappingMatchMethod 一一对应） */
  export type MatchMethod = 'MANUAL' | 'TRUSTED_FEED';

  /** 对象状态 */
  export type MasterObjectStatus = 'ACTIVE' | 'DISABLED';

  /** 映射版本状态 */
  export type RevisionStatus = 'DRAFT' | 'PUBLISHED';

  /** 条目问题（与服务端 AiMasterMappingProblem 一一对应） */
  export type MappingProblem = 'CONFLICT' | 'EXPIRED' | 'NONE';

  /** 反查未命中原因 */
  export type UnmappedReason = 'NOT_REGISTERED';

  /** 企业统一对象 */
  export interface MasterObject {
    createTime?: null | string;
    currentRevision: number;
    description: string;
    id: number;
    objectCode: string;
    objectName: string;
    objectType: MasterObjectType;
    status: MasterObjectStatus;
    version: number;
  }

  /** 新建/修改对象请求 */
  export interface MasterObjectSaveReq {
    description?: string;
    id?: number;
    objectCode?: string;
    objectName: string;
    objectType: MasterObjectType;
    version?: number;
  }

  /** 映射版本 */
  export interface Revision {
    createdBy: number;
    entryCount: number;
    mappingFingerprint: string;
    masterObjectId: number;
    publishedBy?: null | number;
    publishedTime?: null | string;
    revisionNo: number;
    status: RevisionStatus;
    validFrom: string;
    validTo?: null | string;
    version: number;
  }

  /** 映射条目 */
  export interface MappingEntry {
    applicationId: number;
    entityType: string;
    id: number;
    matchMethod: MatchMethod;
    problem: MappingProblem;
    sourceKey: string;
    sourceName: string;
    validFrom: string;
    validTo?: null | string;
    version: number;
  }

  /** 版本详情（含冲突预览） */
  export interface RevisionDetail {
    conflictKeys: string[];
    entries: MappingEntry[];
    publishable: boolean;
    revision: Revision;
  }

  /** 登记映射条目请求 */
  export interface MappingEntryCreateReq {
    applicationId: number;
    entityType: string;
    masterObjectId: number;
    matchMethod: MatchMethod;
    revisionNo: number;
    sourceKey: string;
    sourceName?: string;
    validFrom: string;
    validTo?: string;
  }

  /** 判定结果（携带版本号与冻结指纹） */
  export interface MappingResolution {
    applicationId: number;
    asOf: string;
    entityType: string;
    matchMethod: MatchMethod;
    masterObjectId: number;
    objectCode: string;
    objectName: string;
    objectType: MasterObjectType;
    revisionFingerprint: string;
    revisionNo: number;
    sourceKey: string;
    sourceName: string;
    validFrom: string;
    validTo?: null | string;
  }

  /** 源键反查结果（未命中时 mapped=false，其余字段为空） */
  export interface MappingReverse {
    applicationId: number;
    asOf: string;
    entityType: string;
    mapped: boolean;
    masterObjectId?: null | number;
    matchMethod?: MatchMethod | null;
    objectCode?: null | string;
    objectName?: null | string;
    objectType?: MasterObjectType | null;
    reason?: null | UnmappedReason;
    revisionFingerprint?: null | string;
    revisionNo?: null | number;
    sourceKey: string;
    sourceName?: null | string;
    validFrom?: null | string;
    validTo?: null | string;
  }

  /** 目录条目（含问题标注） */
  export interface CatalogEntry {
    appCode: string;
    applicationId: number;
    entityType: string;
    inForce: boolean;
    matchMethod: MatchMethod;
    problem: MappingProblem;
    sourceKey: string;
    sourceName: string;
    systemName: string;
    usable: boolean;
    validFrom: string;
    validTo?: null | string;
  }

  /** 主数据映射目录（无权系统不出现） */
  export interface ObjectCatalog {
    asOf: string;
    catalogFingerprint: string;
    denied: boolean;
    entries: CatalogEntry[];
    masterObjectId: number;
    modelCatalog: string;
    objectCode: string;
    objectName: string;
    objectType: MasterObjectType;
    revisionFingerprint: string;
    revisionNo: number;
  }
}

/** 企业统一对象分页 */
export function getMasterObjectPage(
  params: PageParam & {
    keyword?: string;
    objectType?: AiSemanticApi.MasterObjectType;
    status?: AiSemanticApi.MasterObjectStatus;
  },
) {
  return requestClient.get<PageResult<AiSemanticApi.MasterObject>>(
    '/ai/semantic/object/page',
    { params },
  );
}

/** 按标识查询对象（不存在时后端返回 404 语义） */
export function getMasterObjectByCode(objectCode: string) {
  return requestClient.get<AiSemanticApi.MasterObject>(
    `/ai/semantic/object/get-by-code?objectCode=${encodeURIComponent(objectCode)}`,
  );
}

/** 新建企业统一对象 */
export function createMasterObject(data: AiSemanticApi.MasterObjectSaveReq) {
  return requestClient.post<number>('/ai/semantic/object/create', data);
}

/** 修改企业统一对象（标识不可改） */
export function updateMasterObject(data: AiSemanticApi.MasterObjectSaveReq) {
  return requestClient.put<boolean>('/ai/semantic/object/update', data);
}

/** 启用/停用企业统一对象 */
export function updateMasterObjectStatus(
  id: number,
  version: number,
  enabled: boolean,
) {
  return requestClient.put<boolean>('/ai/semantic/object/update-status', {
    enabled,
    id,
    version,
  });
}

/** 映射版本分页 */
export function getRevisionPage(
  params: PageParam & {
    masterObjectId?: number;
    status?: AiSemanticApi.RevisionStatus;
  },
) {
  return requestClient.get<PageResult<AiSemanticApi.Revision>>(
    '/ai/semantic/revision/page',
    { params },
  );
}

/** 版本详情（条目 + 冲突预览 + 是否可发布） */
export function getRevisionDetail(masterObjectId: number, revisionNo: number) {
  return requestClient.get<AiSemanticApi.RevisionDetail>(
    `/ai/semantic/revision/detail?masterObjectId=${masterObjectId}&revisionNo=${revisionNo}`,
  );
}

/** 新建映射版本草稿 */
export function createRevision(data: {
  masterObjectId: number;
  validFrom: string;
  validTo?: string;
}) {
  return requestClient.post<number>('/ai/semantic/revision/create', data);
}

/** 登记源键映射（只认源键，不按同名推断） */
export function createMappingEntry(data: AiSemanticApi.MappingEntryCreateReq) {
  return requestClient.post<number>('/ai/semantic/mapping/create', data);
}

/** 删除草稿版本里的映射条目（已发布版本不可改） */
export function deleteMappingEntry(id: number, version: number) {
  return requestClient.delete<boolean>(
    `/ai/semantic/mapping/delete?id=${id}&version=${version}`,
  );
}

/** 发布映射版本（发布人必须不同于草稿创建人；冲突阻断） */
export function publishRevision(
  masterObjectId: number,
  revisionNo: number,
  version: number,
) {
  return requestClient.put<AiSemanticApi.Revision>(
    '/ai/semantic/revision/publish',
    {
      masterObjectId,
      revisionNo,
      version,
    },
  );
}

/** 按对象 + 显式版本判定源键（版本未发布/过期/冲突一律阻断） */
export function resolveObjectKey(data: {
  applicationId: number;
  asOf: string;
  entityType: string;
  objectCode: string;
  revisionNo: number;
}) {
  return requestClient.post<AiSemanticApi.MappingResolution>(
    '/ai/semantic/resolve/object-key',
    data,
  );
}

/** 按源键反查统一对象（未登记返回 mapped=false） */
export function resolveSourceKey(data: {
  applicationId: number;
  asOf: string;
  entityType: string;
  sourceKey: string;
}) {
  return requestClient.post<AiSemanticApi.MappingReverse>(
    '/ai/semantic/resolve/source-key',
    data,
  );
}

/** 发现某主体可见的主数据映射目录（无权系统不出现） */
export function getMasterObjectCatalog(params: {
  applicationId: number;
  asOf: string;
  externalUserId?: string;
  objectCode: string;
  revisionNo: number;
  subjectType: string;
}) {
  return requestClient.get<AiSemanticApi.ObjectCatalog>(
    '/ai/semantic/catalog/get',
    { params },
  );
}

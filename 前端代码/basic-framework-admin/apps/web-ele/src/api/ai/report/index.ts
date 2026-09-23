import type { PageParam, PageResult } from '@vben/request';

import { RequestClient } from '@vben/request';

/**
 * 报表域应用端 API（R07）：保存 / 版本读取 / 对话修改 / 刷新。
 *
 * 通道与 O08 的在线调试一致：走**应用端**（`/app-api`）并用短期票据，
 * 不复用管理端 Cookie/令牌——报表归属（应用 + 主体 + 外部用户标识）由服务端会话决定，
 * 客户端既不能自报归属，也不能提供行范围（数据类修改/刷新需要授权层的行范围上下文）。
 */

/** 应用端通道：与管理端分离，避免把管理端令牌带到应用端接口上。 */
const appApiClient = new RequestClient({
  baseURL: '/app-api',
  withCredentials: false,
});

function auth(ticket: string) {
  // 票据只驻留内存：由调用方持有并逐次传入，不写入任何持久化存储
  return { headers: { Authorization: `Bearer ${ticket}` } };
}

export namespace AiReportApi {
  /** 报表元信息 */
  export interface Report {
    code: string;
    createTime?: Date;
    description?: string;
    id: number;
    latestVersionNo: number;
    mode: string;
    name: string;
    publishedVersionNo: number;
    releaseId?: number;
    schemaVersion: string;
    serviceId?: number;
    themeId?: string;
    themeRevision?: number;
    updateTime?: Date;
    version: number;
  }

  /** 版本摘要（不含规格与数据正文） */
  export interface VersionBrief {
    asOf?: Date;
    completeness?: string;
    createTime?: Date;
    createdByRun?: string;
    id: number;
    mode: string;
    versionNo: number;
  }

  /** 版本（含规格与快照数据；可刷新版本的数据为空） */
  export interface Version {
    asOf?: Date;
    completeness?: string;
    createTime?: Date;
    createdByRun?: string;
    dataJson?: string;
    id: number;
    mode: string;
    reportId: number;
    sourcesJson?: string;
    specJson: string;
    versionNo: number;
  }

  /** 保存请求（不带编号为新建；带编号与乐观锁版本为保存新版本） */
  export interface SaveReq {
    code?: string;
    completeness?: string;
    createdByRun?: string;
    dataJson?: string;
    description?: string;
    id?: number;
    mode: string;
    name: string;
    schemaVersion?: string;
    serviceId?: number;
    specJson: string;
    themeId?: string;
    themeRevision?: number;
    version?: number;
  }

  /** 对话修改请求（归属与行范围都不在请求体里） */
  export interface ReviseReq {
    baseVersionNo: number;
    createdByRun?: string;
    datasetId?: number;
    datasetVersionId?: number;
    endpointId: number;
    id: number;
    instruction: string;
    version: number;
  }

  /** 对话修改结果（APPLIED 产生新版本；CLARIFICATION 只返回追问） */
  export interface RevisionResult {
    baseVersionNo?: number;
    clarificationCandidates?: { code: string; label: string }[];
    clarificationQuestion?: string;
    clarificationReason?: string;
    diff?: {
      addedBlocks?: string[];
      addedDatasetRefs?: string[];
      layoutChanged?: boolean;
      modifiedBlocks?: string[];
      operations?: string[];
      queryRequired?: boolean;
      removedBlocks?: string[];
      titleChanged?: boolean;
    };
    newVersionNo?: number;
    notes?: string[];
    outcome: string;
    queryPerformed?: boolean;
    reportId?: number;
  }

  /** 刷新请求 */
  export interface RefreshReq {
    createdByRun?: string;
    id: number;
  }

  /** 刷新结果（OK/UNCHANGED/FAILED 都是正常结果） */
  export interface RefreshResult {
    asOf?: Date;
    baseVersionNo?: number;
    completeness?: string;
    dataJson?: string;
    note?: string;
    reason?: string;
    reportId?: number;
    resultVersionNo?: number;
    status: string;
  }

  /** 刷新状态（上次刷新时间/结果/原因 + 上一次结果） */
  export interface RefreshState {
    asOf?: Date;
    attempted: boolean;
    completeness?: string;
    dataJson?: string;
    reason?: string;
    reportId?: number;
    resultVersionNo?: number;
    status?: string;
  }
}

/** 分页查询当前主体的报表 */
export async function getReportPage(
  ticket: string,
  params: PageParam & { mode?: string },
): Promise<PageResult<AiReportApi.Report>> {
  return appApiClient.get<PageResult<AiReportApi.Report>>('/ai/report/page', {
    params,
    ...auth(ticket),
  });
}

/** 查询报表元信息（越权与不存在同语义） */
export async function getReport(ticket: string, id: number) {
  return appApiClient.get<AiReportApi.Report>('/ai/report/get', {
    params: { id },
    ...auth(ticket),
  });
}

/** 版本列表（不含规格与数据正文） */
export async function listReportVersions(ticket: string, id: number) {
  return appApiClient.get<AiReportApi.VersionBrief[]>('/ai/report/versions', {
    params: { id },
    ...auth(ticket),
  });
}

/** 读取当前生效版本（服务端复核保存时的授权范围） */
export async function readCurrentReportVersion(ticket: string, id: number) {
  return appApiClient.get<AiReportApi.Version>('/ai/report/current', {
    params: { id },
    ...auth(ticket),
  });
}

/** 读取指定版本（旧版本同样复核授权范围） */
export async function readReportVersion(
  ticket: string,
  id: number,
  versionNo: number,
) {
  return appApiClient.get<AiReportApi.Version>('/ai/report/version', {
    params: { id, versionNo },
    ...auth(ticket),
  });
}

/** 保存报表（新建或保存新版本；乐观锁不一致返回 409） */
export async function saveReport(ticket: string, data: AiReportApi.SaveReq) {
  return appApiClient.post<number>('/ai/report/save', data, auth(ticket));
}

/** 对话修改报表（展示类复用保存时的数据；数据类按当前权限重新查询） */
export async function reviseReport(
  ticket: string,
  data: AiReportApi.ReviseReq,
) {
  return appApiClient.post<AiReportApi.RevisionResult>(
    '/ai/report/revise',
    data,
    auth(ticket),
  );
}

/** 刷新可刷新报表（失败保留旧结果并留痕） */
export async function refreshReport(
  ticket: string,
  data: AiReportApi.RefreshReq,
) {
  return appApiClient.post<AiReportApi.RefreshResult>(
    '/ai/report/refresh',
    data,
    auth(ticket),
  );
}

/** 上次刷新状态与上一次结果（读取结果前服务端按当前权限复核） */
export async function readRefreshState(ticket: string, id: number) {
  return appApiClient.get<AiReportApi.RefreshState>('/ai/report/refresh/last', {
    params: { id },
    ...auth(ticket),
  });
}

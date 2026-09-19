import type { PageParam, PageResult } from '@vben/request';

import { requestClient } from '#/api/request';

export namespace AiServiceApi {
  /** 服务草稿（发布版本内容不可变，草稿可反复编辑） */
  export interface Service {
    id: number;
    appId: number;
    code: string;
    name: string;
    description?: string;
    status: string;
    modelEndpointId: number;
    promptTemplate: string;
    inputSchema: string;
    outputSchema?: string;
    requiredCapabilities: string[];
    runSubjectType: string;
    evalThreshold: number;
    draftRevision: number;
    version: number;
    createTime?: Date;
  }

  /** 新增/修改草稿请求 */
  export interface ServiceSaveReq {
    id?: number;
    appId: number;
    code: string;
    name: string;
    description?: string;
    modelEndpointId: number;
    promptTemplate: string;
    inputSchema: string;
    outputSchema?: string;
    requiredCapabilities: string[];
    runSubjectType: string;
    evalThreshold: number;
    version?: number;
  }

  /** 资源绑定（releaseId 为空表示草稿绑定） */
  export interface ServiceResource {
    id: number;
    serviceId: number;
    releaseId?: number;
    resourceType: string;
    resourceKey: string;
    actions: string[];
    status: string;
    version: number;
    createTime?: Date;
  }

  /** 绑定资源请求 */
  export interface ServiceResourceSaveReq {
    serviceId: number;
    resourceType: string;
    resourceKey: string;
    actions: string[];
  }

  /** 能力校验结果 */
  export interface CapabilityResult {
    required: string[];
    publishable: string[];
    missing: string[];
    satisfied: boolean;
  }

  /** 发布版本（内容列不可变） */
  export interface Release {
    id: number;
    serviceId: number;
    releaseVersion: number;
    modelEndpointId: number;
    endpointConfigRevision: number;
    requiredCapabilities: string;
    evalThreshold: number;
    contentHash: string;
    status: string;
    version: number;
    createTime?: Date;
  }

  /** 评测记录 */
  export interface ReleaseEvaluation {
    id: number;
    releaseId: number;
    contentHash: string;
    endpointConfigRevision: number;
    score: number;
    threshold: number;
    passed: boolean;
    caseCount: number;
    notes?: string;
    createTime?: Date;
  }

  /** 运行解析结果（新运行按别名解析到的版本固定值） */
  export interface RunSnapshot {
    serviceId: number;
    releaseId: number;
    releaseVersion: number;
    status: string;
    contentHash: string;
    modelEndpointId: number;
    modelRevision: number;
    pinned: boolean;
    resources: Array<{
      id: number;
      resourceKey: string;
      resourceType: string;
      version: number;
    }>;
  }

  /** 调试请求（必须显式给出测试主体与数据分级） */
  export interface DebugRunReq {
    serviceId: number;
    testSubjectType: string;
    testSubjectId?: string;
    userMessage: string;
    history?: Array<{ content: string; role: string }>;
    businessContext?: string;
    dataLevel: string;
    timeoutMillis?: number;
    maxMessages?: number;
    maxTokens?: number;
  }

  /** 调试阶段摘要（只有阶段名、结果与耗时） */
  export interface DebugStage {
    stage: string;
    status: string;
    detail?: string;
    durationMs?: number;
  }

  /** 上下文分区统计（不含分区正文） */
  export interface ContextSectionStat {
    section: string;
    includedCount: number;
    droppedCount: number;
    estimatedTokens: number;
    truncated: boolean;
    sanitized: boolean;
  }

  /** 调试结果：阶段摘要 + 证据 + 可见输出 */
  export interface DebugResult {
    releaseId: number;
    releaseVersion: number;
    contentHash: string;
    modelEndpointId: number;
    modelRevision: number;
    testSubjectType: string;
    testSubjectId?: string;
    authorizedBindings: string[];
    sections: ContextSectionStat[];
    estimatedTokens: number;
    truncated: boolean;
    stages: DebugStage[];
    output?: string;
    structured: boolean;
    inputTokens?: number;
    outputTokens?: number;
    durationMs: number;
  }
}

/** 服务草稿分页 */
export function getServicePage(params: PageParam) {
  return requestClient.get<PageResult<AiServiceApi.Service>>(
    '/ai/service/page',
    {
      params,
    },
  );
}

/** 服务草稿详情 */
export function getService(id: number) {
  return requestClient.get<AiServiceApi.Service>(`/ai/service/get?id=${id}`);
}

/** 新增服务草稿 */
export function createService(data: AiServiceApi.ServiceSaveReq) {
  return requestClient.post<number>('/ai/service/create', data);
}

/** 修改服务草稿（配置变更递增 draftRevision） */
export function updateService(data: AiServiceApi.ServiceSaveReq) {
  return requestClient.put<boolean>('/ai/service/update', data);
}

/** 删除服务草稿 */
export function deleteService(id: number, version: number) {
  return requestClient.delete<boolean>(
    `/ai/service/delete?id=${id}&version=${version}`,
  );
}

/** 标记可发布（能力与资源都必须满足） */
export function markServiceReady(id: number, version: number) {
  return requestClient.put<boolean>(
    `/ai/service/mark-ready?id=${id}&version=${version}`,
  );
}

/** 能力校验（只读提示） */
export function checkServiceCapabilities(id: number) {
  return requestClient.get<AiServiceApi.CapabilityResult>(
    `/ai/service/check-capabilities?id=${id}`,
  );
}

/** 草稿资源绑定 */
export function listServiceResources(id: number) {
  return requestClient.get<AiServiceApi.ServiceResource[]>(
    `/ai/service/resources?id=${id}`,
  );
}

/** 绑定资源（越权或类型非法都拒绝） */
export function bindServiceResource(data: AiServiceApi.ServiceResourceSaveReq) {
  return requestClient.post<number>('/ai/service/bind-resource', data);
}

/** 解绑资源 */
export function unbindServiceResource(bindingId: number, version: number) {
  return requestClient.put<boolean>(
    `/ai/service/unbind-resource?bindingId=${bindingId}&version=${version}`,
  );
}

/** 创建发布候选（冻结内容、端点配置版本与资源绑定） */
export function createReleaseCandidate(serviceId: number, version: number) {
  return requestClient.post<number>('/ai/service/release/create-candidate', {
    serviceId,
    version,
  });
}

/** 记录评测结论（通过与否由平台按冻结门槛判定） */
export function recordReleaseEvaluation(
  releaseId: number,
  score: number,
  caseCount: number,
  notes?: string,
) {
  return requestClient.post<number>('/ai/service/release/evaluate', {
    releaseId,
    score,
    caseCount,
    notes,
  });
}

/** 发布（预检查通过后切换别名） */
export function publishRelease(releaseId: number, version: number) {
  return requestClient.post<boolean>('/ai/service/release/publish', {
    releaseId,
    version,
  });
}

/** 回退（把别名切回历史版本，只影响后续运行） */
export function rollbackRelease(releaseId: number, version: number) {
  return requestClient.post<boolean>('/ai/service/release/rollback', {
    releaseId,
    version,
  });
}

/** 停用（退役当前生效版本） */
export function disableRelease(serviceId: number, version: number) {
  return requestClient.post<boolean>('/ai/service/release/disable', {
    serviceId,
    version,
  });
}

/** 发布预检查（返回未满足项，空表示可发布） */
export function checkReleaseReadiness(releaseId: number) {
  return requestClient.get<string[]>('/ai/service/release/check-publish', {
    params: { releaseId },
  });
}

/** 服务的发布版本（倒序） */
export function listReleases(serviceId: number) {
  return requestClient.get<AiServiceApi.Release[]>('/ai/service/release/list', {
    params: { serviceId },
  });
}

/** 发布版本冻结的资源绑定快照 */
export function listReleaseBindings(releaseId: number) {
  return requestClient.get<AiServiceApi.ServiceResource[]>(
    '/ai/service/release/bindings',
    { params: { releaseId } },
  );
}

/** 发布版本的评测记录（最新在前） */
export function listReleaseEvaluations(releaseId: number) {
  return requestClient.get<AiServiceApi.ReleaseEvaluation[]>(
    '/ai/service/release/evaluations',
    { params: { releaseId } },
  );
}

/** 运行解析（新运行按别名解析到的版本固定值） */
export function resolveRunSnapshot(serviceId: number) {
  return requestClient.get<AiServiceApi.RunSnapshot>(
    '/ai/service/release/resolve',
    { params: { serviceId } },
  );
}

/** 调试运行（显式测试主体 + 当前生效版本） */
export function runServiceDebug(data: AiServiceApi.DebugRunReq) {
  return requestClient.post<AiServiceApi.DebugResult>(
    '/ai/service/debug/run',
    data,
  );
}

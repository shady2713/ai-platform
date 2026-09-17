import type { PageParam, PageResult } from '@vben/request';

import { requestClient } from '#/api/request';

export namespace AiModelEndpointApi {
  /** 模型端点（响应不含凭据，只有 credentialConfigured 标识） */
  export interface ModelEndpoint {
    id: number;
    name: string;
    provider: string;
    baseUrl: string;
    modelId: string;
    capabilities: string[];
    enabled: boolean;
    referenced: boolean;
    configRevision: number;
    credentialRevision: number;
    credentialConfigured: boolean;
    version: number;
    createTime?: Date;
  }

  /** 不可变配置版本 */
  export interface ModelEndpointRevision {
    id: number;
    revision: number;
    modelId: string;
    capabilities: string[];
    createTime?: Date;
  }

  /** 能力探测结论（不含提示词与上游报文） */
  export interface ModelProbeResult {
    probeKind: string;
    status: string;
    detailCode?: string;
    embeddingDimension?: number;
    latencyMs: number;
    configRevision: number;
  }

  /** 能力总览：声明、确认与可发布范围 */
  export interface CapabilityOverview {
    endpointId: number;
    declared: string[];
    supported: string[];
    publishable: string[];
  }

  /** 新增/修改请求 */
  export interface ModelEndpointSaveReq {
    id?: number;
    name: string;
    provider: string;
    baseUrl: string;
    modelId: string;
    capabilities: string[];
    credential?: string;
    version?: number;
  }
}

/** 查询模型端点分页 */
export function getModelEndpointPage(params: PageParam) {
  return requestClient.get<PageResult<AiModelEndpointApi.ModelEndpoint>>(
    '/ai/model-endpoint/page',
    { params },
  );
}

/** 查询模型端点详情 */
export function getModelEndpoint(id: number) {
  return requestClient.get<AiModelEndpointApi.ModelEndpoint>(
    `/ai/model-endpoint/get?id=${id}`,
  );
}

/** 创建模型端点 */
export function createModelEndpoint(
  data: AiModelEndpointApi.ModelEndpointSaveReq,
) {
  return requestClient.post<number>('/ai/model-endpoint/create', data);
}

/** 修改模型端点（非秘密配置生成新版本） */
export function updateModelEndpoint(
  data: AiModelEndpointApi.ModelEndpointSaveReq,
) {
  return requestClient.put<boolean>('/ai/model-endpoint/update', data);
}

/** 启用/停用模型端点 */
export function updateModelEndpointStatus(
  id: number,
  version: number,
  enabled: boolean,
) {
  return requestClient.put<boolean>('/ai/model-endpoint/update-status', {
    id,
    version,
    enabled,
  });
}

/** 轮换凭据（留空表示保留） */
export function rotateModelEndpointCredential(
  id: number,
  version: number,
  credential: string,
) {
  return requestClient.put<boolean>('/ai/model-endpoint/rotate-credential', {
    id,
    version,
    credential,
  });
}

/** 删除模型端点 */
export function deleteModelEndpoint(id: number, version: number) {
  return requestClient.delete<boolean>(
    `/ai/model-endpoint/delete?id=${id}&version=${version}`,
  );
}

/** 查询不可变配置版本列表 */
export function getModelEndpointRevisions(id: number) {
  return requestClient.get<AiModelEndpointApi.ModelEndpointRevision[]>(
    `/ai/model-endpoint/revisions?id=${id}`,
  );
}

/** 执行能力探测（真实调用上游） */
export function probeModelEndpoint(id: number) {
  return requestClient.post<AiModelEndpointApi.ModelProbeResult[]>(
    `/ai/model-endpoint/${id}/probe`,
  );
}

/** 查询最近一次探测结论 */
export function getModelEndpointProbeResults(id: number) {
  return requestClient.get<AiModelEndpointApi.ModelProbeResult[]>(
    `/ai/model-endpoint/${id}/probe`,
  );
}

/** 查询能力总览（声明、确认与可发布范围） */
export function getModelEndpointCapabilities(id: number) {
  return requestClient.get<AiModelEndpointApi.CapabilityOverview>(
    `/ai/model-endpoint/${id}/capabilities`,
  );
}

import type { PageParam, PageResult } from '@vben/request';

import { requestClient } from '#/api/request';

/**
 * 数据接入域 API（D10）：连接器 / 数据集 / 工具。
 *
 * 三张页面共用同一个模块，因为它们属于同一条"接入 → 语义 → 工具"链路，
 * 页面对字段的期待必须与后端 VO 一一对应（后端 VO 只属于协议层，前端不猜字段）。
 *
 * 注意：这里**没有**任何"执行任意 SQL / 脚本"的接口——连接器只按已发布 operation
 * 执行声明参数，数据集只按语义定义生成计划，工具只按版本政策执行。
 */
export namespace AiConnectorApi {
  /** 连接器（秘密永不回显，只有"是否已配置"） */
  export interface Connector {
    id: number;
    code: string;
    name: string;
    connectorType: string;
    status: string;
    configJson: string;
    credentialConfigured?: boolean;
    credentialRevision?: number;
    referenced?: boolean;
    version: number;
    createTime?: Date;
  }

  /** 新增/修改连接器请求（credential 只在写入时出现） */
  export interface ConnectorSaveReq {
    id?: number;
    code: string;
    name: string;
    connectorType: string;
    configJson: string;
    credential?: string;
    version?: number;
  }

  /** 连接测试结论（只回稳定原因码与耗时） */
  export interface ConnectorProbe {
    connectorId: number;
    probeKind: string;
    status: string;
    detailCode?: string;
    latencyMs?: number;
    createTime?: Date;
  }

  /** 连接器操作（声明式：方法/路径/参数/分页都来自导入与发布） */
  export interface ConnectorOperation {
    id: number;
    connectorId: number;
    operationKey: string;
    httpMethod: string;
    pathTemplate: string;
    summary?: string;
    parameterJson?: string;
    responseJson?: string;
    paginationJson?: string;
    status: string;
    version: number;
  }

  /** OpenAPI 导入结果（草稿 + 跳过原因） */
  export interface ConnectorImportResp {
    operationKeys: string[];
    skipped: string[];
  }

  /** 操作执行结果（分页结论 + 条目；截断与失败都有稳定原因码） */
  export interface ConnectorExecuteResp {
    status: string;
    pages?: number;
    itemCount?: number;
    stoppedReason?: string;
    detailCode?: string;
    items?: string[];
  }
}

/** 连接器分页 */
export async function getConnectorPage(
  params: PageParam & { connectorType?: string; status?: string },
) {
  return await requestClient.get<PageResult<AiConnectorApi.Connector>>(
    '/ai/connector/page',
    { params },
  );
}

/** 连接器详情 */
export async function getConnector(id: number) {
  return await requestClient.get<AiConnectorApi.Connector>(
    '/ai/connector/get',
    {
      params: { id },
    },
  );
}

/** 新增连接器 */
export async function createConnector(data: AiConnectorApi.ConnectorSaveReq) {
  return await requestClient.post<number>('/ai/connector/create', data);
}

/** 修改连接器 */
export async function updateConnector(data: AiConnectorApi.ConnectorSaveReq) {
  return await requestClient.put<boolean>('/ai/connector/update', data);
}

/** 轮换秘密（旧秘密立即失效） */
export async function rotateConnectorCredential(
  id: number,
  version: number,
  credential: string,
) {
  return await requestClient.put<boolean>(
    '/ai/connector/rotate-credential',
    null,
    {
      params: { credential, id, version },
    },
  );
}

/** 启停连接器 */
export async function updateConnectorStatus(
  id: number,
  version: number,
  enabled: boolean,
) {
  return await requestClient.put<boolean>('/ai/connector/update-status', null, {
    params: { enabled, id, version },
  });
}

/** 删除连接器（被数据集/工具引用时拒绝） */
export async function deleteConnector(id: number, version: number) {
  return await requestClient.delete<boolean>('/ai/connector/delete', {
    params: { id, version },
  });
}

/** 连接测试（HTTP 走出站策略；结论只记稳定原因码） */
export async function probeConnector(id: number) {
  return await requestClient.post<AiConnectorApi.ConnectorProbe>(
    `/ai/connector/${id}/probe`,
  );
}

/** 历史探测记录 */
export async function listConnectorProbes(id: number) {
  return await requestClient.get<AiConnectorApi.ConnectorProbe[]>(
    `/ai/connector/${id}/probe`,
  );
}

/** 导入 OpenAPI 文档（产生草稿操作；外部 ref 与 header 参数会被跳过并记录原因） */
export async function importConnectorOperations(
  id: number,
  documentJson: string,
) {
  return await requestClient.post<AiConnectorApi.ConnectorImportResp>(
    `/ai/connector/${id}/import`,
    { documentJson },
  );
}

/** 已导入的操作（草稿与已发布） */
export async function listConnectorOperations(id: number) {
  return await requestClient.get<AiConnectorApi.ConnectorOperation[]>(
    `/ai/connector/${id}/operations`,
  );
}

/** 发布操作（草稿不可执行） */
export async function publishConnectorOperation(id: number, version: number) {
  return await requestClient.post<boolean>('/ai/connector/operation/publish', {
    id,
    version,
  });
}

/** 执行操作（只能填声明参数：没有 URL/请求头/SQL 输入面） */
export async function executeConnectorOperation(
  connectorId: number,
  operationKey: string,
  args: Record<string, unknown>,
) {
  return await requestClient.post<AiConnectorApi.ConnectorExecuteResp>(
    '/ai/connector/operation/execute',
    { arguments: args, connectorId, operationKey },
  );
}

export namespace AiDatasetApi {
  /** 数据集（来源对象必须在该连接器授权白名单内） */
  export interface Dataset {
    id: number;
    code: string;
    name: string;
    description?: string;
    connectorId: number;
    sourceObject: string;
    status: string;
    latestVersionNo?: number;
    publishedVersionNo?: number;
    version: number;
    createTime?: Date;
  }

  /** 新增/修改数据集请求（标识与来源创建后不可修改） */
  export interface DatasetSaveReq {
    id?: number;
    code: string;
    name: string;
    description?: string;
    connectorId: number;
    sourceObject: string;
    version?: number;
  }

  /** 语义版本（不可变快照 + 验证/漂移结论） */
  export interface DatasetVersion {
    id: number;
    datasetId: number;
    versionNo: number;
    status: string;
    definitionJson: string;
    schemaHash: string;
    sourceSchemaHash?: string;
    verificationStatus: string;
    driftJson?: string;
    verifiedAt?: Date;
    publishedAt?: Date;
    version: number;
  }

  /** 验证/发布结论（漂移只回列名与结论） */
  export interface DatasetVersionVerifyResp {
    versionId: number;
    versionNo: number;
    status: string;
    verificationStatus: string;
    missingColumns: string[];
    typeChangedColumns: string[];
    addedColumns: string[];
    schemaHash: string;
    sourceSchemaHash?: string;
    publishable: boolean;
  }
}

/** 数据集分页 */
export async function getDatasetPage(
  params: PageParam & { connectorId?: number; status?: string },
) {
  return await requestClient.get<PageResult<AiDatasetApi.Dataset>>(
    '/ai/dataset/page',
    { params },
  );
}

/** 数据集详情 */
export async function getDataset(id: number) {
  return await requestClient.get<AiDatasetApi.Dataset>('/ai/dataset/get', {
    params: { id },
  });
}

/** 新增数据集 */
export async function createDataset(data: AiDatasetApi.DatasetSaveReq) {
  return await requestClient.post<number>('/ai/dataset/create', data);
}

/** 修改数据集 */
export async function updateDataset(data: AiDatasetApi.DatasetSaveReq) {
  return await requestClient.put<boolean>('/ai/dataset/update', data);
}

/** 启停数据集（停用后不能新建/验证/发布版本） */
export async function updateDatasetStatus(
  id: number,
  version: number,
  enabled: boolean,
) {
  return await requestClient.put<boolean>('/ai/dataset/update-status', null, {
    params: { enabled, id, version },
  });
}

/** 删除数据集（被报表引用时拒绝） */
export async function deleteDataset(id: number, version: number) {
  return await requestClient.delete<boolean>('/ai/dataset/delete', {
    params: { id, version },
  });
}

/** 创建语义版本草稿（定义是声明式 JSON，不是 SQL） */
export async function createDatasetVersion(
  datasetId: number,
  definitionJson: string,
) {
  return await requestClient.post<number>('/ai/dataset/version/create', {
    datasetId,
    definitionJson,
  });
}

/** 验证版本（与上游结构比对；漂移则置待验证并给出原因） */
export async function verifyDatasetVersion(versionId: number, version: number) {
  return await requestClient.post<AiDatasetApi.DatasetVersionVerifyResp>(
    '/ai/dataset/version/verify',
    { version, versionId },
  );
}

/** 发布版本（要求已验证且上游结构自验证以来未变化） */
export async function publishDatasetVersion(
  versionId: number,
  version: number,
) {
  return await requestClient.post<AiDatasetApi.DatasetVersionVerifyResp>(
    '/ai/dataset/version/publish',
    { version, versionId },
  );
}

/** 版本详情（含定义快照） */
export async function getDatasetVersion(versionId: number) {
  return await requestClient.get<AiDatasetApi.DatasetVersion>(
    '/ai/dataset/version/get',
    { params: { versionId } },
  );
}

/** 版本分页 */
export async function getDatasetVersionPage(
  datasetId: number,
  params: PageParam,
) {
  return await requestClient.get<PageResult<AiDatasetApi.DatasetVersion>>(
    '/ai/dataset/version/page',
    { params: { datasetId, ...params } },
  );
}

export namespace AiToolApi {
  /** 工具（身份 + 来源连接器） */
  export interface Tool {
    id: number;
    code: string;
    name: string;
    description?: string;
    connectorId: number;
    status: string;
    latestVersionNo?: number;
    version: number;
    createTime?: Date;
  }

  /** 新增/修改工具请求 */
  export interface ToolSaveReq {
    id?: number;
    code: string;
    name: string;
    description?: string;
    connectorId: number;
    version?: number;
  }

  /** 工具版本（政策与输入输出 schema 的不可变快照） */
  export interface ToolVersion {
    id: number;
    toolId: number;
    versionNo: number;
    status: string;
    toolType: string;
    policy: string;
    sourceKind: string;
    sourceRef: string;
    inputSchemaJson: string;
    outputSchemaJson: string;
    schemaHash: string;
    publishedAt?: Date;
    version: number;
  }
}

/** 工具分页 */
export async function getToolPage(
  params: PageParam & { connectorId?: number; status?: string },
) {
  return await requestClient.get<PageResult<AiToolApi.Tool>>('/ai/tool/page', {
    params,
  });
}

/** 工具详情 */
export async function getTool(id: number) {
  return await requestClient.get<AiToolApi.Tool>('/ai/tool/get', {
    params: { id },
  });
}

/** 新增工具 */
export async function createTool(data: AiToolApi.ToolSaveReq) {
  return await requestClient.post<number>('/ai/tool/create', data);
}

/** 修改工具 */
export async function updateTool(data: AiToolApi.ToolSaveReq) {
  return await requestClient.put<boolean>('/ai/tool/update', data);
}

/** 启停工具（停用后不可执行） */
export async function updateToolStatus(
  id: number,
  version: number,
  enabled: boolean,
) {
  return await requestClient.put<boolean>('/ai/tool/update-status', null, {
    params: { enabled, id, version },
  });
}

/** 删除工具（被引用时拒绝） */
export async function deleteTool(id: number, version: number) {
  return await requestClient.delete<boolean>('/ai/tool/delete', {
    params: { id, version },
  });
}

/** 创建工具版本草稿（政策缺省 DENY） */
export async function createToolVersion(data: {
  inputSchemaJson: string;
  outputSchemaJson: string;
  policy: string;
  sourceKind: string;
  sourceRef: string;
  toolId: number;
  toolType: string;
}) {
  return await requestClient.post<number>('/ai/tool/version/create', data);
}

/** 发布工具版本（首期只允许读工具；来源 operation 必须已发布） */
export async function publishToolVersion(versionId: number, version: number) {
  return await requestClient.post<boolean>('/ai/tool/version/publish', {
    version,
    versionId,
  });
}

/** 工具版本详情 */
export async function getToolVersion(versionId: number) {
  return await requestClient.get<AiToolApi.ToolVersion>(
    '/ai/tool/version/get',
    { params: { versionId } },
  );
}

/** 工具版本分页 */
export async function getToolVersionPage(toolId: number, params: PageParam) {
  return await requestClient.get<PageResult<AiToolApi.ToolVersion>>(
    '/ai/tool/version/page',
    { params: { toolId, ...params } },
  );
}

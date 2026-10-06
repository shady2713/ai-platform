import type { PageParam, PageResult } from '@vben/request';

import { requestClient } from '#/api/request';

/**
 * AI 流程编排域 API（X08）：流程定义 / 不可变图版本 / 受控运行。
 *
 * 字段与三个控制器（AiWorkflowController / AiWorkflowVersionController /
 * AiWorkflowRunController）的 VO 一一对应，前端不猜字段。后端 VO 只属于协议层，
 * 图 JSON 的形状与拓扑判定都在服务端，这里**不复制**任何校验逻辑。
 *
 * 注意：这里**没有**任何"执行任意脚本 / 任意 SQL"的入口——受控运行只按已发布版本
 * 的冻结图执行，人工确认类工具节点在流程里受控结束，写副作用只能经既有确认链路。
 */
export namespace AiWorkflowApi {
  /** 流程定义（应用内标识唯一，创建后不可修改） */
  export interface Workflow {
    applicationId: number;
    code: string;
    createTime?: Date;
    description?: string;
    id: number;
    latestVersionNo?: number;
    name: string;
    status: string;
    version: number;
  }

  /** 新增/修改流程定义请求：标识与所属应用创建后不可修改（历史运行按版本编号引用） */
  export interface WorkflowSaveReq {
    applicationId?: number;
    code?: string;
    description?: string;
    id?: number;
    name: string;
    version?: number;
  }

  /** 启停流程定义请求：停用后不受理新运行 */
  export interface WorkflowStatusReq {
    enabled: boolean;
    id: number;
    version: number;
  }

  /** 流程定义分页查询（标识/名称模糊，状态精确） */
  export type WorkflowPageReq = PageParam & {
    applicationId?: number;
    code?: string;
    status?: string;
  };
}

/** 流程定义分页 */
export async function getWorkflowPage(params: AiWorkflowApi.WorkflowPageReq) {
  return await requestClient.get<PageResult<AiWorkflowApi.Workflow>>(
    '/ai/workflow/page',
    { params },
  );
}

/** 流程定义详情 */
export async function getWorkflow(id: number) {
  return await requestClient.get<AiWorkflowApi.Workflow>('/ai/workflow/get', {
    params: { id },
  });
}

/** 新增流程定义（标识在应用内唯一） */
export async function createWorkflow(data: AiWorkflowApi.WorkflowSaveReq) {
  return await requestClient.post<number>('/ai/workflow/create', data);
}

/** 修改流程定义（标识与所属应用不可改） */
export async function updateWorkflow(data: AiWorkflowApi.WorkflowSaveReq) {
  return await requestClient.put<boolean>('/ai/workflow/update', data);
}

/** 启用/停用流程定义（后端用 @RequestBody 收 {id, enabled, version}） */
export async function updateWorkflowStatus(
  data: AiWorkflowApi.WorkflowStatusReq,
) {
  return await requestClient.put<boolean>('/ai/workflow/update-status', data);
}

/** 删除流程定义（软删除；后端用 @RequestParam 收 id 与乐观锁版本） */
export async function deleteWorkflow(id: number, version: number) {
  return await requestClient.delete<boolean>('/ai/workflow/delete', {
    params: { id, version },
  });
}

export namespace AiWorkflowVersionApi {
  /** 流程版本：不可变快照，只有 DRAFT 可编辑 */
  export interface WorkflowVersion {
    createTime?: Date;
    edgeCount?: number;
    graphHash?: string;
    /** 受控图 JSON（声明式 nodes/edges，不是脚本） */
    graphJson?: string;
    id: number;
    nodeCount?: number;
    /**
     * 发布时间。
     *
     * <p>这里刻意声明成 `string` 而不是沿用 `createTime?: Date` 的惯例：
     * 请求层**不做日期归一化**，运行时拿到的就是后端 `LocalDateTime` 序列化出的字符串；
     * 而本字段是版本列表里唯一被**原样渲染**的时间（`{{ version.publishedAt }}`），
     * 声明成 `Date` 会让类型与渲染结果对不上——声明成 `Date` 的 `createTime` 之所以
     * 一直没人发现，正是因为没有任何页面直接渲染它。
     */
    publishedAt?: string;
    status: string;
    version: number;
    versionNo: number;
    workflowId: number;
  }

  /** 新建草稿请求 */
  export interface DraftCreateReq {
    graphJson: string;
    workflowId: number;
  }

  /** 编辑草稿图请求（已发布版本传到这里会被服务端直接拒绝） */
  export interface DraftUpdateReq {
    graphJson: string;
    version: number;
    versionId: number;
    workflowId: number;
  }

  /** 发布/废弃请求（只有草稿可被这两个命令作用） */
  export interface VersionActionReq {
    id: number;
    version: number;
  }

  /** 版本分页查询 */
  export type VersionPageReq = PageParam & {
    status?: string;
    workflowId?: number;
  };
}

/** 新建草稿版本（同一流程同时最多一个打开的草稿） */
export async function createWorkflowDraft(
  data: AiWorkflowVersionApi.DraftCreateReq,
) {
  return await requestClient.post<number>(
    '/ai/workflow-version/create-draft',
    data,
  );
}

/** 编辑草稿图（只有 DRAFT 可编辑） */
export async function updateWorkflowDraft(
  data: AiWorkflowVersionApi.DraftUpdateReq,
) {
  return await requestClient.put<boolean>(
    '/ai/workflow-version/update-draft',
    data,
  );
}

/** 发布版本（环/无出口/类型不匹配/引用不存在一律由服务端拒绝） */
export async function publishWorkflowVersion(
  data: AiWorkflowVersionApi.VersionActionReq,
) {
  return await requestClient.put<number>('/ai/workflow-version/publish', data);
}

/** 废弃草稿（终态，释放"单开草稿"名额） */
export async function discardWorkflowDraft(
  data: AiWorkflowVersionApi.VersionActionReq,
) {
  return await requestClient.put<boolean>('/ai/workflow-version/discard', data);
}

/** 版本详情（含图 JSON） */
export async function getWorkflowVersion(id: number) {
  return await requestClient.get<AiWorkflowVersionApi.WorkflowVersion>(
    '/ai/workflow-version/get',
    { params: { id } },
  );
}

/** 当前打开的草稿（没有则服务端返回 null） */
export async function getOpenWorkflowDraft(workflowId: number) {
  return await requestClient.get<AiWorkflowVersionApi.WorkflowVersion | null>(
    '/ai/workflow-version/open-draft',
    { params: { workflowId } },
  );
}

/** 版本分页 */
export async function getWorkflowVersionPage(
  params: AiWorkflowVersionApi.VersionPageReq,
) {
  return await requestClient.get<
    PageResult<AiWorkflowVersionApi.WorkflowVersion>
  >('/ai/workflow-version/page', { params });
}

export namespace AiWorkflowRunApi {
  /** 节点留痕（按执行顺序；失败定位与步骤可视化的数据源） */
  export interface RunNode {
    durationMs?: number;
    errorCode?: string;
    nodeKey: string;
    nodeType: string;
    outputText?: string;
    status: string;
  }

  /** 运行记录：受理即固定已发布版本并同步执行 */
  export interface Run {
    currentNodeKey?: string;
    dataLevel?: string;
    durationMs?: number;
    errorCode?: string;
    finishedTime?: Date;
    id: number;
    idempotencyKey?: string;
    nodeExecuted?: number;
    nodeTotal?: number;
    /** 仅受理响应带节点事实；运行查询接口不含 */
    nodes?: RunNode[];
    outputText?: string;
    startedTime?: Date;
    status: string;
    workflowId: number;
    workflowVersionId?: number;
  }

  /** 受理运行请求：幂等键在流程内唯一，重复受理返回首次运行 */
  export interface RunAcceptReq {
    dataLevel: string;
    idempotencyKey: string;
    inputText?: string;
    /** 步数预算：只能比平台默认更紧 */
    maxSteps?: number;
    /** 耗时预算：平台封顶 120000 毫秒 */
    maxDurationMillis?: number;
    workflowId: number;
  }

  /** 运行分页查询 */
  export type RunPageReq = PageParam & {
    status?: string;
    workflowId?: number;
  };
}

/**
 * 受理并执行流程运行。
 *
 * 同步执行、预算有界，响应直接带终态与逐节点事实。响应里可能出现
 * `AI_TOOL_CONFIRMATION_REQUIRED` 这类稳定错误码：需要人工确认的工具节点在流程里
 * **不会**被执行，这是受控结束而不是失败，前端原样透出。
 */
export async function acceptWorkflowRun(data: AiWorkflowRunApi.RunAcceptReq) {
  return await requestClient.post<AiWorkflowRunApi.Run>(
    '/ai/workflow-run/accept',
    data,
  );
}

/** 运行详情（幂等键与时间戳以这个接口为准） */
export async function getWorkflowRun(id: number) {
  return await requestClient.get<AiWorkflowRunApi.Run>('/ai/workflow-run/get', {
    params: { id },
  });
}

/** 运行分页 */
export async function getWorkflowRunPage(params: AiWorkflowRunApi.RunPageReq) {
  return await requestClient.get<PageResult<AiWorkflowRunApi.Run>>(
    '/ai/workflow-run/page',
    { params },
  );
}

/** 节点留痕（按执行顺序） */
export async function getWorkflowRunNodeList(runId: number) {
  return await requestClient.get<AiWorkflowRunApi.RunNode[]>(
    '/ai/workflow-run/node-list',
    { params: { runId } },
  );
}

import type { PageParam, PageResult } from '@vben/request';

import { requestClient } from '#/api/request';

/**
 * 运行监控 API（Q03）：管理端通道（`/admin-api`），权限码与 V82 菜单种子一致。
 *
 * 契约要点：只回**标识与计量元数据**——主体只有类型（不含外部用户标识明文）、模型端点只有编号
 * 与配置修订、事件块只给类型与"有没有正文"（AT-011：后台普通管理员看不到秘密与正文）。
 */
export namespace AiObservabilityApi {
  /** 列表行 */
  export interface RunRow {
    applicationId: number;
    attemptCount?: number;
    createTime?: Date;
    lastErrorCode?: string;
    latestSeq?: number;
    releaseId: number;
    retryBlockedReason?: string;
    retryable: boolean;
    runId: number;
    runKey: string;
    serviceId: number;
    status: string;
    stepCount?: number;
    subjectType: string;
    taskStatus?: string;
    updateTime?: Date;
  }

  /** 耗时分解：未计量的阶段在 unmeasuredStages 里列出，字段为空而不是 0 */
  export interface RunTiming {
    businessApiDurationMs?: number;
    modelDurationMs?: number;
    modelInvocationCount?: number;
    retrievalDurationMs?: number;
    totalDurationMs?: number;
    unmeasuredStages?: string[];
    unknownUsageCount?: number;
  }

  /** 详情 */
  export interface RunDetail extends RunRow {
    contentHash?: string;
    conversationId?: number;
    dataLevel?: string;
    endpointConfigRevision?: number;
    modelEndpointId?: number;
    nextAttemptTime?: Date;
    resultDigest?: string;
    resultMessageId?: number;
    runVersion: number;
    taskId?: number;
    taskKind?: string;
    timing?: RunTiming;
  }

  /** 分页查询参数（筛选在服务端执行） */
  export interface RunPageParams extends PageParam {
    applicationId?: number;
    from?: string;
    serviceId?: number;
    status?: string;
    subjectType?: string;
    to?: string;
  }

  /** 人工重试请求（version 是运行行乐观锁版本，取自详情） */
  export interface RunRetryReq {
    runId: number;
    version: number;
  }

  /** 事件时间线（不含事件块正文） */
  export interface TimelineEvent {
    blockPresent: boolean;
    blockType?: string;
    createTime?: Date;
    schemaVersion?: string;
    seq: number;
    status: string;
  }
}

/** 运行监控分页 */
export function getRunPage(
  params: AiObservabilityApi.RunPageParams,
): Promise<PageResult<AiObservabilityApi.RunRow>> {
  return requestClient.get<PageResult<AiObservabilityApi.RunRow>>(
    '/ai/observability/run/page',
    { params },
  );
}

/** 运行详情 */
export function getRunDetail(
  runId: number,
): Promise<AiObservabilityApi.RunDetail> {
  return requestClient.get<AiObservabilityApi.RunDetail>(
    '/ai/observability/run/get',
    { params: { runId } },
  );
}

/** 运行事件时间线（有界，按序号升序） */
export function getRunTimeline(params: {
  afterSeq?: number;
  limit?: number;
  runId: number;
}): Promise<AiObservabilityApi.TimelineEvent[]> {
  return requestClient.get<AiObservabilityApi.TimelineEvent[]>(
    '/ai/observability/run/timeline',
    { params },
  );
}

/** 人工重试（只允许原任务可重试类型；服务端仍会校验权限与可重试性） */
export function retryRun(
  data: AiObservabilityApi.RunRetryReq,
): Promise<boolean> {
  return requestClient.post<boolean>('/ai/observability/run/retry', data);
}

export type { PageParam };

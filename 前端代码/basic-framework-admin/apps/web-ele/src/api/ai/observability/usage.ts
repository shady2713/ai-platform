import type { PageParam, PageResult } from '@vben/request';

import { requestClient } from '#/api/request';

/**
 * 用量与限额 API（Q02 的 `/ai/usage/**`，页面由 Q03 交付）。
 *
 * token 未知时字段为空：界面必须显示"未知/估算"而不是 0（AT-060）。
 */
export namespace AiUsageApi {
  /** 用量账本行 */
  export interface UsageRow {
    applicationId: number;
    durationMs?: number;
    endpointRef?: string;
    inputTokens?: number;
    invocationId: string;
    modelRef?: string;
    modelRevision?: number;
    occurredAt?: Date;
    outputTokens?: number;
    runId?: number;
    serviceId?: number;
    status?: string;
    taskId?: number;
    usageSource: string;
  }

  /** 按计量来源聚合的一行 */
  export interface SourceSummaryRow {
    inputTokens: number;
    invocationCount: number;
    outputTokens: number;
    usageSource: string;
  }

  /** 按计量来源聚合（unknownInvocations 是"来源未知"的条数） */
  export interface SourceSummary {
    sources: SourceSummaryRow[];
    unknownInvocations: number;
  }

  /** 按服务聚合的一行 */
  export interface ServiceSummaryRow {
    inputTokens: number;
    invocationCount: number;
    outputTokens: number;
    serviceId: number;
  }

  /** 用量分页参数（服务端按应用/服务/时间窗过滤） */
  export interface UsagePageParams extends PageParam {
    applicationId?: number;
    from?: string;
    serviceId?: number;
    to?: string;
  }
}

/** 用量账本分页 */
export function getUsagePage(
  params: AiUsageApi.UsagePageParams,
): Promise<PageResult<AiUsageApi.UsageRow>> {
  return requestClient.get<PageResult<AiUsageApi.UsageRow>>('/ai/usage/page', {
    params,
  });
}

/** 按计量来源聚合（含"来源未知"条数） */
export function getUsageSummary(params: {
  applicationId: number;
  from: string;
  to: string;
}): Promise<AiUsageApi.SourceSummary> {
  return requestClient.get<AiUsageApi.SourceSummary>('/ai/usage/summary', {
    params,
  });
}

/** 按服务聚合 */
export function getUsageServiceSummary(params: {
  applicationId: number;
  from: string;
  to: string;
}): Promise<AiUsageApi.ServiceSummaryRow[]> {
  return requestClient.get<AiUsageApi.ServiceSummaryRow[]>(
    '/ai/usage/service-summary',
    { params },
  );
}

/** 当前有效配额占位数（未释放且未到期） */
export function getQuotaActive(applicationId: number): Promise<number> {
  return requestClient.get<number>('/ai/usage/quota-active', {
    params: { applicationId },
  });
}

export type { PageParam };

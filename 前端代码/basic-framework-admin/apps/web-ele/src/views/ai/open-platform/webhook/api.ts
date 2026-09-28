import type { PageParam, PageResult } from '@vben/request';

import { requestClient } from '#/api/request';

/**
 * Webhook 投递管理 API（X10）：管理端通道（`/admin-api`），权限码与 V88 菜单种子一致。
 *
 * 契约要点：响应只回**状态、计数与稳定原因码**——不含签名密钥（只给 `secretConfigured` 与版本号）、
 * 不含投递正文（只给 `payloadDigest` 摘要）、不含目标密钥与上游响应正文。
 */
export namespace AiWebhookApi {
  /** 投递目标（控制面配置） */
  export interface Target {
    id: number;
    applicationId: number;
    code: string;
    name: string;
    targetUrl: string;
    eventTypes: string[];
    /** 是否已配置签名密钥（平台不返回密钥本身） */
    secretConfigured: boolean;
    secretRevision: number;
    /** ENABLED / DISABLED（停用即停发） */
    status: string;
    maxAttempts: number;
    version: number;
    createTime?: string;
  }

  /** 投递事实行 */
  export interface Delivery {
    id: number;
    deliveryNo: string;
    targetId: number;
    applicationId: number;
    eventType: string;
    resourceType: string;
    resourceId: number;
    resourceKey: string;
    occurredTime?: string;
    payloadDigest: string;
    /** PENDING / RUNNING / SUCCEEDED / FAILED（FAILED = 死信，可人工重投） */
    status: string;
    attemptCount: number;
    maxAttempts: number;
    lastErrorCode?: string;
    failureCode?: string;
    nextAttemptTime?: string;
    firstAttemptTime?: string;
    deliveredTime?: string;
    createTime?: string;
  }

  /** 每一次完成尝试的结论（append-retention 留痕） */
  export interface DeliveryAttempt {
    attemptNo: number;
    outcome: string;
    errorCode?: string;
    httpStatus?: number;
    signatureTimestamp?: number;
    durationMs?: number;
    startedTime?: string;
    finishedTime?: string;
  }

  /** 目标新增/修改入参（密钥只提交不回显；修改时留空表示保留） */
  export interface TargetSavePayload {
    applicationId: number;
    code: string;
    name: string;
    targetUrl: string;
    eventTypes: string[];
    secret?: string;
    maxAttempts: number;
  }

  /** 目标分页查询 */
  export interface TargetPageQuery extends PageParam {
    applicationId?: number;
    code?: string;
    status?: string;
  }

  /** 投递分页查询 */
  export interface DeliveryPageQuery extends PageParam {
    targetId?: number;
    status?: string;
    eventType?: string;
  }
}

/** 目标分页 */
export function getTargetPage(params: AiWebhookApi.TargetPageQuery) {
  return requestClient.get<PageResult<AiWebhookApi.Target>>(
    '/ai/webhook/target/page',
    { params },
  );
}

/** 新增目标（响应只有目标编号；密钥不回显） */
export function createTarget(data: AiWebhookApi.TargetSavePayload) {
  return requestClient.post<number>('/ai/webhook/target/create', data);
}

/** 启用/停用目标（停用即停发） */
export function updateTargetStatus(data: {
  enabled: boolean;
  id: number;
  version: number;
}) {
  return requestClient.put<boolean>('/ai/webhook/target/update-status', data);
}

/** 轮换签名密钥（旧密钥立即作废；响应不含任何密钥材料） */
export function rotateTargetSecret(data: {
  id: number;
  secret: string;
  version: number;
}) {
  return requestClient.put<boolean>('/ai/webhook/target/rotate-secret', data);
}

/** 删除目标（在途投递会在发送前复检失败并按事实收尾） */
export function deleteTarget(id: number, version: number) {
  return requestClient.delete<boolean>('/ai/webhook/target/delete', {
    params: { id, version },
  });
}

/** 投递分页（按状态过滤 FAILED 即死信视图） */
export function getDeliveryPage(params: AiWebhookApi.DeliveryPageQuery) {
  return requestClient.get<PageResult<AiWebhookApi.Delivery>>(
    '/ai/webhook/delivery/page',
    { params },
  );
}

/** 某次投递的尝试留痕 */
export function getDeliveryAttempts(deliveryId: number) {
  return requestClient.get<AiWebhookApi.DeliveryAttempt[]>(
    '/ai/webhook/delivery/attempts',
    { params: { deliveryId } },
  );
}

/** 人工重投死信（目标停用或已删除时被服务端拒绝） */
export function redeliverDelivery(id: number) {
  return requestClient.put<boolean>('/ai/webhook/delivery/redeliver', { id });
}

import type { ResultBlock, Theme } from '@vben/ai-contracts';

/**
 * 桥实例状态（C06）：与设计契约 8.2 的状态机一致。
 *
 * CREATED（已创建，未握手）→ WAITING_READY（已发 HELLO）→ AUTHENTICATING（换票中）→
 * INITIALIZED（可收发业务消息）→ DESTROYED（已销毁，单向终态）。
 */
export type BridgeState =
  | 'AUTHENTICATING'
  | 'CREATED'
  | 'DESTROYED'
  | 'INITIALIZED'
  | 'WAITING_READY';

/** 桥协议里用到的主题类型（与 ai-contracts 的冻结契约同型）。 */
export type { Theme };

/** 运行状态与开放 API 契约 RunStatus 一致（docs/ai-platform/contracts/openapi-core.json）。 */
export type AiRunStatus =
  | 'CANCELLED'
  | 'FAILED'
  | 'QUEUED'
  | 'RUNNING'
  | 'SUCCEEDED'
  | 'WAITING_CONFIRMATION'
  | 'WAITING_INPUT';

export interface CreateRunRequest {
  /** 会话编号（数值编号；缺省表示新建会话） */
  conversationId?: number;
  /** 用户消息，1..16000 字符 */
  message: string;
  /** 服务业务键，形如 svc_xxx */
  serviceId: string;
}

/** 受理结果（与开放 API 的 AiRunAcceptResp 一致）。 */
export interface RunAccepted {
  releaseId?: number;
  releaseVersion?: number;
  /** 是否命中幂等（复用首次受理的运行） */
  reused: boolean;
  runId: number;
  runKey: string;
  status: AiRunStatus;
}

/** 运行快照（与开放 API 的 AiRun 一致；事件流重连/窗口过期时使用）。 */
export interface RunSnapshot {
  blocks?: ResultBlock[];
  id: number;
  runKey?: string;
  status: AiRunStatus;
  version?: number;
}

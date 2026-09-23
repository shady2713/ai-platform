import type { ResultBlock } from '@vben/ai-contracts';

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

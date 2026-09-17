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
  /** 服务业务键，形如 svc_xxx */
  conversationId?: string;
  /** 用户消息，1..16000 字符 */
  message: string;
  /** 服务业务键，形如 svc_xxx */
  serviceId: string;
}

export interface RunAccepted {
  runId: string;
  status: AiRunStatus;
}

export interface RunSnapshot {
  blocks: ResultBlock[];
  runId: string;
  status: AiRunStatus;
}

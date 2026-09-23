/**
 * 会话状态机（C02）：等待 / 执行 / 确认 / 失败 / 完成 五态 + 代次隔离 + 错误只提示一次。
 *
 * <p>为什么单独做一台纯状态机（不写在组件里）：会话状态同时被三件事驱动——用户操作（发送/取消/重试）、
 * 服务端事件（SSE seq）、宿主行为（切换用户/销毁）。把它们收敛到一台**可单测**的机器上，
 * 才能逐条证明：连续点击不重复受理、取消后晚到的完成不覆盖终态、切用户后旧响应被丢弃、
 * 同一个错误只提示一次（AT-016/053）。
 *
 * <p>代次（generation）语义：宿主切换用户或切换会话时代次 +1；任何带旧代次的回调都被丢弃，
 * 不存在"上一个用户的回答出现在新用户界面"的窗口。
 */

/** 会话阶段：等待（可发送）/ 执行（已受理）/ 确认（等人工确认）/ 失败 / 完成。 */
export type ConversationPhase =
  | 'FAILED'
  | 'IDLE'
  | 'RUNNING'
  | 'SUCCEEDED'
  | 'WAITING_CONFIRMATION';

/** 服务端运行状态 → 会话阶段（未知状态不猜，保持执行中）。 */
export function phaseOfRunStatus(status: string): ConversationPhase {
  switch (status) {
    case 'CANCELLED':
    case 'FAILED': {
      return 'FAILED';
    }
    case 'SUCCEEDED': {
      return 'SUCCEEDED';
    }
    case 'WAITING_CONFIRMATION': {
      return 'WAITING_CONFIRMATION';
    }
    default: {
      return 'RUNNING';
    }
  }
}

/** 状态快照（只读）。 */
export interface ConversationSnapshot {
  /** 当前错误键（同一错误只提示一次） */
  errorKey: null | string;
  /** 代次（切换用户/会话时 +1） */
  generation: number;
  /** 最近一次幂等键（重试复用同一键） */
  idempotencyKey: null | string;
  /** 已确认的最大事件序号 */
  lastSeq: number;
  /** 当前阶段 */
  phase: ConversationPhase;
  /** 当前运行业务键（未受理时为空） */
  runKey: null | string;
}

export interface ConversationMachine {
  /** 受理成功：进入执行态（旧代次丢弃）。 */
  acceptRun(runKey: string, generation: number): boolean;
  /** 应用服务端事件：按代次与 seq 过滤（返回是否被采纳）。 */
  applyEvent(
    event: { seq: number; status: string },
    generation: number,
  ): boolean;
  /** 开始一次发送：执行中/确认中拒绝重复受理（连续点击不重复 run）。 */
  beginSend(generation: number, idempotencyKey: string): boolean;
  /** 取消：只有执行中/确认中可取消；返回是否需要调用服务端取消。 */
  cancel(generation: number): boolean;
  /** 记录失败：同一错误键只提示一次（返回是否应该提示）。 */
  fail(errorKey: string, generation: number): boolean;
  /** 重试：失败态回到等待态，并复用同一幂等键。 */
  retry(generation: number): {
    accepted: boolean;
    idempotencyKey: null | string;
  };
  /** 快照 */
  snapshot(): ConversationSnapshot;
  /** 切换用户/会话：代次 +1，回到等待态，旧回调一律丢弃。 */
  switchGeneration(): number;
}

/** 创建会话状态机（初始代次可指定，便于"会话内续接"）。 */
export function createConversationMachine(
  initialGeneration = 0,
): ConversationMachine {
  let phase: ConversationPhase = 'IDLE';
  let generation = initialGeneration;
  let runKey: null | string = null;
  let lastSeq = 0;
  let idempotencyKey: null | string = null;
  let errorKey: null | string = null;

  const stale = (incoming: number) => incoming !== generation;

  return {
    acceptRun(incomingRunKey: string, incomingGeneration: number): boolean {
      if (stale(incomingGeneration)) {
        return false;
      }
      runKey = incomingRunKey;
      phase = 'RUNNING';
      errorKey = null;
      lastSeq = 0;
      return true;
    },

    applyEvent(event, incomingGeneration): boolean {
      if (stale(incomingGeneration)) {
        // 切用户/切会话后晚到的事件：丢弃（不覆盖新会话的界面）
        return false;
      }
      if (event.seq <= lastSeq) {
        return false;
      }
      lastSeq = event.seq;
      phase = phaseOfRunStatus(event.status);
      if (phase !== 'RUNNING') {
        idempotencyKey = null;
      }
      return true;
    },

    beginSend(incomingGeneration, key): boolean {
      if (stale(incomingGeneration)) {
        return false;
      }
      if (phase === 'RUNNING' || phase === 'WAITING_CONFIRMATION') {
        // 连续点击：执行中（含受理在途）不接受第二次受理（同一运行只受理一次）
        return false;
      }
      idempotencyKey = key;
      // 受理在途也按"执行中"处理：请求返回前的重复点击一律拒绝，避免产生第二个运行
      phase = 'RUNNING';
      return true;
    },

    cancel(incomingGeneration): boolean {
      if (stale(incomingGeneration)) {
        return false;
      }
      if (phase !== 'RUNNING' && phase !== 'WAITING_CONFIRMATION') {
        return false;
      }
      phase = 'FAILED';
      errorKey = null;
      return true;
    },

    fail(incomingErrorKey, incomingGeneration): boolean {
      if (stale(incomingGeneration)) {
        return false;
      }
      phase = 'FAILED';
      if (errorKey === incomingErrorKey) {
        // 同一个错误只提示一次
        return false;
      }
      errorKey = incomingErrorKey;
      return true;
    },

    retry(incomingGeneration) {
      if (stale(incomingGeneration)) {
        return { accepted: false, idempotencyKey: null };
      }
      if (phase !== 'FAILED') {
        return { accepted: false, idempotencyKey: null };
      }
      phase = 'IDLE';
      // 保留 errorKey：重试后若仍是同一个错误，不再重复提示（错误只提示一次）
      return { accepted: true, idempotencyKey };
    },

    snapshot(): ConversationSnapshot {
      return { errorKey, generation, idempotencyKey, lastSeq, phase, runKey };
    },

    switchGeneration(): number {
      generation += 1;
      phase = 'IDLE';
      runKey = null;
      lastSeq = 0;
      idempotencyKey = null;
      errorKey = null;
      return generation;
    },
  };
}

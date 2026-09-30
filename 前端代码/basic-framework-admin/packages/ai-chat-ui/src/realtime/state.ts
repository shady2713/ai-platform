/**
 * 实时语音会话状态机（X05）：加载 / 空 / 会话中 / 失败 / 失权 / 销毁六态 + 本地回合栅栏。
 *
 * <p>为什么单独做一台可单测的状态机：实时会话同时被三件事驱动——用户操作（开始/打断/关麦/关闭）、
 * 服务端事件（转写、工具状态、背压占用）与宿主行为（切换用户/卸载组件）。把它们收敛到一台纯
 * 状态机上，才能逐条证明：加载/空/失败/失权/销毁五态渲染正确、打断后旧回合的晚到响应被丢弃、
 * 背压占用有明确提示（不在客户端静默丢帧）、销毁后任何晚到的响应都不写状态。
 *
 * <p>三条纪律（与服务端语义对齐，客户端不做"更宽松"的解释）：
 * 1. **回合栅栏**：打断先本地推进回合；任何带旧回合号的响应（例如打断前发出的推流请求的响应）
 *    一律丢弃，旧音频不会因为"响应晚到"而继续进入界面；
 * 2. **不猜可用**：会话的协议与音频格式由受理请求显式给出，客户端不根据"另一个端点能用"推断；
 *    受理被拒绝时按服务端稳定码进入失败或失权态，绝不自动改用其它协议/端点重试；
 * 3. **不静默丢弃**：客户端不做音频降采样或丢帧补偿；背压占用由服务端事实驱动，界面只提示，
 *    真正的"超限结束"由服务端按稳定原因执行。
 */

/** 状态机阶段：加载中 / 空（未开始或已结束）/ 会话中 / 失败 / 失权 / 已销毁。 */
export type RealtimePhase =
  | 'ACTIVE'
  | 'DENIED'
  | 'DESTROYED'
  | 'EMPTY'
  | 'FAILED'
  | 'LOADING';

/** 输入缓冲压力：占用达到高水位即提示（提示不等于丢弃）。 */
export type RealtimePressure = 'HIGH' | 'NORMAL';

/** 服务端会话事件（与开放 API 的事件形状一致）。 */
export interface RealtimeEventView {
  byteCount: number;
  createTime: string;
  detailCode: string;
  seq: number;
  text: string;
  turnNo: number;
  type: string;
}

/** 会话内工具调用状态。 */
export interface RealtimeToolCallView {
  callId: string;
  id: number;
  resultCode: null | string;
  status: string;
  toolCode: string;
  turnNo: number;
}

/** 会话视图（服务端返回的统一形状；票据只在受理/续票响应出现一次）。 */
export interface RealtimeSessionView {
  audioFormat: string;
  closeReason: null | string;
  droppedStaleFrames: number;
  endpointId: number;
  events: RealtimeEventView[];
  expiresTime: null | string;
  id: number;
  inputBufferedBytes: number;
  inputCapacityBytes: number;
  muted: boolean;
  protocol: string;
  resumeAttempts: number;
  sessionKey: string;
  status: string;
  ticket: null | string;
  ticketExpiresTime: null | string;
  toolCalls: RealtimeToolCallView[];
  turnNo: number;
}

/** 转写行（由事件派生；`final=false` 是中间结果）。 */
export interface RealtimeTranscriptLine {
  final: boolean;
  seq: number;
  text: string;
  turnNo: number;
}

/** 受理输入（端点/协议/音频格式由调用方显式给出）。 */
export interface RealtimeAcceptInput {
  audioFormat: string;
  endpointId: number;
  protocol: string;
  requestKey: string;
  sessionSeconds?: number;
}

/** 宿主注入的受控端口（真实实现是带票据/登录会话的请求）。 */
export interface RealtimeApi {
  accept(input: RealtimeAcceptInput): Promise<RealtimeSessionView>;
  close(sessionId: number): Promise<RealtimeSessionView>;
  detach(sessionId: number): Promise<RealtimeSessionView>;
  executeToolCall(
    sessionId: number,
    toolCallId: number,
  ): Promise<RealtimeSessionView>;
  getSession(sessionId: number): Promise<RealtimeSessionView>;
  interrupt(sessionId: number, turnNo: number): Promise<RealtimeSessionView>;
  mute(sessionId: number, muted: boolean): Promise<RealtimeSessionView>;
  pushAudio(input: {
    frameSeq: number;
    payload: string;
    sessionId: number;
    turnNo: number;
  }): Promise<RealtimeSessionView>;
  renewTicket(sessionId: number, ticket: string): Promise<RealtimeSessionView>;
  resume(sessionId: number, ticket: string): Promise<RealtimeSessionView>;
}

/** 只读快照。 */
export interface RealtimeSnapshot {
  /** 稳定错误键（同一错误只提示一次由宿主决定；这里只给键与文本） */
  errorKey: null | string;
  /** 代次：销毁/切换用户 +1，旧代次响应一律丢弃 */
  generation: number;
  /** 本地因回合过期丢弃的响应数（与服务端计数相加展示） */
  localDroppedFrames: number;
  /** 阶段 */
  phase: RealtimePhase;
  /** 输入缓冲压力 */
  pressure: RealtimePressure;
  /** 会话视图（未受理或销毁后为空） */
  session: null | RealtimeSessionView;
  /** 转写（升序；中间结果与定稿都在） */
  transcript: RealtimeTranscriptLine[];
  /** 本地当前回合（打断先本地推进） */
  turnNo: number;
}

/** 状态机。 */
export interface RealtimeMachine {
  /** 受理会话（显式端点/协议/音频格式；失败按稳定码进入失败/失权态）。 */
  accept(input: RealtimeAcceptInput): Promise<boolean>;
  /** 关闭会话（幂等；成功后进入空态但保留转写）。 */
  close(): Promise<boolean>;
  /** 销毁（切换用户/卸载时调用）：之后任何晚到响应都不写状态。 */
  destroy(): void;
  /** 标记断线（有界重连窗口由服务端计时）。 */
  detach(): Promise<boolean>;
  /** 执行会话内工具调用（幂等：已终态返回既有结论）。 */
  executeToolCall(toolCallId: number): Promise<boolean>;
  /** 打断（先本地推进回合，旧回合的晚到响应被丢弃）。 */
  interrupt(): Promise<boolean>;
  /** 关麦/开麦。 */
  mute(muted: boolean): Promise<boolean>;
  /** 推送一帧音频（回合取本地当前回合）。 */
  pushFrame(payload: string, frameSeq: number): Promise<boolean>;
  /** 刷新会话事实。 */
  refresh(): Promise<boolean>;
  /** 续票（未过期的当前票据）。 */
  renewTicket(ticket: string): Promise<boolean>;
  /** 重连（必须出示当前票据）。 */
  resume(ticket: string): Promise<boolean>;
  /** 只读快照。 */
  snapshot(): RealtimeSnapshot;
  /** 订阅快照变化（组件挂载时订阅、卸载时取消）。 */
  subscribe(listener: (snapshot: RealtimeSnapshot) => void): () => void;
}

/** 平台稳定错误码（与服务端 `AiErrorCodeConstants` 一致；只登记界面需要区分的那几个）。 */
export const REALTIME_ERROR_CODES = {
  ACCESS_DENIED: 1_003_001_004,
  AUTHORIZATION_DENIED: 1_003_003_009,
  SESSION_NOT_EXISTS: 1_003_014_000,
  SESSION_LIMIT_EXCEEDED: 1_003_014_003,
  PROTOCOL_UNVERIFIED: 1_003_014_004,
  ADAPTER_UNAVAILABLE: 1_003_014_005,
  BACKPRESSURE: 1_003_014_008,
  TICKET_INVALID: 1_003_014_013,
} as const;

/** 输入缓冲高水位（占用达到 75% 即提示，服务端仍按 100% 结束会话）。 */
const HIGH_WATERMARK = 0.75;

/** 从事件列表派生转写（按回合 + 序号升序；同一位置重复事件只保留一条）。 */
export function transcriptOf(
  events: RealtimeEventView[],
): RealtimeTranscriptLine[] {
  const seen = new Set<string>();
  const lines: RealtimeTranscriptLine[] = [];
  for (const event of events ?? []) {
    if (event.type !== 'TRANSCRIPT' || !event.text) {
      continue;
    }
    const key = `${event.turnNo}:${event.seq}:${event.detailCode}`;
    if (seen.has(key)) {
      continue;
    }
    seen.add(key);
    lines.push({
      final: event.detailCode === 'FINAL',
      seq: event.seq,
      text: event.text,
      turnNo: event.turnNo,
    });
  }
  return lines;
}

/** 压力判定：容量非法或为 0 时按正常处理（不造出"100% 压力"的假象）。 */
export function pressureOf(
  buffered: number,
  capacity: number,
): RealtimePressure {
  if (
    !Number.isFinite(capacity) ||
    capacity <= 0 ||
    !Number.isFinite(buffered)
  ) {
    return 'NORMAL';
  }
  return buffered / capacity >= HIGH_WATERMARK ? 'HIGH' : 'NORMAL';
}

/** 失败键（稳定；同一键只提示一次由宿主决定）。 */
export function realtimeErrorKey(error: unknown): string {
  const code = codeOf(error);
  return code === null ? 'realtime-error-unknown' : `realtime-error-${code}`;
}

/** 失败提示文本（只覆盖界面需要区分的稳定码，其余给通用文案）。 */
export function realtimeErrorMessage(error: unknown): string {
  switch (codeOf(error)) {
    case REALTIME_ERROR_CODES.ADAPTER_UNAVAILABLE: {
      return '平台未注册该协议的实时适配器，会话未建立';
    }
    case REALTIME_ERROR_CODES.BACKPRESSURE: {
      return '输入音频超过会话缓冲上限，会话已按平台规则结束（未静默丢帧）';
    }
    case REALTIME_ERROR_CODES.PROTOCOL_UNVERIFIED: {
      return '该端点与协议未通过平台的实时能力验证，会话未建立';
    }
    case REALTIME_ERROR_CODES.SESSION_LIMIT_EXCEEDED: {
      return '并发实时会话数已达上限，请稍后重试或结束其它会话';
    }
    case REALTIME_ERROR_CODES.SESSION_NOT_EXISTS: {
      return '会话不存在或不属于当前用户';
    }
    case REALTIME_ERROR_CODES.TICKET_INVALID: {
      return '会话票据无效或已过期，请重新建立会话';
    }
    default: {
      return '实时会话操作失败，请稍后重试';
    }
  }
}

/** 失权判定：只认"明确无权"的稳定码，不把普通失败当成失权。 */
export function isDenied(error: unknown): boolean {
  const code = codeOf(error);
  if (
    code === REALTIME_ERROR_CODES.ACCESS_DENIED ||
    code === REALTIME_ERROR_CODES.AUTHORIZATION_DENIED
  ) {
    return true;
  }
  const status = statusOf(error);
  return status === 401 || status === 403;
}

/** 创建状态机（端口由宿主注入；本文件不依赖浏览器 API，可在 happy-dom 下逐条验证）。 */
export function createRealtimeMachine(api: RealtimeApi): RealtimeMachine {
  let generation = 0;
  let localTurn = 0;
  let localDroppedFrames = 0;
  let phase: RealtimePhase = 'EMPTY';
  let session: null | RealtimeSessionView = null;
  let transcript: RealtimeTranscriptLine[] = [];
  let errorKey: null | string = null;
  let pressure: RealtimePressure = 'NORMAL';
  const listeners = new Set<(snapshot: RealtimeSnapshot) => void>();

  const snapshot = (): RealtimeSnapshot => ({
    errorKey,
    generation,
    localDroppedFrames,
    phase,
    pressure,
    session,
    transcript,
    turnNo: localTurn,
  });

  const emit = (): void => {
    const current = snapshot();
    for (const listener of listeners) {
      listener(current);
    }
  };

  const reset = (): void => {
    session = null;
    transcript = [];
    errorKey = null;
    pressure = 'NORMAL';
    localTurn = 0;
    localDroppedFrames = 0;
  };

  /**
   * 采纳服务端视图。
   *
   * <p>旧代次（销毁/切用户后）一律丢弃。回合栅栏只作用于**媒体面响应**（推流/打断/事件同步）：
   * 生命周期响应（查询/断线/重连/关麦/关闭/工具执行）可能携带"打断之前"的回合号，
   * 对它们套用栅栏会把"会话已关闭"这类终态事实一起丢掉——那是界面必须看到的。
   */
  const applyView = (
    view: RealtimeSessionView,
    startedAt: number,
    fenceTurn: boolean,
  ): boolean => {
    if (startedAt !== generation) {
      return false;
    }
    if (fenceTurn && view.turnNo < localTurn) {
      localDroppedFrames += 1;
      emit();
      return false;
    }
    session = view;
    localTurn = Math.max(localTurn, view.turnNo);
    transcript = transcriptOf(view.events);
    pressure = pressureOf(view.inputBufferedBytes, view.inputCapacityBytes);
    errorKey = null;
    phase = view.status === 'CLOSED' ? 'EMPTY' : 'ACTIVE';
    emit();
    return true;
  };

  const fail = (error: unknown, startedAt: number): false => {
    if (startedAt !== generation) {
      return false;
    }
    errorKey = realtimeErrorKey(error);
    phase = isDenied(error) ? 'DENIED' : 'FAILED';
    emit();
    return false;
  };

  const withSession = async (
    action: (
      sessionId: number,
      startedAt: number,
    ) => Promise<RealtimeSessionView>,
  ): Promise<boolean> => {
    if (!session || phase === 'DESTROYED') {
      return false;
    }
    const startedAt = generation;
    try {
      // 生命周期响应不套用回合栅栏：终态事实（已关闭/已断开）必须能进入界面
      return applyView(await action(session.id, startedAt), startedAt, false);
    } catch (error) {
      return fail(error, startedAt);
    }
  };

  return {
    async accept(input: RealtimeAcceptInput): Promise<boolean> {
      if (phase === 'DESTROYED') {
        return false;
      }
      const startedAt = generation;
      phase = 'LOADING';
      errorKey = null;
      emit();
      try {
        const view = await api.accept(input);
        if (startedAt !== generation) {
          return false;
        }
        localTurn = 0;
        localDroppedFrames = 0;
        return applyView(view, startedAt, false);
      } catch (error) {
        return fail(error, startedAt);
      }
    },
    close: () => withSession((sessionId) => api.close(sessionId)),
    detach: () => withSession((sessionId) => api.detach(sessionId)),
    destroy(): void {
      generation += 1;
      reset();
      phase = 'DESTROYED';
      emit();
    },
    executeToolCall: (toolCallId: number) =>
      withSession((sessionId) => api.executeToolCall(sessionId, toolCallId)),
    async interrupt(): Promise<boolean> {
      if (!session || phase === 'DESTROYED') {
        return false;
      }
      const startedAt = generation;
      const interruptedTurn = localTurn;
      localTurn += 1;
      emit();
      try {
        const view = await api.interrupt(session.id, interruptedTurn);
        return applyView(view, startedAt, true);
      } catch (error) {
        return fail(error, startedAt);
      }
    },
    mute: (muted: boolean) =>
      withSession((sessionId) => api.mute(sessionId, muted)),
    async pushFrame(payload: string, frameSeq: number): Promise<boolean> {
      if (!session || phase !== 'ACTIVE') {
        return false;
      }
      const startedAt = generation;
      try {
        return applyView(
          await api.pushAudio({
            frameSeq,
            payload,
            sessionId: session.id,
            turnNo: localTurn,
          }),
          startedAt,
          true,
        );
      } catch (error) {
        return fail(error, startedAt);
      }
    },
    refresh: () => withSession((sessionId) => api.getSession(sessionId)),
    resume: (ticket: string) =>
      withSession((sessionId) => api.resume(sessionId, ticket)),
    renewTicket: (ticket: string) =>
      withSession((sessionId) => api.renewTicket(sessionId, ticket)),
    snapshot,
    subscribe(listener: (snapshot: RealtimeSnapshot) => void): () => void {
      listeners.add(listener);
      listener(snapshot());
      return () => {
        listeners.delete(listener);
      };
    },
  };
}

/** 从异常里取平台错误码（只认数字；其它形状一律视为未知）。 */
function codeOf(error: unknown): null | number {
  if (typeof error === 'object' && error !== null && 'code' in error) {
    const code = (error as { code?: unknown }).code;
    return typeof code === 'number' ? code : null;
  }
  return null;
}

/** 从异常里取 HTTP 状态码（宿主适配器可携带；缺失按未知处理）。 */
function statusOf(error: unknown): null | number {
  if (typeof error === 'object' && error !== null && 'status' in error) {
    const status = (error as { status?: unknown }).status;
    return typeof status === 'number' ? status : null;
  }
  return null;
}

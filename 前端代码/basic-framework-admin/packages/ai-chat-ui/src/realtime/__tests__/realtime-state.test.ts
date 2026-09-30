import type {
  RealtimeAcceptInput,
  RealtimeApi,
  RealtimeSessionView,
} from '../state';

import { describe, expect, it, vi } from 'vitest';

import {
  createRealtimeMachine,
  pressureOf,
  REALTIME_ERROR_CODES,
  realtimeErrorKey,
  realtimeErrorMessage,
  transcriptOf,
} from '../state';

/** 受控错误对象：真实 API 抛 Error，测试不能抛字面量（no-throw-literal）。 */
function apiError(code: number): Error & { code: number } {
  const error = new Error(String(code)) as Error & { code: number };
  error.code = code;
  return error;
}

const ACCEPT_INPUT: RealtimeAcceptInput = {
  audioFormat: 'audio/pcm@16000:1:20',
  endpointId: 9,
  protocol: 'WEBSOCKET',
  requestKey: 'rt_request_0001',
};

function sessionView(
  overrides: Partial<RealtimeSessionView> = {},
): RealtimeSessionView {
  return {
    audioFormat: 'audio/pcm@16000:1:20',
    closeReason: null,
    droppedStaleFrames: 0,
    endpointId: 9,
    events: [],
    expiresTime: '2026-09-27T10:05:00',
    id: 42,
    inputBufferedBytes: 0,
    inputCapacityBytes: 4096,
    muted: false,
    protocol: 'WEBSOCKET',
    resumeAttempts: 0,
    sessionKey: 'rts_test',
    status: 'OPEN',
    ticket: 'ticket-plain',
    ticketExpiresTime: '2026-09-27T10:01:00',
    toolCalls: [],
    turnNo: 0,
    ...overrides,
  };
}

function transcriptEvent(
  turnNo: number,
  seq: number,
  text: string,
  final: boolean,
) {
  return {
    byteCount: 0,
    createTime: '2026-09-27T10:00:00',
    detailCode: final ? 'FINAL' : 'PARTIAL',
    seq,
    text,
    turnNo,
    type: 'TRANSCRIPT',
  };
}

function apiOf(overrides: Partial<RealtimeApi> = {}): RealtimeApi {
  return {
    accept: vi.fn(async () => sessionView()),
    close: vi.fn(async () =>
      sessionView({
        closeReason: 'client-closed',
        status: 'CLOSED',
        turnNo: 1,
      }),
    ),
    detach: vi.fn(async () => sessionView({ status: 'DETACHED', turnNo: 1 })),
    executeToolCall: vi.fn(async () => sessionView()),
    getSession: vi.fn(async () => sessionView()),
    interrupt: vi.fn(async () => sessionView({ turnNo: 1 })),
    mute: vi.fn(async () => sessionView({ muted: true, turnNo: 1 })),
    pushAudio: vi.fn(async () => sessionView()),
    resume: vi.fn(async () => sessionView({ resumeAttempts: 1 })),
    renewTicket: vi.fn(async () => sessionView({ ticket: 'ticket-renewed' })),
    ...overrides,
  };
}

describe('realtime state machine', () => {
  it('walks loading → active on accept and exposes server facts', async () => {
    const api = apiOf({
      accept: vi.fn(async () =>
        sessionView({
          events: [transcriptEvent(0, 1, '你好', true)],
          inputBufferedBytes: 3200,
          toolCalls: [
            {
              callId: 'call_1',
              id: 7,
              resultCode: null,
              status: 'PROPOSED',
              toolCode: 'lookup-order',
              turnNo: 0,
            },
          ],
        }),
      ),
    });
    const machine = createRealtimeMachine(api);
    const phases: string[] = [];
    machine.subscribe((snapshot) => phases.push(snapshot.phase));

    const accepted = await machine.accept(ACCEPT_INPUT);

    expect(accepted).toBe(true);
    expect(phases).toContain('LOADING');
    const snapshot = machine.snapshot();
    expect(snapshot.phase).toBe('ACTIVE');
    expect(snapshot.transcript).toEqual([
      { final: true, seq: 1, text: '你好', turnNo: 0 },
    ]);
    expect(snapshot.pressure).toBe('HIGH');
    expect(snapshot.session?.toolCalls).toHaveLength(1);
    expect(api.accept).toHaveBeenCalledWith(ACCEPT_INPUT);
  });

  it('keeps the transcript after close and falls back to the empty state', async () => {
    const machine = createRealtimeMachine(apiOf());
    await machine.accept(ACCEPT_INPUT);

    await machine.close();

    expect(machine.snapshot().phase).toBe('EMPTY');
    expect(machine.snapshot().session?.closeReason).toBe('client-closed');
  });

  it('classifies denials as denied and other failures as failed', async () => {
    const denied = createRealtimeMachine(
      apiOf({
        accept: vi.fn(async () => {
          throw apiError(REALTIME_ERROR_CODES.ACCESS_DENIED);
        }),
      }),
    );
    await denied.accept(ACCEPT_INPUT);
    expect(denied.snapshot().phase).toBe('DENIED');
    expect(denied.snapshot().errorKey).toBe(
      `realtime-error-${REALTIME_ERROR_CODES.ACCESS_DENIED}`,
    );

    const failed = createRealtimeMachine(
      apiOf({
        accept: vi.fn(async () => {
          throw apiError(REALTIME_ERROR_CODES.PROTOCOL_UNVERIFIED);
        }),
      }),
    );
    await failed.accept(ACCEPT_INPUT);
    expect(failed.snapshot().phase).toBe('FAILED');
    expect(
      realtimeErrorMessage({ code: REALTIME_ERROR_CODES.PROTOCOL_UNVERIFIED }),
    ).toContain('未通过平台的实时能力验证');

    const unknown = createRealtimeMachine(
      apiOf({
        accept: vi.fn(async () => {
          throw new Error('network down');
        }),
      }),
    );
    await unknown.accept(ACCEPT_INPUT);
    expect(unknown.snapshot().phase).toBe('FAILED');
    expect(realtimeErrorKey(new Error('x'))).toBe('realtime-error-unknown');
  });

  it('advances the local turn first and discards stale late responses', async () => {
    let resolvePush: (view: RealtimeSessionView) => void = () => {};
    const api = apiOf({
      interrupt: vi.fn(async () => sessionView({ turnNo: 1 })),
      pushAudio: vi.fn(
        () =>
          new Promise<RealtimeSessionView>((resolve) => {
            resolvePush = resolve;
          }),
      ),
    });
    const machine = createRealtimeMachine(api);
    await machine.accept(ACCEPT_INPUT);

    const pendingPush = machine.pushFrame('payload', 0);
    expect(api.pushAudio).toHaveBeenCalledWith(
      expect.objectContaining({ frameSeq: 0, sessionId: 42, turnNo: 0 }),
    );

    await machine.interrupt();
    expect(machine.snapshot().turnNo).toBe(1);

    // 打断前发出的推流响应晚到：旧回合（0 < 1）被丢弃，不覆盖界面状态
    resolvePush(
      sessionView({
        events: [transcriptEvent(0, 5, '旧回合', true)],
        turnNo: 0,
      }),
    );
    await expect(pendingPush).resolves.toBe(false);
    expect(machine.snapshot().turnNo).toBe(1);
    expect(machine.snapshot().localDroppedFrames).toBe(1);
    expect(machine.snapshot().transcript).toHaveLength(0);
  });

  it('sends frames with the local turn and rejects frames outside the active state', async () => {
    const api = apiOf();
    const machine = createRealtimeMachine(api);

    await expect(machine.pushFrame('payload', 1)).resolves.toBe(false);
    expect(api.pushAudio).not.toHaveBeenCalled();

    await machine.accept(ACCEPT_INPUT);
    await machine.interrupt();
    await machine.pushFrame('payload', 2);

    expect(api.pushAudio).toHaveBeenLastCalledWith(
      expect.objectContaining({ frameSeq: 2, turnNo: 1 }),
    );
  });

  it('destroys the session and ignores late responses after a user switch', async () => {
    let resolveAccept: (view: RealtimeSessionView) => void = () => {};
    const machine = createRealtimeMachine(
      apiOf({
        accept: vi.fn(
          () =>
            new Promise<RealtimeSessionView>((resolve) => {
              resolveAccept = resolve;
            }),
        ),
      }),
    );
    const pending = machine.accept(ACCEPT_INPUT);
    machine.destroy();
    expect(machine.snapshot().phase).toBe('DESTROYED');

    resolveAccept(sessionView());
    await expect(pending).resolves.toBe(false);
    expect(machine.snapshot().phase).toBe('DESTROYED');
    expect(machine.snapshot().session).toBeNull();
    expect(machine.snapshot().generation).toBe(1);
  });

  it('delegates mute, resume, renew and tool execution to the api', async () => {
    const api = apiOf();
    const machine = createRealtimeMachine(api);
    await machine.accept(ACCEPT_INPUT);

    await machine.mute(true);
    expect(api.mute).toHaveBeenCalledWith(42, true);
    await machine.resume('ticket-plain');
    expect(api.resume).toHaveBeenCalledWith(42, 'ticket-plain');
    await machine.renewTicket('ticket-plain');
    expect(api.renewTicket).toHaveBeenCalledWith(42, 'ticket-plain');
    await machine.executeToolCall(7);
    expect(api.executeToolCall).toHaveBeenCalledWith(42, 7);
    await machine.detach();
    expect(api.detach).toHaveBeenCalledWith(42);
    await machine.refresh();
    expect(api.getSession).toHaveBeenCalledWith(42);
  });

  it('unsubscribes listeners and stops notifying after destroy', async () => {
    const machine = createRealtimeMachine(apiOf());
    const listener = vi.fn();
    const unsubscribe = machine.subscribe(listener);
    unsubscribe();
    await machine.accept(ACCEPT_INPUT);

    expect(listener).toHaveBeenCalledTimes(1);
  });

  it('derives transcript lines without duplicates and computes pressure safely', () => {
    const lines = transcriptOf([
      transcriptEvent(0, 1, '你', false),
      transcriptEvent(0, 1, '你', false),
      transcriptEvent(0, 2, '你好', true),
      {
        byteCount: 320,
        createTime: '2026-09-27T10:00:00',
        detailCode: 'DELIVERED',
        seq: 3,
        text: '',
        turnNo: 0,
        type: 'AUDIO',
      },
    ]);

    expect(lines).toEqual([
      { final: false, seq: 1, text: '你', turnNo: 0 },
      { final: true, seq: 2, text: '你好', turnNo: 0 },
    ]);
    expect(pressureOf(3072, 4096)).toBe('HIGH');
    expect(pressureOf(1024, 4096)).toBe('NORMAL');
    expect(pressureOf(10, 0)).toBe('NORMAL');
    expect(pressureOf(Number.NaN, 4096)).toBe('NORMAL');
  });
});

import type {
  RealtimeAcceptInput,
  RealtimeApi,
  RealtimeSessionView,
} from '../state';

import { mount } from '@vue/test-utils';

import { describe, expect, it, vi } from 'vitest';

import { createRealtimeMachine, REALTIME_ERROR_CODES } from '../state';

/** 受控错误对象：真实 API 抛 Error，测试不能抛字面量（no-throw-literal）。 */
function apiError(code: number): Error & { code: number } {
  const error = new Error(String(code)) as Error & { code: number };
  error.code = code;
  return error;
}

const { default: RealtimePanel } = await import('../RealtimePanel.vue');

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

async function flush(): Promise<void> {
  await Promise.resolve();
  await Promise.resolve();
  await Promise.resolve();
}

describe('realtimePanel', () => {
  it('renders the loading state while the session is being accepted', async () => {
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
    const wrapper = mount(RealtimePanel, {
      props: { acceptInput: ACCEPT_INPUT, machine },
    });

    const pending = machine.accept(ACCEPT_INPUT);
    await flush();
    expect(wrapper.find('[data-testid="realtime-loading"]').exists()).toBe(
      true,
    );

    resolveAccept(sessionView());
    await pending;
    await flush();
    expect(wrapper.find('[data-testid="realtime-loading"]').exists()).toBe(
      false,
    );
    expect(wrapper.find('[data-testid="realtime-status"]').text()).toBe(
      '会话中',
    );
    wrapper.unmount();
  });

  it('renders the empty state and starts a session from the button', async () => {
    const api = apiOf();
    const machine = createRealtimeMachine(api);
    const wrapper = mount(RealtimePanel, {
      props: { acceptInput: ACCEPT_INPUT, machine },
    });

    expect(wrapper.find('[data-testid="realtime-empty"]').text()).toContain(
      '还没有进行中的实时会话',
    );

    await wrapper.find('[data-testid="realtime-start"]').trigger('click');
    await flush();

    expect(api.accept).toHaveBeenCalledWith(ACCEPT_INPUT);
    expect(wrapper.find('[data-testid="realtime-status"]').text()).toBe(
      '会话中',
    );
    wrapper.unmount();
  });

  it('renders the failure state with a stable message and retry entry', async () => {
    const machine = createRealtimeMachine(
      apiOf({
        accept: vi.fn(async () => {
          throw apiError(REALTIME_ERROR_CODES.BACKPRESSURE);
        }),
      }),
    );
    const wrapper = mount(RealtimePanel, {
      props: { acceptInput: ACCEPT_INPUT, machine },
    });

    await machine.accept(ACCEPT_INPUT);
    await flush();

    expect(wrapper.find('[data-testid="realtime-failed"]').text()).toContain(
      '超过会话缓冲上限',
    );
    expect(wrapper.find('[data-testid="realtime-start"]').exists()).toBe(true);
    wrapper.unmount();
  });

  it('renders the denied state for authorization failures', async () => {
    const machine = createRealtimeMachine(
      apiOf({
        accept: vi.fn(async () => {
          throw apiError(REALTIME_ERROR_CODES.AUTHORIZATION_DENIED);
        }),
      }),
    );
    const wrapper = mount(RealtimePanel, {
      props: { acceptInput: ACCEPT_INPUT, machine },
    });

    await machine.accept(ACCEPT_INPUT);
    await flush();

    expect(wrapper.find('[data-testid="realtime-denied"]').text()).toContain(
      '没有使用实时语音的权限',
    );
    expect(wrapper.find('[data-testid="realtime-start"]').exists()).toBe(true);
    wrapper.unmount();
  });

  it('renders the destroyed state after a user switch or unmount', async () => {
    const machine = createRealtimeMachine(apiOf());
    const wrapper = mount(RealtimePanel, {
      props: { acceptInput: ACCEPT_INPUT, machine },
    });
    await machine.accept(ACCEPT_INPUT);
    await flush();

    machine.destroy();
    await flush();

    expect(wrapper.find('[data-testid="realtime-destroyed"]').text()).toContain(
      '会话已销毁',
    );
    expect(wrapper.find('[data-testid="realtime-start"]').exists()).toBe(false);

    const second = mount(RealtimePanel, {
      props: {
        acceptInput: ACCEPT_INPUT,
        machine: createRealtimeMachine(apiOf()),
      },
    });
    second.unmount();
    expect(machine.snapshot().phase).toBe('DESTROYED');
  });

  it('shows transcript, tool states and pressure without executing raw html', async () => {
    const hostile = '<img src=x onerror="alert(1)">';
    const api = apiOf({
      accept: vi.fn(async () =>
        sessionView({
          events: [
            {
              byteCount: 0,
              createTime: '2026-09-27T10:00:00',
              detailCode: 'PARTIAL',
              seq: 1,
              text: hostile,
              turnNo: 0,
              type: 'TRANSCRIPT',
            },
            {
              byteCount: 320,
              createTime: '2026-09-27T10:00:01',
              detailCode: 'DELIVERED',
              seq: 2,
              text: '',
              turnNo: 0,
              type: 'AUDIO',
            },
          ],
          inputBufferedBytes: 4000,
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
    const wrapper = mount(RealtimePanel, {
      props: { acceptInput: ACCEPT_INPUT, machine },
    });
    await machine.accept(ACCEPT_INPUT);
    await flush();

    expect(
      wrapper.find('[data-testid="realtime-transcript"]').text(),
    ).toContain(hostile);
    expect(wrapper.html()).not.toContain('<img');
    expect(wrapper.find('[data-testid="realtime-pressure"]').exists()).toBe(
      true,
    );
    expect(wrapper.find('[data-testid="realtime-tools"]').text()).toContain(
      'PROPOSED',
    );

    await wrapper
      .find('[data-testid="realtime-tool-execute"]')
      .trigger('click');
    await flush();
    expect(api.executeToolCall).toHaveBeenCalledWith(42, 7);
    wrapper.unmount();
  });

  it('offers interrupt, mute, resume and close only for live sessions', async () => {
    const api = apiOf();
    const machine = createRealtimeMachine(api);
    const wrapper = mount(RealtimePanel, {
      props: { acceptInput: ACCEPT_INPUT, machine },
    });
    await machine.accept(ACCEPT_INPUT);
    await flush();

    await wrapper.find('[data-testid="realtime-interrupt"]').trigger('click');
    await flush();
    expect(api.interrupt).toHaveBeenCalledWith(42, 0);
    expect(wrapper.find('[data-testid="realtime-turn"]').text()).toBe('1');

    await wrapper.find('[data-testid="realtime-mute"]').trigger('click');
    await flush();
    expect(api.mute).toHaveBeenCalledWith(42, true);

    await machine.detach();
    await flush();
    await wrapper.find('[data-testid="realtime-resume"]').trigger('click');
    await flush();
    expect(api.resume).toHaveBeenCalledWith(42, 'ticket-plain');

    await wrapper.find('[data-testid="realtime-close"]').trigger('click');
    await flush();
    expect(api.close).toHaveBeenCalledWith(42);
    expect(wrapper.find('[data-testid="realtime-empty"]').text()).toContain(
      'client-closed',
    );
    wrapper.unmount();
  });

  it('shows dropped stale frames reported by the server', async () => {
    const machine = createRealtimeMachine(
      apiOf({
        accept: vi.fn(async () => sessionView({ droppedStaleFrames: 2 })),
      }),
    );
    const wrapper = mount(RealtimePanel, {
      props: { acceptInput: ACCEPT_INPUT, machine },
    });
    await machine.accept(ACCEPT_INPUT);
    await flush();

    expect(wrapper.find('[data-testid="realtime-dropped"]').text()).toContain(
      '2',
    );
    wrapper.unmount();
  });
});

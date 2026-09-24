import type { BridgeMessage } from '@vben/ai-contracts';

import type { HostBridgeTransport } from '../host-bridge';

import { describe, expect, it, vi } from 'vitest';

import { createHostBridge } from '../host-bridge';

const INSTANCE = 'inst-1';

const ORIGIN = 'https://crm.example.com';

interface Harness {
  bridge: ReturnType<typeof createHostBridge>;
  destroy: ReturnType<typeof vi.fn>;
  errors: { errorCode: string; message: string }[];
  frame: object;
  getAccessToken: ReturnType<typeof vi.fn>;
  rejected: string[];
  sent: BridgeMessage[];
}

function harness(
  options: {
    allowOrigins?: string[];
    token?: null | { expiresAt: string; token: string };
  } = {},
): Harness {
  const sent: BridgeMessage[] = [];
  const errors: { errorCode: string; message: string }[] = [];
  const rejected: string[] = [];
  const frame = { name: 'iframe-source' };
  const getAccessToken = vi.fn(async () =>
    options.token === null
      ? Promise.reject(new Error('no token'))
      : (options.token ?? {
          expiresAt: '2026-09-25T10:00:00Z',
          token: 'aitkt_once_abcdefg',
        }),
  );
  const transport: HostBridgeTransport = {
    destroy: vi.fn(),
    post: (message) => sent.push(message),
    source: () => frame,
  };
  lastFrame = frame;
  const bridge = createHostBridge({
    allowedOrigins: options.allowOrigins ?? [ORIGIN],
    appCode: 'crm-portal',
    getAccessToken,
    instanceId: INSTANCE,
    onError: (error) => errors.push(error),
    onRejected: (reason) => rejected.push(reason),
    serviceId: 'svc_1',
    transport,
  });
  return {
    bridge,
    destroy: transport.destroy as ReturnType<typeof vi.fn>,
    errors,
    frame,
    getAccessToken,
    rejected,
    sent,
  };
}

/** 取自最近一次 harness 的 frame：便于在用例里写"来源正确但其它字段不对"的场景。 */
let lastFrame: object = {};
function harnessedSource() {
  return lastFrame;
}

function hello(overrides: Record<string, unknown> = {}) {
  return {
    appCode: 'crm-portal',
    instanceId: INSTANCE,
    protocolVersion: '1.0',
    type: 'HELLO',
    ...overrides,
  };
}

describe('宿主侧桥（C06）', () => {
  it('构造时必须有允许域：空允许域直接拒绝（不允许退化成谁都信）', () => {
    expect(() => harness({ allowOrigins: [] })).toThrow(/允许域/u);
  });

  it('start 发送 HELLO 并进入等待 READY', () => {
    const { bridge, sent } = harness();

    expect(bridge.state()).toBe('CREATED');
    bridge.start();

    expect(bridge.state()).toBe('WAITING_READY');
    expect(sent).toHaveLength(1);
    expect(sent[0]?.type).toBe('HELLO');
    expect(sent[0]?.instanceId).toBe(INSTANCE);
  });

  it('恶意 frame 送不进 AUTH：origin 不在允许域、来源不是本实例 iframe、实例号不符都不处理', () => {
    const { bridge, getAccessToken, rejected, sent } = harness();
    bridge.start();

    // 1) 允许域之外的 origin
    expect(
      bridge.receive({
        data: hello(),
        origin: 'https://evil.example.com',
        source: undefined,
      }),
    ).toBe(false);
    // 2) origin 对但来源不是本实例 iframe（页面里另一个 frame 冒充）
    expect(
      bridge.receive({
        data: hello(),
        origin: ORIGIN,
        source: { name: 'other-frame' },
      }),
    ).toBe(false);
    // 3) 实例号不匹配
    expect(
      bridge.receive({
        data: hello({ instanceId: 'inst-other' }),
        origin: ORIGIN,
        source: harnessedSource(),
      }),
    ).toBe(false);

    expect(getAccessToken).not.toHaveBeenCalled();
    expect(sent.some((message) => message.type === 'AUTH')).toBe(false);
    expect(rejected).toContain('ORIGIN_NOT_ALLOWED');
    expect(rejected).toContain('INSTANCE_MISMATCH');
  });

  it('乱序/非法 schema/不兼容版本都不触发换票', () => {
    const harnessed = harness();
    harnessed.bridge.start();

    // schema 非法：多带字段
    expect(
      harnessed.bridge.receive({
        data: { ...hello(), html: '<b>x</b>' },
        origin: ORIGIN,
        source: harnessed.frame,
      }),
    ).toBe(false);
    // 未知类型
    expect(
      harnessed.bridge.receive({
        data: { ...hello(), type: 'EXECUTE' },
        origin: ORIGIN,
        source: harnessed.frame,
      }),
    ).toBe(false);
    // 版本不兼容（主版本不同）
    expect(
      harnessed.bridge.receive({
        data: hello({ protocolVersion: '2.0' }),
        origin: ORIGIN,
        source: harnessed.frame,
      }),
    ).toBe(false);

    expect(harnessed.getAccessToken).not.toHaveBeenCalled();
    expect(harnessed.rejected).toContain('SCHEMA_INVALID');
    expect(harnessed.errors.map((error) => error.errorCode)).toContain(
      'PROTOCOL_VERSION_UNSUPPORTED',
    );
  });

  it('rEADY 后换票并进入 INITIALIZED：AUTH 带票据、INIT 带服务与主题', async () => {
    const harnessed = harness();
    harnessed.bridge.start();

    expect(
      harnessed.bridge.receive({
        data: {
          instanceId: INSTANCE,
          protocolVersion: '1.0',
          themeRevision: 2,
          type: 'READY',
        },
        origin: ORIGIN,
        source: harnessed.frame,
      }),
    ).toBe(true);
    await vi.waitFor(() =>
      expect(harnessed.bridge.state()).toBe('INITIALIZED'),
    );

    const auth = harnessed.sent.find((message) => message.type === 'AUTH');
    expect(auth).toBeDefined();
    expect(auth && 'token' in auth ? auth.token : '').toBe(
      'aitkt_once_abcdefg',
    );
    expect(auth && 'expiresAt' in auth ? auth.expiresAt : '').toBe(
      '2026-09-25T10:00:00Z',
    );
    const init = harnessed.sent.find((message) => message.type === 'INIT');
    expect(init).toBeDefined();
    expect(init && 'serviceId' in init ? init.serviceId : '').toBe('svc_1');
    expect(harnessed.getAccessToken).toHaveBeenCalledTimes(1);
  });

  it('连续 READY 与并发 TOKEN_REQUIRED 只换一次票（single-flight）', async () => {
    const harnessed = harness();
    harnessed.bridge.start();
    const ready = {
      instanceId: INSTANCE,
      protocolVersion: '1.0',
      type: 'READY',
    };

    harnessed.bridge.receive({
      data: ready,
      origin: ORIGIN,
      source: harnessed.frame,
    });
    harnessed.bridge.receive({
      data: ready,
      origin: ORIGIN,
      source: harnessed.frame,
    });
    harnessed.bridge.receive({
      data: ready,
      origin: ORIGIN,
      source: harnessed.frame,
    });
    await vi.waitFor(() =>
      expect(harnessed.bridge.state()).toBe('INITIALIZED'),
    );

    expect(harnessed.getAccessToken).toHaveBeenCalledTimes(1);

    // 票据到期：并发两次请求只触发一次换票
    harnessed.bridge.receive({
      data: {
        instanceId: INSTANCE,
        protocolVersion: '1.0',
        reason: 'EXPIRED',
        type: 'TOKEN_REQUIRED',
      },
      origin: ORIGIN,
      source: harnessed.frame,
    });
    harnessed.bridge.receive({
      data: {
        instanceId: INSTANCE,
        protocolVersion: '1.0',
        reason: 'EXPIRED',
        type: 'TOKEN_REQUIRED',
      },
      origin: ORIGIN,
      source: harnessed.frame,
    });
    await vi.waitFor(() =>
      expect(harnessed.getAccessToken).toHaveBeenCalledTimes(2),
    );
  });

  it('换票失败回 ERROR 且不进入 INITIALIZED', async () => {
    const harnessed = harness({ token: null });
    harnessed.bridge.start();

    harnessed.bridge.receive({
      data: { instanceId: INSTANCE, protocolVersion: '1.0', type: 'READY' },
      origin: ORIGIN,
      source: harnessed.frame,
    });
    await vi.waitFor(() => expect(harnessed.errors.length).toBeGreaterThan(0));

    expect(harnessed.bridge.state()).toBe('AUTHENTICATING');
    expect(harnessed.errors[0]?.errorCode).toBe('TOKEN_UNAVAILABLE');
    expect(
      harnessed.sent.filter((message) => message.type === 'AUTH'),
    ).toHaveLength(0);
    expect(
      harnessed.sent.filter((message) => message.type === 'INIT'),
    ).toHaveLength(0);
  });

  it('切用户 resetSession：代次 +1、旧代次的换票结果作废、重新握手', async () => {
    let release: (value: {
      expiresAt: string;
      token: string;
    }) => void = () => {};
    const harnessed = harness();
    harnessed.getAccessToken.mockImplementation(
      () =>
        new Promise<{ expiresAt: string; token: string }>((resolve) => {
          release = resolve;
        }),
    );
    harnessed.bridge.start();
    const before = harnessed.bridge.currentGeneration();

    harnessed.bridge.receive({
      data: { instanceId: INSTANCE, protocolVersion: '1.0', type: 'READY' },
      origin: ORIGIN,
      source: harnessed.frame,
    });
    // 换票在途时切换用户：旧代次结果必须被丢弃
    harnessed.bridge.resetSession();
    expect(harnessed.bridge.currentGeneration()).toBe(before + 1);
    release({ expiresAt: '2026-09-25T10:00:00Z', token: 'aitkt_once_abcdefg' });
    await Promise.resolve();
    await Promise.resolve();

    expect(harnessed.bridge.state()).toBe('WAITING_READY');
    expect(
      harnessed.sent.filter((message) => message.type === 'AUTH'),
    ).toHaveLength(0);
    // 新一代次重新发 HELLO
    expect(
      harnessed.sent.filter((message) => message.type === 'HELLO'),
    ).toHaveLength(2);
  });

  it('两个实例互不干扰：各自的允许域与实例号只接受自己的消息', async () => {
    const first = harness({ allowOrigins: ['https://crm.example.com'] });
    const second = harness({ allowOrigins: ['https://portal-b.example.com'] });
    first.bridge.start();
    second.bridge.start();

    // 给第二个实例的 origin 发给第一个：被拒
    expect(
      first.bridge.receive({
        data: hello(),
        origin: 'https://portal-b.example.com',
        source: first.frame,
      }),
    ).toBe(false);
    // 自己的 READY 才生效
    second.bridge.receive({
      data: { instanceId: INSTANCE, protocolVersion: '1.0', type: 'READY' },
      origin: 'https://portal-b.example.com',
      source: second.frame,
    });
    await vi.waitFor(() => expect(second.bridge.state()).toBe('INITIALIZED'));

    expect(first.bridge.state()).toBe('WAITING_READY');
    expect(first.sent.some((message) => message.type === 'AUTH')).toBe(false);
    expect(second.sent.some((message) => message.type === 'AUTH')).toBe(true);
  });

  it('启动阶段的业务消息被拒绝；DESTROY 单向终止并清理资源', async () => {
    const harnessed = harness();
    harnessed.bridge.start();

    // 白名单内但属于业务阶段的消息：明确拒绝并回 ERROR
    const business = harnessed.bridge.receive({
      data: {
        instanceId: INSTANCE,
        protocolVersion: '1.0',
        type: 'CONTEXT_UPDATE',
      },
      origin: ORIGIN,
      source: harnessed.frame,
    });
    expect(business).toBe(false);
    expect(harnessed.errors.at(-1)?.errorCode).toBe('MESSAGE_NOT_SUPPORTED');

    harnessed.bridge.destroy();
    expect(harnessed.bridge.state()).toBe('DESTROYED');
    expect(harnessed.destroy).toHaveBeenCalledTimes(1);
    // 销毁后一切消息都不再处理
    expect(
      harnessed.bridge.receive({
        data: hello(),
        origin: ORIGIN,
        source: harnessed.frame,
      }),
    ).toBe(false);
    expect(harnessed.getAccessToken).not.toHaveBeenCalled();
  });
});

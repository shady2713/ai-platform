import type { BridgeMessage } from '@vben/ai-embed-sdk';

import { describe, expect, it, vi } from 'vitest';

import { createIframeBridge } from '../iframe-bridge';

const INSTANCE = 'inst-1';

const ORIGIN = 'https://crm.example.com';

function harness(options: { allowedOrigins?: string[] } = {}) {
  const sent: BridgeMessage[] = [];
  const errors: { errorCode: string; message: string }[] = [];
  const parent = { name: 'parent-window' };
  const destroy = vi.fn();
  const onAuth = vi.fn();
  const onInit = vi.fn();
  const onDestroy = vi.fn();
  const bridge = createIframeBridge({
    allowedOrigins: options.allowedOrigins ?? [ORIGIN],
    appCode: 'crm-portal',
    instanceId: INSTANCE,
    onAuth,
    onDestroy,
    onError: (error) => errors.push(error),
    onInit,
    parentSource: parent,
    transport: { destroy, post: (message) => sent.push(message) },
  });
  return { bridge, destroy, errors, onAuth, onDestroy, onInit, parent, sent };
}

const auth = (overrides: Record<string, unknown> = {}) => ({
  expiresAt: '2026-09-25T10:00:00Z',
  instanceId: INSTANCE,
  protocolVersion: '1.0',
  token: 'aitkt_once_abcdefg',
  type: 'AUTH',
  ...overrides,
});

const init = (overrides: Record<string, unknown> = {}) => ({
  instanceId: INSTANCE,
  protocolVersion: '1.0',
  serviceId: 'svc_1',
  type: 'INIT',
  ...overrides,
});

describe('iframe 侧桥（C06）', () => {
  it('允许域为空即拒绝构造（不允许退化成谁都信）', () => {
    expect(() => harness({ allowedOrigins: [] })).toThrow(/允许域/u);
  });

  it('handshake 通知就绪；宿主 HELLO 声明的应用必须与本入口一致', () => {
    const harnessed = harness();

    expect(harnessed.bridge.state()).toBe('CREATED');
    harnessed.bridge.handshake();

    expect(harnessed.bridge.state()).toBe('WAITING_READY');
    expect(harnessed.sent[0]?.type).toBe('READY');
    expect(harnessed.sent[0]?.instanceId).toBe(INSTANCE);

    // 应用不一致：明确拒绝，不回 READY（跨应用串用入口会被挡下）
    expect(
      harnessed.bridge.receive({
        data: {
          appCode: 'other-app',
          instanceId: INSTANCE,
          protocolVersion: '1.0',
          type: 'HELLO',
        },
        origin: ORIGIN,
        source: harnessed.parent,
      }),
    ).toBe(false);
    expect(harnessed.errors.at(-1)?.errorCode).toBe('APP_MISMATCH');

    expect(
      harnessed.bridge.receive({
        data: {
          appCode: 'crm-portal',
          instanceId: INSTANCE,
          protocolVersion: '1.0',
          type: 'HELLO',
        },
        origin: ORIGIN,
        source: harnessed.parent,
      }),
    ).toBe(true);
    expect(harnessed.sent.at(-1)?.type).toBe('READY');
  });

  it('只认父窗口与允许域：其它来源一律丢弃且不回 ERROR', () => {
    const harnessed = harness();
    harnessed.bridge.handshake();

    // 其它 origin
    expect(
      harnessed.bridge.receive({
        data: auth(),
        origin: 'https://evil.example.com',
        source: harnessed.parent,
      }),
    ).toBe(false);
    // 同 origin 但不是父窗口（同页面另一个 frame）
    expect(
      harnessed.bridge.receive({
        data: auth(),
        origin: ORIGIN,
        source: { name: 'sibling' },
      }),
    ).toBe(false);
    // 实例号不符
    expect(
      harnessed.bridge.receive({
        data: auth({ instanceId: 'other' }),
        origin: ORIGIN,
        source: harnessed.parent,
      }),
    ).toBe(false);
    // 版本不兼容
    expect(
      harnessed.bridge.receive({
        data: auth({ protocolVersion: '2.0' }),
        origin: ORIGIN,
        source: harnessed.parent,
      }),
    ).toBe(false);

    expect(harnessed.bridge.credential()).toBeNull();
    expect(harnessed.errors.map((error) => error.errorCode)).toStrictEqual([
      'PROTOCOL_VERSION_UNSUPPORTED',
    ]);
  });

  it('提前业务消息被拒绝（未 AUTH 之前），且不落任何票据', () => {
    const harnessed = harness();
    harnessed.bridge.handshake();

    expect(
      harnessed.bridge.receive({
        data: {
          instanceId: INSTANCE,
          protocolVersion: '1.0',
          type: 'CONTEXT_UPDATE',
        },
        origin: ORIGIN,
        source: harnessed.parent,
      }),
    ).toBe(false);
    expect(harnessed.errors.at(-1)?.errorCode).toBe('MESSAGE_OUT_OF_ORDER');
    expect(harnessed.bridge.credential()).toBeNull();
    expect(harnessed.bridge.state()).toBe('WAITING_READY');
  });

  it('aUTH 只把票据放在内存，INIT 后进入 INITIALIZED 并应用主题', () => {
    const harnessed = harness();
    harnessed.bridge.handshake();

    expect(
      harnessed.bridge.receive({
        data: auth(),
        origin: ORIGIN,
        source: harnessed.parent,
      }),
    ).toBe(true);
    expect(harnessed.bridge.credential()?.token).toBe('aitkt_once_abcdefg');
    expect(harnessed.onAuth).toHaveBeenCalledWith({
      expiresAt: '2026-09-25T10:00:00Z',
      token: 'aitkt_once_abcdefg',
    });

    expect(
      harnessed.bridge.receive({
        data: init({
          theme: {
            colorScheme: 'dark',
            fontFamily: 'system-ui',
            primaryColor: '#1677ff',
            radius: 6,
          },
        }),
        origin: ORIGIN,
        source: harnessed.parent,
      }),
    ).toBe(true);

    expect(harnessed.bridge.state()).toBe('INITIALIZED');
    expect(harnessed.bridge.currentTheme()?.colorScheme).toBe('dark');
    expect(harnessed.onInit).toHaveBeenCalledWith({
      serviceId: 'svc_1',
      theme: expect.objectContaining({ primaryColor: '#1677ff' }),
    });
  });

  it('续票：INITIALIZED 后 requestToken 会清掉本地票据并发 TOKEN_REQUIRED', () => {
    const harnessed = harness();
    harnessed.bridge.handshake();
    harnessed.bridge.receive({
      data: auth(),
      origin: ORIGIN,
      source: harnessed.parent,
    });
    harnessed.bridge.receive({
      data: init(),
      origin: ORIGIN,
      source: harnessed.parent,
    });

    harnessed.bridge.requestToken('EXPIRED');

    expect(harnessed.bridge.credential()).toBeNull();
    expect(harnessed.sent.at(-1)).toMatchObject({
      reason: 'EXPIRED',
      type: 'TOKEN_REQUIRED',
    });

    // 未 INITIALIZED 时不发（避免握手前就要求续票）
    const fresh = harness();
    fresh.bridge.handshake();
    fresh.bridge.requestToken('MISSING');
    expect(
      fresh.sent.some((message) => message.type === 'TOKEN_REQUIRED'),
    ).toBe(false);
  });

  it('dESTROY 清 token 与主题并单向终止', () => {
    const harnessed = harness();
    harnessed.bridge.handshake();
    harnessed.bridge.receive({
      data: auth(),
      origin: ORIGIN,
      source: harnessed.parent,
    });
    harnessed.bridge.receive({
      data: init({
        theme: {
          colorScheme: 'dark',
          fontFamily: 'system-ui',
          primaryColor: '#1677ff',
          radius: 6,
        },
      }),
      origin: ORIGIN,
      source: harnessed.parent,
    });

    harnessed.bridge.receive({
      data: { instanceId: INSTANCE, protocolVersion: '1.0', type: 'DESTROY' },
      origin: ORIGIN,
      source: harnessed.parent,
    });

    expect(harnessed.bridge.state()).toBe('DESTROYED');
    expect(harnessed.bridge.credential()).toBeNull();
    expect(harnessed.bridge.currentTheme()).toBeUndefined();
    expect(harnessed.destroy).toHaveBeenCalledTimes(1);
    expect(harnessed.onDestroy).toHaveBeenCalledTimes(1);
    // 销毁后不再处理任何消息
    expect(
      harnessed.bridge.receive({
        data: auth(),
        origin: ORIGIN,
        source: harnessed.parent,
      }),
    ).toBe(false);
  });
});

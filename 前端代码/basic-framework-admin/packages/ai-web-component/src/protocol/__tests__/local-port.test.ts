import type { BridgeMessage, Theme } from '@vben/ai-contracts';

import { beforeEach, describe, expect, it, vi } from 'vitest';

import { createLocalPort } from '../local-port';

/**
 * 页内桥（X09）：握手顺序、来源/实例/版本校验、票据只留内存、续票与销毁。
 *
 * <p>这里的 "宿主消息" 都是**契约里的真实形状**（经 `parseBridgeMessage` 校验），
 * 与 iframe 路径共用的消息协议因此有同一份证据。
 */
const ORIGIN = 'https://platform.example.com';
const APP_CODE = 'crm-portal';
const INSTANCE = 'inst-1';
const FAKE_TICKET = ['aitkt', 'local', 'port'].join('_');
const THEME: Theme = {
  fontFamily: 'system-ui',
  primaryColor: '#1677ff',
  radius: 6,
};

interface PortHarness {
  calls: { destroyed: boolean; posted: BridgeMessage[] };
  contexts: unknown[];
  destroyed: () => boolean;
  errors: { errorCode: string; message: string }[];
  inits: { serviceId: null | string; theme: Theme | undefined }[];
  port: ReturnType<typeof createLocalPort>;
  self: object;
  themes: Theme[];
}

function harness(): PortHarness {
  const posted: BridgeMessage[] = [];
  const errors: { errorCode: string; message: string }[] = [];
  const inits: { serviceId: null | string; theme: Theme | undefined }[] = [];
  const themes: Theme[] = [];
  const contexts: unknown[] = [];
  const self = {};
  let destroyed = false;
  const port = createLocalPort({
    allowedOrigins: [ORIGIN],
    appCode: APP_CODE,
    instanceId: INSTANCE,
    onContext: (context) => contexts.push(context),
    onDestroyed: () => {
      destroyed = true;
    },
    onError: (error) => errors.push(error),
    onInit: (payload) => inits.push(payload),
    onTheme: (theme) => themes.push(theme),
    self,
    transport: {
      destroy: () => {
        destroyed = true;
      },
      post: (message) => posted.push(message),
    },
  });
  return {
    calls: { destroyed: false, posted },
    contexts,
    destroyed: () => destroyed,
    errors,
    inits,
    port,
    themes,
    self,
  };
}

function host(
  harnessed: PortHarness,
  message: unknown,
  overrides: { origin?: string; source?: unknown } = {},
): boolean {
  return harnessed.port.receive({
    data: message,
    origin: overrides.origin ?? ORIGIN,
    source: overrides.source ?? harnessed.self,
  });
}

function auth() {
  return {
    expiresAt: '2026-09-27T10:00:00Z',
    instanceId: INSTANCE,
    protocolVersion: '1.0',
    token: FAKE_TICKET,
    type: 'AUTH',
  };
}

function init() {
  return {
    instanceId: INSTANCE,
    protocolVersion: '1.0',
    serviceId: 'svc_1',
    theme: THEME,
    type: 'INIT',
  };
}

/** 走到 INITIALIZED（HELLO → READY → AUTH → INIT）。 */
function initialize(harnessed: PortHarness): void {
  host(harnessed, {
    appCode: APP_CODE,
    instanceId: INSTANCE,
    protocolVersion: '1.0',
    type: 'HELLO',
  });
  host(harnessed, auth());
  host(harnessed, init());
}

describe('组件页内桥（X09）', () => {
  let harnessed: PortHarness;

  beforeEach(() => {
    harnessed = harness();
  });

  it('允许域为空即拒绝构造（不允许退化成"谁都信"）', () => {
    expect(() =>
      createLocalPort({
        allowedOrigins: [],
        appCode: APP_CODE,
        instanceId: INSTANCE,
        self: {},
        transport: { destroy: () => undefined, post: () => undefined },
      }),
    ).toThrow(/允许域/u);
  });

  it('hELLO：应用标识不一致即拒绝并回 ERROR；一致则回 READY 并进入等待票据', () => {
    expect(
      host(harnessed, {
        appCode: 'other-app',
        instanceId: INSTANCE,
        protocolVersion: '1.0',
        type: 'HELLO',
      }),
    ).toBe(false);
    expect(harnessed.errors.map((error) => error.errorCode)).toEqual([
      'APP_MISMATCH',
    ]);
    expect(harnessed.calls.posted.at(-1)?.type).toBe('ERROR');

    expect(
      host(harnessed, {
        appCode: APP_CODE,
        instanceId: INSTANCE,
        protocolVersion: '1.0',
        type: 'HELLO',
      }),
    ).toBe(true);
    expect(harnessed.port.state()).toBe('WAITING_READY');
    expect(harnessed.calls.posted.at(-1)?.type).toBe('READY');
  });

  it('来源不是本实例、Origin 不在允许域、实例号不符：一律丢弃且不回 ERROR', () => {
    const before = harnessed.calls.posted.length;
    expect(host(harnessed, init(), { source: {} })).toBe(false);
    expect(
      host(harnessed, init(), { origin: 'https://evil.example.com' }),
    ).toBe(false);
    expect(host(harnessed, { ...init(), instanceId: 'other-instance' })).toBe(
      false,
    );
    expect(harnessed.calls.posted).toHaveLength(before);
    expect(harnessed.errors).toHaveLength(0);
  });

  it('协议版本不兼容：拒绝并回 ERROR（主版本不同不能协商）', () => {
    host(harnessed, {
      appCode: APP_CODE,
      instanceId: INSTANCE,
      protocolVersion: '1.0',
      type: 'HELLO',
    });
    expect(host(harnessed, { ...init(), protocolVersion: '2.0' })).toBe(false);
    expect(harnessed.errors.at(-1)?.errorCode).toBe(
      'PROTOCOL_VERSION_UNSUPPORTED',
    );
  });

  it('提前业务消息：等待票据期间拒绝；白名单未建模的消息回 MESSAGE_NOT_SUPPORTED', () => {
    host(harnessed, {
      appCode: APP_CODE,
      instanceId: INSTANCE,
      protocolVersion: '1.0',
      type: 'HELLO',
    });
    expect(
      host(harnessed, {
        context: { page: 'crm/order' },
        instanceId: INSTANCE,
        protocolVersion: '1.0',
        type: 'CONTEXT_UPDATE',
      }),
    ).toBe(false);
    expect(harnessed.errors.at(-1)?.errorCode).toBe('MESSAGE_OUT_OF_ORDER');

    // 白名单内但本版本未建模（OPEN）：明确拒绝而不是静默丢弃
    const fresh = harness();
    expect(
      host(fresh, {
        instanceId: INSTANCE,
        protocolVersion: '1.0',
        type: 'OPEN',
      }),
    ).toBe(false);
    expect(fresh.errors.at(-1)?.errorCode).toBe('MESSAGE_NOT_SUPPORTED');
  });

  it('未校验通过的输入（非白名单/不合规 AUTH）不进入业务', () => {
    expect(host(harnessed, { hello: 'world' })).toBe(false);
    expect(host(harnessed, null)).toBe(false);
    expect(harnessed.errors).toHaveLength(0);

    // AUTH 的 token 太短：契约层拒绝 → 白名单命中 → 明确回错误
    expect(host(harnessed, { ...auth(), token: 'short' })).toBe(false);
    expect(harnessed.errors.at(-1)?.errorCode).toBe('MESSAGE_NOT_SUPPORTED');
    expect(harnessed.port.credential()).toBeNull();
  });

  it('aUTH：票据只留内存（副本返回、不进 postMessage）', () => {
    host(harnessed, {
      appCode: APP_CODE,
      instanceId: INSTANCE,
      protocolVersion: '1.0',
      type: 'HELLO',
    });
    expect(host(harnessed, auth())).toBe(true);
    const credential = harnessed.port.credential();
    expect(credential).toEqual({
      expiresAt: '2026-09-27T10:00:00Z',
      token: FAKE_TICKET,
    });
    // 返回值是副本：每次调用都是新对象（改它不会碰到桥内部状态）
    const again = harnessed.port.credential();
    expect(again).not.toBe(credential);
    expect(again?.token).toBe(FAKE_TICKET);
    // 票据不出现在任何出站消息里（只有宿主 → 嵌入侧的 AUTH 携带票据）
    for (const message of harnessed.calls.posted) {
      expect(JSON.stringify(message)).not.toContain(FAKE_TICKET);
    }

    // 未 READY 就收到 AUTH：乱序
    const fresh = harness();
    expect(host(fresh, auth())).toBe(false);
    expect(fresh.errors.at(-1)?.errorCode).toBe('MESSAGE_OUT_OF_ORDER');
  });

  it('iNIT：进入 INITIALIZED 并上报服务与主题；重复 INIT 不生效', () => {
    initialize(harnessed);
    expect(harnessed.port.state()).toBe('INITIALIZED');
    expect(harnessed.inits).toEqual([{ serviceId: 'svc_1', theme: THEME }]);
    expect(harnessed.port.currentTheme()).toEqual(THEME);
    expect(host(harnessed, init())).toBe(false);
    expect(harnessed.inits).toHaveLength(1);
  });

  it('iNIT 无服务标识/无主题：按缺省上报（null/undefined），不猜值', () => {
    host(harnessed, {
      appCode: APP_CODE,
      instanceId: INSTANCE,
      protocolVersion: '1.0',
      type: 'HELLO',
    });
    host(harnessed, auth());
    host(harnessed, {
      instanceId: INSTANCE,
      protocolVersion: '1.0',
      type: 'INIT',
    });
    expect(harnessed.inits).toEqual([{ serviceId: null, theme: undefined }]);
  });

  it('cONTEXT_UPDATE：合法上下文转交（只作用于下一次运行）；契约不合法与消费方拒绝都有稳定错误码', () => {
    initialize(harnessed);
    expect(
      host(harnessed, {
        context: { objectId: 'order-1', page: 'crm/order' },
        instanceId: INSTANCE,
        protocolVersion: '1.0',
        type: 'CONTEXT_UPDATE',
      }),
    ).toBe(true);
    expect(harnessed.contexts).toEqual([
      { objectId: 'order-1', page: 'crm/order' },
    ]);

    // 契约层就拒绝（page 不是字符串）：白名单命中 ⇒ 明确回错误，不静默丢弃
    expect(
      host(harnessed, {
        context: { page: 42 },
        instanceId: INSTANCE,
        protocolVersion: '1.0',
        type: 'CONTEXT_UPDATE',
      }),
    ).toBe(false);
    expect(harnessed.errors.at(-1)?.errorCode).toBe('MESSAGE_NOT_SUPPORTED');

    // 消费方（宿主侧仓库）拒绝：回 CONTEXT_SCHEMA_INVALID，不把异常抛穿桥
    const errors: { errorCode: string }[] = [];
    const withErrorHandler = createLocalPort({
      allowedOrigins: [ORIGIN],
      appCode: APP_CODE,
      instanceId: INSTANCE,
      onContext: () => {
        throw new Error('上下文不符合仓库契约');
      },
      onError: (error) => errors.push(error),
      self: harnessed.self,
      transport: { destroy: () => undefined, post: () => undefined },
    });
    for (const message of [
      {
        appCode: APP_CODE,
        instanceId: INSTANCE,
        protocolVersion: '1.0',
        type: 'HELLO',
      },
      auth(),
      init(),
      {
        context: { page: 'crm/order' },
        instanceId: INSTANCE,
        protocolVersion: '1.0',
        type: 'CONTEXT_UPDATE',
      },
    ]) {
      withErrorHandler.receive({
        data: message,
        origin: ORIGIN,
        source: harnessed.self,
      });
    }
    expect(errors.at(-1)?.errorCode).toBe('CONTEXT_SCHEMA_INVALID');
  });

  it('tHEME_UPDATE：只换观感（不改状态、不动票据）', () => {
    initialize(harnessed);
    const dark = { ...THEME, colorScheme: 'dark' as const };
    expect(
      host(harnessed, {
        instanceId: INSTANCE,
        protocolVersion: '1.0',
        theme: dark,
        type: 'THEME_UPDATE',
      }),
    ).toBe(true);
    expect(harnessed.themes).toEqual([dark]);
    expect(harnessed.port.currentTheme()).toEqual(dark);
    expect(harnessed.port.state()).toBe('INITIALIZED');
    expect(harnessed.port.credential()).not.toBeNull();
  });

  it('反向消息（NAVIGATE_REQUEST/REPORT_CREATED 来自宿主）按乱序拒绝', () => {
    initialize(harnessed);
    expect(
      host(harnessed, {
        instanceId: INSTANCE,
        protocolVersion: '1.0',
        route: 'order.detail',
        type: 'NAVIGATE_REQUEST',
      }),
    ).toBe(false);
    expect(harnessed.errors.at(-1)?.errorCode).toBe('MESSAGE_OUT_OF_ORDER');
    expect(
      host(harnessed, {
        instanceId: INSTANCE,
        protocolVersion: '1.0',
        reportId: 'rpt_abc123',
        type: 'REPORT_CREATED',
        version: 1,
      }),
    ).toBe(false);
    expect(harnessed.errors.at(-1)?.errorCode).toBe('MESSAGE_OUT_OF_ORDER');
  });

  it('嵌入侧上报：INIT 之前静默不发，INIT 之后按契约形状发送', () => {
    harnessed.port.requestNavigate('order.detail', { id: 'order-1' });
    harnessed.port.notifyReportCreated({ reportId: 'rpt_abc123', version: 1 });
    expect(harnessed.calls.posted).toHaveLength(0);

    initialize(harnessed);
    harnessed.calls.posted.length = 0;
    harnessed.port.requestNavigate('order.detail', { id: 'order-1' });
    harnessed.port.notifyReportCreated({
      reportId: 'rpt_abc123',
      title: '订单报表',
      version: 2,
    });
    expect(harnessed.calls.posted).toEqual([
      {
        instanceId: INSTANCE,
        params: { id: 'order-1' },
        protocolVersion: '1.0',
        route: 'order.detail',
        type: 'NAVIGATE_REQUEST',
      },
      {
        instanceId: INSTANCE,
        protocolVersion: '1.0',
        reportId: 'rpt_abc123',
        title: '订单报表',
        type: 'REPORT_CREATED',
        version: 2,
      },
    ]);
  });

  it('续票：TOKEN_REQUIRED 发出后由新 AUTH 兑现；失败/销毁兑现 null，不挂起', async () => {
    const pendingBefore = harnessed.port.renew('MISSING');
    await expect(pendingBefore).resolves.toBeNull();

    initialize(harnessed);
    harnessed.calls.posted.length = 0;
    const renewal = harnessed.port.renew('EXPIRED');
    expect(harnessed.port.credential()).toBeNull();
    expect(harnessed.calls.posted.at(-1)).toMatchObject({
      reason: 'EXPIRED',
      type: 'TOKEN_REQUIRED',
    });
    expect(host(harnessed, auth())).toBe(true);
    await expect(renewal).resolves.toBe(FAKE_TICKET);

    // 宿主换了新票据：credential 更新
    const otherTicket = ['aitkt', 'renewed'].join('_');
    host(harnessed, { ...auth(), token: otherTicket });
    expect(harnessed.port.credential()?.token).toBe(otherTicket);

    // 桥失败（ERROR）：兑现 null
    const failed = harnessed.port.renew('REVOKED');
    host(harnessed, {
      errorCode: 'TOKEN_UNAVAILABLE',
      instanceId: INSTANCE,
      message: '宿主未提供',
      protocolVersion: '1.0',
      type: 'ERROR',
    });
    await expect(failed).resolves.toBeNull();
    expect(harnessed.errors.at(-1)?.errorCode).toBe('TOKEN_UNAVAILABLE');

    // 销毁：兑现 null
    const destroyedRenewal = harnessed.port.renew('EXPIRED');
    harnessed.port.destroy();
    await expect(destroyedRenewal).resolves.toBeNull();
  });

  it('再次 HELLO（切用户/重新握手）：清票据与主题并重新 READY', () => {
    initialize(harnessed);
    harnessed.calls.posted.length = 0;
    expect(
      host(harnessed, {
        appCode: APP_CODE,
        instanceId: INSTANCE,
        protocolVersion: '1.0',
        type: 'HELLO',
      }),
    ).toBe(true);
    expect(harnessed.port.state()).toBe('WAITING_READY');
    expect(harnessed.port.credential()).toBeNull();
    expect(harnessed.port.currentTheme()).toBeUndefined();
    expect(harnessed.calls.posted.at(-1)?.type).toBe('READY');
  });

  it('dESTROY：清票据、通报销毁、释放传输；重复销毁幂等且之后不再受理消息', () => {
    initialize(harnessed);
    expect(
      host(harnessed, {
        instanceId: INSTANCE,
        protocolVersion: '1.0',
        type: 'DESTROY',
      }),
    ).toBe(true);
    expect(harnessed.port.state()).toBe('DESTROYED');
    expect(harnessed.port.credential()).toBeNull();
    expect(harnessed.destroyed()).toBe(true);
    expect(host(harnessed, init())).toBe(false);
    harnessed.port.destroy();
    expect(harnessed.port.state()).toBe('DESTROYED');
  });

  it('状态可观测：CREATED → WAITING_READY → INITIALIZED', () => {
    expect(harnessed.port.state()).toBe('CREATED');
    host(harnessed, {
      appCode: APP_CODE,
      instanceId: INSTANCE,
      protocolVersion: '1.0',
      type: 'HELLO',
    });
    expect(harnessed.port.state()).toBe('WAITING_READY');
    host(harnessed, auth());
    host(harnessed, init());
    expect(harnessed.port.state()).toBe('INITIALIZED');
  });
});

describe('页内桥的订阅可见性（X09）', () => {
  it('destroy 会释放传输端口（宿主侧桥调用的入口）', () => {
    const spy = vi.fn();
    const port = createLocalPort({
      allowedOrigins: [ORIGIN],
      appCode: APP_CODE,
      instanceId: INSTANCE,
      self: {},
      transport: { destroy: spy, post: () => undefined },
    });
    port.destroy();
    port.destroy();
    expect(spy).toHaveBeenCalledTimes(1);
  });
});

import type { HostEvent } from '../support/harness';

/**
 * AT-051：错误 origin / source / frame 的消息不被接受（真实浏览器 + 真实跨源 postMessage）。
 *
 * 场景构成（每个 Origin 都是真实 Origin，不是伪造的 event 对象）：
 *  - 宿主页 `http://127.0.0.1:5290`（夹具）：真实 SDK `createHostBridge` + 真实 iframe 传输；
 *  - 已注册嵌入壳 `http://127.0.0.1:5291`（夹具）：真实 `IframeBridge`（apps/ai-chat 源码打包）；
 *  - 攻击页 `http://127.0.0.1:5292`：未授权第三方页；
 *  - 另一个真实嵌入壳 `http://127.0.0.1:5293`：**不在**允许域里（错误 origin 对照物）；
 *  - 同允许域的诱饵 frame：真实壳，但**不是**本实例（伪 source 对照物）。
 *
 * 断言口径：不换票（宿主后端换票次数不增长）、票据不下发给未注册 frame、
 * 上下文/导航不被伪造消息改变、"拒绝"有稳定原因可查。
 *
 * 未覆盖（环境缺口，见 tests/playwright/README.md）：平台侧换票（A04）需要真实后端与
 * 应用凭据，本环境没有；这里断言的是**票据去向与隔离**，不是平台签发票据本身。
 * 也不依赖模型：全程没有对话调用（model-dependent: not configured in this environment）。
 */
import { expect, test } from '@playwright/test';

import {
  ATTACK,
  ATTACK_PAGE_PATH,
  HOST,
  SHELL_A,
  SHELL_B,
  shellUrl,
  TICKET_ENDPOINT,
  TICKET_PATH,
} from '../fixtures/origins.mjs';
import {
  attackFetchTicket,
  attackReceived,
  attackSend,
  capture,
  configureTicket,
  hostCall,
  hostEvents,
  openHostPage,
  shellRaw,
  shellSnapshot,
  ticketLog,
  waitForShellState,
} from '../support/harness';

const APP = 'app-allowed';
const INSTANCE = 'inst-good';

/** 打开宿主页并完成一次**真实成功握手**（正向对照：证明后续的"没换票"不是因为链路本来就不通）。 */
async function openRegisteredBridge(page: import('@playwright/test').Page) {
  await openHostPage(page);
  await configureTicket(page, { delayMs: 0 });
  await hostCall(page, 'openBridge', [
    {
      allowedOrigins: [SHELL_A.origin],
      appCode: APP,
      frameUrl: shellUrl(SHELL_A.origin, {
        appCode: APP,
        instanceId: INSTANCE,
      }),
      instanceId: INSTANCE,
      name: 'good',
      targetOrigin: SHELL_A.origin,
    },
  ]);
  return waitForShellState(page, 'good', 'INITIALIZED');
}

function eventsOf(
  events: HostEvent[],
  kind: string,
  name?: string,
): HostEvent[] {
  return events.filter(
    (event) =>
      event.kind === kind && (name === undefined || event.name === name),
  );
}

async function acceptedTickets(page: import('@playwright/test').Page) {
  const log = await ticketLog(page);
  return log.filter((entry) => entry.accepted);
}

test.describe('AT-051 恶意来源消息与票据隔离', () => {
  test('未授权 origin 的伪造 READY 不触发换票，宿主不向该 frame 发任何票据', async ({
    page,
  }) => {
    const shell = await openRegisteredBridge(page);
    // 正向对照：注册实例拿到了票据
    expect(shell.state).toBe('INITIALIZED');
    expect(shell.authCount).toBe(1);
    expect(shell.credential).not.toBeNull();
    expect(await acceptedTickets(page)).toHaveLength(1);

    // 未授权 Origin 的攻击页（不是宿主注册的实例）
    await hostCall(page, 'openExtraFrame', [
      {
        appCode: APP,
        frameUrl: `${ATTACK.origin}${ATTACK_PAGE_PATH}`,
        instanceId: INSTANCE,
        name: 'attacker',
      },
    ]);
    await attackSend(page, {
      appCode: APP,
      instanceId: INSTANCE,
      protocolVersion: '1.0',
      type: 'READY',
    });
    await attackSend(page, {
      expiresAt: new Date(Date.now() + 60_000).toISOString(),
      instanceId: INSTANCE,
      protocolVersion: '1.0',
      token: ['aitkt', 'forged', 'by-attacker'].join('_'),
      type: 'AUTH',
    });

    await expect
      .poll(
        async () =>
          eventsOf(await hostEvents(page), 'bridge-rejected', 'good').length,
      )
      .toBeGreaterThan(0);
    const rejected = eventsOf(
      await hostEvents(page),
      'bridge-rejected',
      'good',
    );
    expect(rejected.map((event) => event.reason)).toContain(
      'ORIGIN_NOT_ALLOWED',
    );

    // 不换票：宿主后端的换票次数没有增长
    expect(await acceptedTickets(page)).toHaveLength(1);
    // 票据不外泄：攻击 frame 一条消息都没收到（宿主只向精确 targetOrigin 发）
    expect(await attackReceived(page)).toEqual([]);
    // 已注册实例的票据没有被第二条 AUTH 覆盖
    const after = await shellSnapshot(page, 'good');
    expect(after.authCount).toBe(1);
    expect(
      after.received.filter((message) => message.type === 'AUTH'),
    ).toHaveLength(1);
    expect(after.credential).toEqual(shell.credential);

    await capture(page, 'at-051-attacker-origin-rejected');
  });

  test('不在允许域的真实嵌入壳（错误 origin）不被接受', async ({ page }) => {
    await openRegisteredBridge(page);

    // SHELL_B 是同源码的真实壳，但 Origin 不在应用允许域里
    await hostCall(page, 'openExtraFrame', [
      {
        appCode: APP,
        frameUrl: shellUrl(SHELL_B.origin, {
          appCode: APP,
          instanceId: 'inst-wrong-origin',
        }),
        instanceId: 'inst-wrong-origin',
        name: 'wrong-origin-shell',
      },
    ]);
    // 它自己会 handshake（真实 READY），但宿主必须按 origin 拒绝
    await expect
      .poll(
        async () =>
          eventsOf(await hostEvents(page), 'bridge-rejected', 'good').filter(
            (event) => event.reason === 'ORIGIN_NOT_ALLOWED',
          ).length,
        { message: '等待宿主按 origin 拒绝 SHELL_B 的 READY' },
      )
      .toBeGreaterThan(0);

    const wrongOrigin = await shellSnapshot(page, 'wrong-origin-shell');
    expect(wrongOrigin.state).toBe('WAITING_READY');
    expect(wrongOrigin.credential).toBeNull();
    expect(wrongOrigin.authCount).toBe(0);
    expect(wrongOrigin.received.map((message) => message.type)).not.toContain(
      'AUTH',
    );

    const rejected = eventsOf(
      await hostEvents(page),
      'bridge-rejected',
      'good',
    );
    expect(rejected.map((event) => event.reason)).toContain(
      'ORIGIN_NOT_ALLOWED',
    );
    const log = await acceptedTickets(page);
    expect(log).toHaveLength(1);
    expect(log.some((entry) => entry.instanceId === 'inst-wrong-origin')).toBe(
      false,
    );

    await capture(page, 'at-051-wrong-origin-shell');
  });

  test('同允许域的诱饵 frame（伪 source）不被接受', async ({ page }) => {
    await openRegisteredBridge(page);

    // 同一个允许域、同一个 appCode，但 instanceId 不同且未注册为宿主实例
    await hostCall(page, 'openExtraFrame', [
      {
        appCode: APP,
        frameUrl: shellUrl(SHELL_A.origin, {
          appCode: APP,
          instanceId: 'inst-decoy',
        }),
        instanceId: 'inst-decoy',
        name: 'decoy',
      },
    ]);
    await expect
      .poll(
        async () =>
          eventsOf(await hostEvents(page), 'bridge-rejected', 'good').filter(
            (event) => event.reason === 'SOURCE_MISMATCH',
          ).length,
        { message: '等待宿主按 source 拒绝诱饵 frame 的 READY' },
      )
      .toBeGreaterThan(0);

    const decoy = await shellSnapshot(page, 'decoy');
    expect(decoy.state).toBe('WAITING_READY');
    expect(decoy.credential).toBeNull();
    expect(decoy.authCount).toBe(0);

    const rejected = eventsOf(
      await hostEvents(page),
      'bridge-rejected',
      'good',
    );
    expect(rejected.map((event) => event.reason)).toContain('SOURCE_MISMATCH');
    const log = await acceptedTickets(page);
    expect(log).toHaveLength(1);
    expect(log.some((entry) => entry.instanceId === 'inst-decoy')).toBe(false);

    await capture(page, 'at-051-decoy-frame');
  });

  test('已注册 frame 发出的非法 instanceId/协议版本/未知类型/伪造 AUTH 都被拒', async ({
    page,
  }) => {
    const shell = await openRegisteredBridge(page);
    const before = await acceptedTickets(page);

    // 1) instanceId 不符：来源合法但实例不对
    await shellRaw(page, 'good', {
      instanceId: 'inst-other',
      protocolVersion: '1.0',
      type: 'READY',
    });
    // 2) 协议版本不受支持（宿主会回 ERROR，但不改变状态、不换票）
    await shellRaw(page, 'good', {
      instanceId: INSTANCE,
      protocolVersion: '9.9',
      type: 'READY',
    });
    // 3) 白名单内但当前阶段不接受的消息
    await shellRaw(page, 'good', {
      instanceId: INSTANCE,
      protocolVersion: '1.0',
      type: 'OPEN',
    });
    // 4) iframe → 宿主方向的 AUTH（形状合法）：宿主不能把它当成"已认证"
    await shellRaw(page, 'good', {
      expiresAt: new Date(Date.now() + 60_000).toISOString(),
      instanceId: INSTANCE,
      protocolVersion: '1.0',
      token: ['aitkt', 'forged', 'by-frame'].join('_'),
      type: 'AUTH',
    });

    await expect
      .poll(async () => {
        const snapshot = await shellSnapshot(page, 'good');
        return snapshot.errors.length;
      })
      .toBeGreaterThanOrEqual(3);

    const after = await shellSnapshot(page, 'good');
    expect(after.errors.map((error) => error.errorCode)).toEqual(
      expect.arrayContaining([
        'PROTOCOL_VERSION_UNSUPPORTED',
        'MESSAGE_NOT_SUPPORTED',
      ]),
    );
    //
    // 状态与票据完全不变：伪造的 AUTH 没有把宿主推进任何新状态
    expect(after.state).toBe(shell.state);
    expect(after.credential).toEqual(shell.credential);
    expect(after.authCount).toBe(1);
    expect(await acceptedTickets(page)).toHaveLength(before.length);

    const events = await hostEvents(page);
    expect(
      eventsOf(events, 'bridge-rejected', 'good').map((event) => event.reason),
    ).toContain('INSTANCE_MISMATCH');
    expect(
      eventsOf(events, 'bridge-error', 'good').map((event) => event.errorCode),
    ).toEqual(expect.arrayContaining(['PROTOCOL_VERSION_UNSUPPORTED']));

    await capture(page, 'at-051-illegal-messages');
  });

  test('伪造的 context/navigation 消息不改变上下文，合法上下文仍可用', async ({
    page,
  }) => {
    await openRegisteredBridge(page);

    // 正向对照：合法的上下文（只含 FR-13 的六个键）被接受
    await hostCall(page, 'postToFrame', [
      'good',
      {
        context: { objectId: 'order-1', page: 'crm/order' },
        instanceId: INSTANCE,
        protocolVersion: '1.0',
        type: 'CONTEXT_UPDATE',
      },
    ]);
    await expect
      .poll(async () => {
        const snapshot = await shellSnapshot(page, 'good');
        return snapshot.context;
      })
      .toEqual({ objectId: 'order-1', page: 'crm/order' });

    // 伪造 1：把身份字段塞进上下文（越权尝试）
    await hostCall(page, 'postToFrame', [
      'good',
      {
        context: { objectId: 'order-9', subjectId: 'alice' },
        instanceId: INSTANCE,
        protocolVersion: '1.0',
        type: 'CONTEXT_UPDATE',
      },
    ]);
    // 伪造 2：宿主方向发来的 NAVIGATE_REQUEST（方向错误）
    await hostCall(page, 'postToFrame', [
      'good',
      {
        instanceId: INSTANCE,
        params: { url: 'https://evil.example.com/steal' },
        protocolVersion: '1.0',
        route: 'order.detail',
        type: 'NAVIGATE_REQUEST',
      },
    ]);
    // 伪造 3：攻击页直接向宿主页发 context/navigation（未授权来源）
    await hostCall(page, 'openExtraFrame', [
      {
        appCode: APP,
        frameUrl: `${ATTACK.origin}${ATTACK_PAGE_PATH}`,
        instanceId: INSTANCE,
        name: 'attacker',
      },
    ]);
    await attackSend(page, {
      context: { objectId: 'order-9', page: 'crm/order' },
      instanceId: INSTANCE,
      protocolVersion: '1.0',
      type: 'CONTEXT_UPDATE',
    });
    await attackSend(page, {
      instanceId: INSTANCE,
      params: { id: 'order-9' },
      protocolVersion: '1.0',
      route: 'order.detail',
      type: 'NAVIGATE_REQUEST',
    });
    await page.waitForTimeout(400);

    await expect
      .poll(async () => {
        const snapshot = await shellSnapshot(page, 'good');
        return snapshot.errors.length;
      })
      .toBeGreaterThanOrEqual(2);

    const shell = await shellSnapshot(page, 'good');
    // 上下文保持合法值：伪造的 subjectId 没有生效，也没有半更新
    expect(shell.context).toEqual({ objectId: 'order-1', page: 'crm/order' });
    // 观测到的错误码：形状非法（多了身份字段）的 CONTEXT_UPDATE 在**契约层**就被拒，
    // 落到 iframe 侧的 catch 分支 → MESSAGE_NOT_SUPPORTED（CONTEXT_SCHEMA_INVALID 只在
    // 契约通过、上下文仓库再拒绝时出现）。方向错误的 NAVIGATE_REQUEST → MESSAGE_OUT_OF_ORDER。
    expect(shell.errors.map((error) => error.errorCode)).toEqual(
      expect.arrayContaining(['MESSAGE_NOT_SUPPORTED', 'MESSAGE_OUT_OF_ORDER']),
    );

    // 宿主侧：攻击页的导航请求没有进登记路由校验，也没有任何导航被执行
    const snapshot = await hostCall<{ navigate: unknown[] }>(page, 'snapshot');
    expect(snapshot.navigate).toEqual([]);
    expect(await attackReceived(page)).toEqual([]);

    await capture(page, 'at-051-context-navigation');
  });

  test('攻击页无法从宿主后端换票（跨源请求被 Origin 白名单挡住）', async ({
    page,
  }) => {
    await openRegisteredBridge(page);
    await hostCall(page, 'openExtraFrame', [
      {
        appCode: APP,
        frameUrl: `${ATTACK.origin}${ATTACK_PAGE_PATH}`,
        instanceId: 'inst-attacker',
        name: 'attacker',
      },
    ]);

    const outcome = await attackFetchTicket(
      page,
      `${HOST.origin}${TICKET_PATH}`,
      {
        appCode: APP,
        instanceId: 'inst-attacker',
      },
    );
    expect(outcome.error).not.toBeNull();

    const log = await ticketLog(page);
    expect(log.filter((entry) => entry.accepted)).toHaveLength(1);
    expect(
      log.some(
        (entry) => entry.accepted && entry.instanceId === 'inst-attacker',
      ),
    ).toBe(false);
    expect(TICKET_ENDPOINT).toBe(`${HOST.origin}${TICKET_PATH}`);
  });
});

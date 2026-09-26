import type { Page } from '@playwright/test';

import type { HostEvent } from '../support/harness';

/**
 * AT-052：一个页面两个 Chat —— 应用/用户/事件/token 不串线。
 *
 * 场景：同一个宿主页上挂**两个不同应用实例**，且两个 iframe 来自**同一个允许域**——
 * 这是最苛刻的隔离场景：origin 校验此时无法区分它们，只有 `event.source` + `instanceId`
 * 能防串线。宿主页的消息路由故意采用**最坏写法**（每条消息送给所有桥实例），
 * 让 SDK 自己的来源判定承担隔离责任。
 *
 * 未覆盖（见 README）：`createChatMount` 的内部桥没有接收入口（宿主无法把收到的消息喂进去），
 * 因此"挂载层"的握手/事件不成立；本用例的协议级隔离用真实 `createHostBridge` + 真实跨源 iframe，
 * 挂载层只断言 DOM/实例结构隔离。
 * 也不依赖模型：全程没有对话调用（model-dependent: not configured in this environment）。
 */
import { expect, test } from '@playwright/test';

import { SHELL_A, shellUrl } from '../fixtures/origins.mjs';
import {
  capture,
  configureTicket,
  frameByName,
  hostCall,
  hostEvents,
  openHostPage,
  shellAction,
  shellRaw,
  shellSnapshot,
  ticketLog,
  waitForShellState,
} from '../support/harness';

const APP_A = 'app-a';
const APP_B = 'app-b';
const INSTANCE_A = 'inst-a';
const INSTANCE_B = 'inst-b';

function eventsOf(
  events: HostEvent[],
  kind: string,
  name: string,
): HostEvent[] {
  return events.filter((event) => event.kind === kind && event.name === name);
}

async function accepted(page: Page) {
  const log = await ticketLog(page);
  return log.filter((entry) => entry.accepted);
}

async function openTwoInstances(page: Page) {
  await openHostPage(page);
  await configureTicket(page, { delayMs: 0 });
  const shared = { targetOrigin: SHELL_A.origin };
  await hostCall(page, 'openBridge', [
    {
      ...shared,
      allowedOrigins: [SHELL_A.origin],
      appCode: APP_A,
      frameUrl: shellUrl(SHELL_A.origin, {
        appCode: APP_A,
        instanceId: INSTANCE_A,
      }),
      instanceId: INSTANCE_A,
      name: 'chat-a',
    },
  ]);
  await hostCall(page, 'openBridge', [
    {
      ...shared,
      allowedOrigins: [SHELL_A.origin],
      appCode: APP_B,
      frameUrl: shellUrl(SHELL_A.origin, {
        appCode: APP_B,
        instanceId: INSTANCE_B,
      }),
      instanceId: INSTANCE_B,
      name: 'chat-b',
    },
  ]);
  await waitForShellState(page, 'chat-a', 'INITIALIZED');
  await waitForShellState(page, 'chat-b', 'INITIALIZED');
}

test.describe('AT-052 同页两个 Chat 实例隔离', () => {
  test('两个实例各自换票：token 与 appCode 不串线', async ({ page }) => {
    await openTwoInstances(page);

    const log = await accepted(page);
    expect(log).toHaveLength(2);
    expect(log.map((entry) => entry.appCode).toSorted()).toEqual([
      APP_A,
      APP_B,
    ]);
    expect(log[0]?.serial).not.toBe(log[1]?.serial);

    const shellA = await shellSnapshot(page, 'chat-a');
    const shellB = await shellSnapshot(page, 'chat-b');
    expect(shellA.appCode).toBe(APP_A);
    expect(shellB.appCode).toBe(APP_B);
    expect(shellA.credential).not.toBeNull();
    expect(shellB.credential).not.toBeNull();
    // 票据前缀包含各自的 appCode 与序号 ⇒ 谁拿到的是谁的票一目了然
    expect(shellA.credential?.prefix).toContain(APP_A);
    expect(shellB.credential?.prefix).toContain(APP_B);
    expect(shellA.credential?.prefix).not.toBe(shellB.credential?.prefix);

    // 每个实例只收到自己的 AUTH/INIT（各一次）
    for (const [shell, label] of [
      [shellA, 'chat-a'],
      [shellB, 'chat-b'],
    ] as const) {
      expect(
        shell.received.filter((message) => message.type === 'AUTH'),
        label,
      ).toHaveLength(1);
      expect(
        shell.received.filter((message) => message.type === 'INIT'),
        label,
      ).toHaveLength(1);
      expect(shell.authCount).toBe(1);
    }

    // 宿主侧：两个实例之间**真实互发**一条 READY（同 origin，只能靠 source 区分），
    // 双向都必须被来源判定挡住——这一步是确定性的（不依赖加载时序）。
    await shellRaw(page, 'chat-a', {
      instanceId: INSTANCE_A,
      protocolVersion: '1.0',
      type: 'READY',
    });
    await shellRaw(page, 'chat-b', {
      instanceId: INSTANCE_B,
      protocolVersion: '1.0',
      type: 'READY',
    });
    const events = await hostEvents(page);
    expect(
      eventsOf(events, 'bridge-rejected', 'chat-a').map(
        (event) => event.reason,
      ),
    ).toContain('SOURCE_MISMATCH');
    expect(
      eventsOf(events, 'bridge-rejected', 'chat-b').map(
        (event) => event.reason,
      ),
    ).toContain('SOURCE_MISMATCH');

    await capture(page, 'at-052-two-instances-tokens');
  });

  test('上下文按实例分流，伪造跨实例消息被拒', async ({ page }) => {
    await openTwoInstances(page);

    // 只给 chat-a 发合法上下文
    await hostCall(page, 'postToFrame', [
      'chat-a',
      {
        context: { objectId: 'order-1', page: 'crm/order' },
        instanceId: INSTANCE_A,
        protocolVersion: '1.0',
        type: 'CONTEXT_UPDATE',
      },
    ]);
    await expect
      .poll(async () => {
        const snapshot = await shellSnapshot(page, 'chat-a');
        return snapshot.context;
      })
      .toEqual({ objectId: 'order-1', page: 'crm/order' });

    const shellA = await shellSnapshot(page, 'chat-a');
    const shellB = await shellSnapshot(page, 'chat-b');
    expect(shellA.context).toEqual({ objectId: 'order-1', page: 'crm/order' });
    expect(shellB.context).toBeNull();

    // chat-b 的 frame 冒充 chat-a 的实例号：B 侧按 instance 拒，A 侧按 source 拒
    const beforeTickets = await accepted(page);
    const before = beforeTickets.length;
    await shellRaw(page, 'chat-b', {
      instanceId: INSTANCE_A,
      protocolVersion: '1.0',
      type: 'READY',
    });
    await expect
      .poll(async () => {
        const current = await hostEvents(page);
        return eventsOf(current, 'bridge-rejected', 'chat-b').filter(
          (event) => event.reason === 'INSTANCE_MISMATCH',
        ).length;
      })
      .toBeGreaterThan(0);

    const events = await hostEvents(page);
    expect(
      eventsOf(events, 'bridge-rejected', 'chat-a').map(
        (event) => event.reason,
      ),
    ).toContain('SOURCE_MISMATCH');
    // 没有因为伪造消息再换一次票
    const afterTickets = await accepted(page);
    expect(afterTickets.length).toBe(before);
    // 两个实例的状态与票据保持不变
    const shellAAfter = await shellSnapshot(page, 'chat-a');
    const shellBAfter = await shellSnapshot(page, 'chat-b');
    expect(shellAAfter.state).toBe('INITIALIZED');
    expect(shellBAfter.state).toBe('INITIALIZED');

    await capture(page, 'at-052-instance-isolation');
  });

  test('跨实例的导航/报表事件按实例归属，未登记路由被宿主校验拒绝', async ({
    page,
  }) => {
    await openTwoInstances(page);

    // iframe 侧真实上报（C08 的 requestNavigate / notifyReportCreated）
    await shellAction(page, 'chat-a', {
      params: { id: 'order-1' },
      route: 'order.detail',
      type: 'requestNavigate',
    });
    await shellAction(page, 'chat-b', {
      params: { page: 2 },
      route: 'report.list',
      type: 'requestNavigate',
    });
    await shellAction(page, 'chat-b', {
      reportId: 'rpt_fixture_1',
      type: 'notifyReportCreated',
      version: 1,
    });
    await page.waitForTimeout(400);

    // 观测到的现状：宿主桥把 iframe 方向的事件按"未实现"拒绝（C08 的宿主侧入口未接线），
    // 因此宿主**没有**收到任何导航/报表事件——这不是用例失败，是要如实记录的缺口。
    const events = await hostEvents(page);
    for (const name of ['chat-a', 'chat-b']) {
      expect(
        eventsOf(events, 'bridge-error', name).map((event) => event.errorCode),
      ).toContain('MESSAGE_NOT_SUPPORTED');
    }
    const snapshot = await hostCall<{
      navigate: unknown[];
      reportCreated: unknown[];
    }>(page, 'snapshot');
    expect(snapshot.navigate).toEqual([]);
    expect(snapshot.reportCreated).toEqual([]);

    // 宿主侧校验器（C08 的 validateHostNavigation，真实代码）按登记表判定，
    // 事件归属由宿主按来源路由：登记路由 + 已声明参数 ⇒ 接受
    const acceptedA = await hostCall<{ ok: boolean }>(page, 'handleNavigate', [
      'chat-a',
      { params: { id: 'order-1' }, route: 'order.detail' },
    ]);
    expect(acceptedA.ok).toBe(true);
    // 未登记路由 / 任意 URL 参数 ⇒ 拒绝（导航不执行任意目标）
    const rejected = await hostCall<{ ok: boolean; reason: string }>(
      page,
      'handleNavigate',
      [
        'chat-b',
        { params: { url: 'https://evil.example.com' }, route: 'report.list' },
      ],
    );
    expect(rejected.ok).toBe(false);
    expect(rejected.reason).toContain('PARAM_NOT_REGISTERED');

    const attributed = await hostCall<{
      navigate: { route: string; target: string }[];
    }>(page, 'snapshot');
    expect(attributed.navigate).toEqual([
      { params: { id: 'order-1' }, route: 'order.detail', target: 'chat-a' },
    ]);

    await capture(page, 'at-052-events-attribution');
  });

  test('两个挂载实例的 DOM/容器/iframe 互不影响（挂载层结构隔离）', async ({
    page,
  }) => {
    await openHostPage(page);
    await configureTicket(page, { delayMs: 0 });
    for (const [name, appCode, instanceId] of [
      ['mount-a', APP_A, 'mount-inst-a'],
      ['mount-b', APP_B, 'mount-inst-b'],
    ] as const) {
      await hostCall(page, 'openMount', [
        {
          allowedOrigins: [SHELL_A.origin],
          appCode,
          frameUrl: shellUrl(SHELL_A.origin, { appCode, instanceId }),
          instanceId,
          mode: 'inline',
          name,
          targetOrigin: SHELL_A.origin,
        },
      ]);
      await hostCall(page, 'mountAction', [name, 'open']);
    }

    await expect
      .poll(async () => {
        const snapshot = await hostCall<{ dom: { mountedFrames: number } }>(
          page,
          'snapshot',
        );
        return snapshot.dom.mountedFrames;
      })
      .toBe(2);

    // mount.ts 会把自己组件的 testid 写到 frame 上（'ai-chat-frame'）
    const frames = page.locator('[data-testid="ai-chat-frame"]');
    await expect(frames).toHaveCount(2);
    const instances = await frames.evaluateAll((nodes) =>
      nodes.map((node) =>
        node instanceof HTMLIFrameElement ? node.dataset.instance : null,
      ),
    );
    expect(new Set(instances)).toEqual(
      new Set(['mount-inst-a', 'mount-inst-b']),
    );
    // 两个 iframe 指向各自的实例 URL（没有互串）
    const urls = await frames.evaluateAll((nodes) =>
      nodes.map((node) => (node instanceof HTMLIFrameElement ? node.src : '')),
    );
    expect(urls.filter((url) => url.includes('mount-inst-a'))).toHaveLength(1);
    expect(urls.filter((url) => url.includes('mount-inst-b'))).toHaveLength(1);
    expect(frameByName(page, 'mount-a').url()).toContain('mount-inst-a');
    expect(frameByName(page, 'mount-b').url()).toContain('mount-inst-b');

    // 销毁 A 只影响 A：B 的 frame/overlay 仍在
    await hostCall(page, 'mountAction', ['mount-a', 'destroy']);
    const afterDestroy = await hostCall<{
      dom: { iframeCount: number; overlays: number };
    }>(page, 'snapshot');
    expect(afterDestroy.dom.overlays).toBe(1);
    await expect(page.locator('[data-testid="ai-chat-frame"]')).toHaveCount(1);
    expect(frameByName(page, 'mount-b').url()).toContain('mount-inst-b');

    // 挂载层现状：内部桥拿不到消息 ⇒ 没有换票（已知缺口，见 README 的缺陷清单）
    const mountTickets = await accepted(page);
    expect(mountTickets.length).toBe(0);

    await capture(page, 'at-052-mount-isolation');
  });
});

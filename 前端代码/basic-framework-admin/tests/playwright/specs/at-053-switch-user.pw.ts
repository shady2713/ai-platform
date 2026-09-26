import type { Page } from '@playwright/test';

import type { HostEvent } from '../support/harness';

/**
 * AT-053：宿主切用户 —— 清空旧会话、丢弃晚到响应。
 *
 * 三个用例覆盖三层语义：
 *  1. **代次过滤**（协议层，真实跨源 iframe）：换票慢响应在途时切用户，
 *     旧代次的结果必须被丢弃（旧票据绝不能发给新会话），新代次重新换票；
 *  2. **实例销毁**（协议层）：销毁后旧 frame 从页面移除、桥进入 DESTROYED、
 *     旧实例标识不再出现在任何 frame 里，新实例独立换票；
 *  3. **挂载层切用户**（`createChatMount`，与 C10 `switchUserAction` 同口径）：
 *     destroy + 重新 mount 后只剩一份面板/iframe，实例标识更新，旧实例的容器残留为 0。
 *
 * 未覆盖：真实"会话内容清空"（对话列表/消息）需要后端与模型
 * （model-dependent: not configured in this environment）；这里清空的是宿主侧上下文与票据。
 */
import { expect, test } from '@playwright/test';

import { SHELL_A, shellUrl } from '../fixtures/origins.mjs';
import {
  capture,
  configureTicket,
  hostCall,
  hostEvents,
  openHostPage,
  shellSnapshot,
  ticketLog,
  waitForShellState,
} from '../support/harness';

const APP = 'app-switch';
const ALICE = 'inst-alice';
const BOB = 'inst-bob';

async function accepted(page: Page) {
  const log = await ticketLog(page);
  return log.filter((entry) => entry.accepted);
}

function kinds(events: HostEvent[], kind: string, name?: string): HostEvent[] {
  return events.filter(
    (event) =>
      event.kind === kind && (name === undefined || event.name === name),
  );
}

async function openBridge(page: Page, name: string, instanceId: string) {
  await hostCall(page, 'openBridge', [
    {
      allowedOrigins: [SHELL_A.origin],
      appCode: APP,
      frameUrl: shellUrl(SHELL_A.origin, { appCode: APP, instanceId }),
      instanceId,
      name,
      targetOrigin: SHELL_A.origin,
    },
  ]);
}

test.describe('AT-053 宿主切用户', () => {
  test('切用户时在途的换票结果被丢弃（旧代次不落地），新代次重新换票', async ({
    page,
  }) => {
    await openHostPage(page);
    // 换票故意慢：保证"切用户"发生在响应之前
    await configureTicket(page, { delayMs: 1200 });
    await openBridge(page, 'session', ALICE);

    // 等到第一次换票**已发出**（响应还压在延迟里）
    await expect
      .poll(
        async () =>
          kinds(await hostEvents(page), 'ticket-requested', 'session').length,
      )
      .toBe(1);

    // 切用户：清掉在途换票、代次 +1、回到等待 READY
    await configureTicket(page, { delayMs: 0 });
    await hostCall(page, 'resetSession', ['session']);
    const shell = await waitForShellState(page, 'session', 'INITIALIZED');
    expect(shell.authCount).toBe(1);
    expect(shell.credential).not.toBeNull();

    // 等到第一次（慢）响应真正落地——它必须被代次过滤丢弃
    await expect
      .poll(
        async () => {
          const settled = await accepted(page);
          return settled.length;
        },
        { message: '等待第一次慢换票的响应落地' },
      )
      .toBe(2);
    const log = await accepted(page);
    const fast = log.find((entry) => entry.delayMs === 0);
    const slow = log.find((entry) => entry.delayMs !== 0);
    expect(fast?.serial).not.toBe(slow?.serial);

    const after = await shellSnapshot(page, 'session');
    // 会话拿到的票据是"切用户之后新换的"那张，两张票不会互相覆盖
    expect(after.credential?.prefix).toContain(APP);
    expect(after.credential?.prefix).toContain(`s${fast?.serial ?? 0}`);
    expect(after.credential?.prefix).not.toContain(`s${slow?.serial ?? 0}`);
    expect(after.credential).toEqual(shell.credential);
    // 晚到响应没有补发第二条 AUTH，也没有把状态推回去
    expect(after.authCount).toBe(1);
    expect(
      after.received.filter((message) => message.type === 'AUTH'),
    ).toHaveLength(1);
    expect(after.state).toBe('INITIALIZED');

    await capture(page, 'at-053-late-response-discarded');
  });

  test('切用户销毁旧实例：旧 frame 移除、状态终止、新实例独立换票且上下文清空', async ({
    page,
  }) => {
    await openHostPage(page);
    await configureTicket(page, { delayMs: 0 });
    await openBridge(page, 'alice', ALICE);
    await waitForShellState(page, 'alice', 'INITIALIZED');
    await hostCall(page, 'setContext', [
      'alice',
      { objectId: 'order-1', page: 'crm/order' },
    ]);
    expect(await hostCall(page, 'contextSnapshot', ['alice'])).toEqual({
      objectId: 'order-1',
      page: 'crm/order',
    });

    // 切用户：销毁旧实例 + 用新实例标识重新登记（C10 switchUserAction 口径）。
    // 实例标识写在壳的 URL 上，所以换实例必须同时换 frameUrl（与真实宿主一致）。
    await hostCall(page, 'switchUser', [
      'alice',
      {
        frameUrl: shellUrl(SHELL_A.origin, { appCode: APP, instanceId: BOB }),
        instanceId: BOB,
        name: 'bob',
      },
    ]);
    await waitForShellState(page, 'bob', 'INITIALIZED');

    const snapshot = await hostCall<{
      bridges: Record<string, { state: null | string }>;
      contextKeys: Record<string, string[]>;
    }>(page, 'snapshot');
    expect(snapshot.bridges.alice).toBeUndefined();
    expect(snapshot.bridges.bob?.state).toBe('INITIALIZED');
    // 新实例的上下文是空的（旧用户的上下文没有被继承）
    expect(snapshot.contextKeys.bob).toEqual([]);

    // 旧 frame 已从页面移除：页面里不再有 alice 实例的 frame
    const frameUrls = page.frames().map((frame) => frame.url());
    expect(frameUrls.some((url) => url.includes(ALICE))).toBe(false);
    expect(frameUrls.some((url) => url.includes(BOB))).toBe(true);

    // 两个实例各自换过一次票，票据不同（旧票据没有被复用）
    const log = await accepted(page);
    expect(log.map((entry) => entry.instanceId)).toEqual([ALICE, BOB]);
    expect(log[0]?.serial).not.toBe(log[1]?.serial);

    await capture(page, 'at-053-switch-user-destroy');
  });

  test('挂载层切用户：destroy + 重新 mount 后只剩一份面板，实例标识更新', async ({
    page,
  }) => {
    await openHostPage(page);
    await configureTicket(page, { delayMs: 0 });
    await hostCall(page, 'openMount', [
      {
        allowedOrigins: [SHELL_A.origin],
        appCode: APP,
        frameUrl: shellUrl(SHELL_A.origin, {
          appCode: APP,
          instanceId: 'mount-alice',
        }),
        instanceId: 'mount-alice',
        mode: 'inline',
        name: 'chat',
        targetOrigin: SHELL_A.origin,
      },
    ]);
    await hostCall(page, 'mountAction', ['chat', 'open']);
    await expect(page.locator('[data-testid="ai-chat-frame"]')).toHaveCount(1);
    await expect(page.locator('[data-testid="ai-chat-overlay"]')).toHaveCount(
      1,
    );

    await hostCall(page, 'remount', [
      'chat',
      {
        frameUrl: shellUrl(SHELL_A.origin, {
          appCode: APP,
          instanceId: 'mount-bob',
        }),
        instanceId: 'mount-bob',
      },
    ]);

    await expect(page.locator('[data-testid="ai-chat-frame"]')).toHaveCount(1);
    await expect(page.locator('[data-testid="ai-chat-overlay"]')).toHaveCount(
      1,
    );
    const frames = await page
      .locator('[data-testid="ai-chat-frame"]')
      .evaluateAll((nodes) =>
        nodes.map((node) =>
          node instanceof HTMLIFrameElement ? node.src : '',
        ),
      );
    expect(frames).toHaveLength(1);
    expect(frames[0]).toContain('mount-bob');
    const snapshot = await hostCall<{
      mounts: Record<string, { frameInstance: null | string }>;
    }>(page, 'snapshot');
    expect(snapshot.mounts.chat?.frameInstance).toBe('mount-bob');

    await capture(page, 'at-053-mount-switch-user');
  });
});

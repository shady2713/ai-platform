/**
 * AT-014 / AT-017 的客户端侧判定（真实浏览器 + 真实 SDK 客户端 + 真实 SSE 读取）。
 *
 * 图形界面在"断线重连"上只做了一半（应用层没有自动重连循环，见 at-017 用例），
 * 但**重连协议语义**是明确的，且由 SDK 客户端承担，所以在这里用真实浏览器直接驱动它：
 *  1. 第一次订阅被服务端提前关闭 → 结果为 `reason=closed`、`lastSeq` 可信；
 *  2. 重连必须带 `afterSeq=lastSeq`（服务端只补发更大的 seq）；
 *  3. 服务端**重放**已发过的事件时，客户端按 seq 去重（同一事件不重复渲染）；
 *  4. 到达终态即停止订阅（不重执行——重连过程中不得调用受理接口）。
 *
 * 另有窗口过期用例：服务端按契约返回 409 + `1_003_004_006`（`AI_RUN_EVENT_WINDOW_EXPIRED`）时，
 * 客户端必须改为读取运行快照（`/ai/run/get`），而不是重新发起运行。
 */
import type { Page } from '@playwright/test';

import { expect, test } from '@playwright/test';

import {
  Q07_PROBE_PAGE_PATH,
  Q07_RUN_KEY,
  startQ07Api,
} from '../support/q07-resilience-api.mjs';

interface ReconnectResult {
  delivered: {
    call: number;
    seq: number;
    status: string;
    text: null | string;
  }[];
  first: { lastSeq: number; reason: string };
  second: { lastSeq: number; reason: string };
}

interface WindowExpiredResult {
  error: null | {
    code: null | string;
    message: string;
    name: null | string;
    status: null | number;
  };
  reason: null | string;
  snapshot: null | Record<string, unknown>;
}

interface ProbeWindow {
  __q07Probe: {
    reconnectStream: (baseUrl: string) => Promise<ReconnectResult>;
    windowExpired: (baseUrl: string) => Promise<WindowExpiredResult>;
  };
}

async function openProbe(page: Page, origin: string): Promise<void> {
  await page.goto(`${origin}${Q07_PROBE_PAGE_PATH}`);
  await expect(page.locator('#probe-status')).toHaveText('q07-probe-ready');
}

test.describe('AT-014 断线重连按 seq 去重 / 窗口过期转快照', () => {
  test('重连带 afterSeq，重放事件按 seq 去重，不重执行', async ({ page }) => {
    const api = await startQ07Api();
    api.state.events = 'reconnect';
    try {
      await openProbe(page, api.origin);
      const result = await page.evaluate(
        (baseUrl) =>
          (globalThis as unknown as ProbeWindow).__q07Probe.reconnectStream(
            baseUrl,
          ),
        api.apiBase,
      );

      // 第一次订阅：服务端提前关闭（可重连），序号推进到 2
      expect(result.first).toEqual({ lastSeq: 2, reason: 'closed' });
      // 重连（afterSeq=2）：服务端重放 1、2，客户端必须丢弃；3、4 被采纳，4 是终态
      expect(result.second).toEqual({ lastSeq: 4, reason: 'terminal' });
      expect(result.delivered).toEqual([
        { call: 1, seq: 1, status: 'RUNNING', text: '片段一' },
        { call: 1, seq: 2, status: 'RUNNING', text: '片段二' },
        { call: 2, seq: 3, status: 'RUNNING', text: '片段三' },
        { call: 2, seq: 4, status: 'SUCCEEDED', text: '终态' },
      ]);

      // 每次订阅只发一次请求；第二次请求必须带 afterSeq，且 runId 来自业务键
      const calls = api.eventsRequests();
      expect(calls).toHaveLength(2);
      expect(calls[0]?.query).toBe('?runId=4001');
      expect(calls[1]?.query).toBe('?runId=4001&afterSeq=2');
      // 断线重连不重执行：全程没有第二个受理请求，也没有读快照
      expect(
        api.state.requests.filter((item) =>
          item.path.endsWith('/ai/run/accept'),
        ),
      ).toEqual([]);
      expect(
        api.state.requests.filter((item) => item.path.endsWith('/ai/run/get')),
      ).toEqual([]);
    } finally {
      await api.close();
    }
  });

  test('重放窗口过期（契约码 1_003_004_006）转运行快照', async ({ page }) => {
    // 冻结契约：`docs/contracts/ai/error-code-map.md`（1_003_004_006 = 1003004006）
    // 与后端 `AiErrorCodeConstants.AI_RUN_EVENT_WINDOW_EXPIRED` 一致；
    // 客户端常量 `RUN_EVENT_WINDOW_EXPIRED` 必须命中该码，否则这里会抛错而不是读快照。
    const api = await startQ07Api();
    api.state.events = 'window-expired-contract';
    try {
      await openProbe(page, api.origin);
      const result = await page.evaluate(
        (baseUrl) =>
          (globalThis as unknown as ProbeWindow).__q07Probe.windowExpired(
            baseUrl,
          ),
        api.apiBase,
      );
      test.info().annotations.push({
        description: `窗口过期时的实际结果：${JSON.stringify(result)}`,
        type: 'at-014',
      });

      expect(result.error).toBeNull();
      expect(result.reason).toBe('snapshot');
      expect(result.snapshot).toMatchObject({
        runKey: Q07_RUN_KEY,
        status: 'SUCCEEDED',
      });
      // 转查询而不是重执行
      expect(
        api.state.requests.filter((item) => item.path.endsWith('/ai/run/get')),
      ).toHaveLength(1);
      expect(
        api.state.requests.filter((item) =>
          item.path.endsWith('/ai/run/accept'),
        ),
      ).toEqual([]);
    } finally {
      await api.close();
    }
  });
});

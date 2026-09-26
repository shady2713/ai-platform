import type { Frame, Page } from '@playwright/test';

/**
 * Q06 用例共用的夹具操作与证据工具。
 *
 * 断言全部通过**页面上下文**（宿主页 / 跨源 frame / 控制端点）取值，
 * 不在 Node 侧模拟协议行为——这是"真实浏览器"的含义。
 *
 * frame 定位用 iframe 的 `name` 属性（宿主夹具在创建时写入），
 * 因为同一个页面里可能有两个 instanceId 相同的 frame（注册 frame 与诱饵 frame），
 * 只按 URL 匹配会取错对象。
 */
import { mkdir } from 'node:fs/promises';
import { join } from 'node:path';

import { expect } from '@playwright/test';

import { ARTIFACT_ROOT, HOST, HOST_PAGE_PATH } from '../fixtures/origins.mjs';

export interface ShellSnapshot {
  appCode: string;
  authCount: number;
  context: null | Record<string, unknown>;
  credential: null | { expiresAt: string; prefix: string };
  destroyed: boolean;
  errors: { errorCode: string; message: string }[];
  hostOrigin: string;
  instanceId: string;
  received: { origin: string; type: null | string }[];
  state: string;
  theme: Record<string, unknown> | undefined;
}

export interface MountSnapshot {
  frameInstance: null | string;
  framePresent: boolean;
  isOpen: boolean | null;
  mode: null | string;
  overlayCount: number;
  panelNarrow: null | string;
}

export interface HostSnapshot {
  bridges: Record<
    string,
    {
      exists: boolean;
      frameInstance: null | string;
      generation: null | number;
      state: null | string;
    }
  >;
  contextKeys: Record<string, string[]>;
  dom: {
    iframeCount: number;
    mountedFrames: number;
    overlays: number;
    panels: number;
    scrollHosts: number;
  };
  mounts: Record<string, MountSnapshot>;
  navigate: {
    params: Record<string, unknown>;
    route: string;
    target: string;
  }[];
  reportCreated: { reportId: string; target: string; version: number }[];
  ticketCalls: {
    appCode: string;
    at: number;
    instanceId: string;
    status: number;
  }[];
}

export interface HostEvent {
  appCode?: string;
  at: number;
  errorCode?: string;
  instanceId?: string;
  kind: string;
  name?: string;
  reason?: string;
  seq: number;
  status?: number;
  type?: null | string;
}

export interface TicketLogEntry {
  accepted: boolean;
  appCode?: null | string;
  delayMs?: number;
  instanceId?: null | string;
  origin?: string;
  reason?: string;
  seq: number;
  serial?: number;
  status?: number;
}

interface HostWindow {
  __q06Host: {
    contextSnapshot: (name: string) => null | Record<string, unknown>;
    destroyInstance: (name: string) => void;
    events: () => HostEvent[];
    handleNavigate: (
      name: string,
      request: { params?: Record<string, unknown>; route: unknown },
    ) => { ok: false; reason: string } | { ok: true };
    handleReportCreated: (
      name: string,
      event: { reportId: string; title?: string; version: number },
    ) => null | { reportId: string; version: number };
    mountAction: (name: string, action: string, payload?: unknown) => boolean;
    openBridge: (config: Record<string, unknown>) => Promise<string>;
    openExtraFrame: (config: Record<string, unknown>) => Promise<string>;
    openMount: (config: Record<string, unknown>) => string;
    openMountByName: (name: string) => boolean;
    postToFrame: (name: string, payload: unknown) => boolean;
    remount: (name: string, overrides?: Record<string, unknown>) => boolean;
    reset: () => boolean;
    resetSession: (name: string) => boolean;
    setContext: (name: string, input: Record<string, unknown>) => boolean;
    snapshot: () => HostSnapshot;
    switchUser: (
      name: string,
      overrides?: Record<string, unknown>,
    ) => Promise<boolean>;
  };
}

interface ShellWindow {
  __q06Shell: {
    notifyReportCreated: (event: {
      reportId: string;
      title?: string;
      version: number;
    }) => void;
    raw: (payload: unknown) => void;
    requestNavigate: (route: string, params?: Record<string, unknown>) => void;
    requestToken: (reason: string) => void;
    snapshot: () => ShellSnapshot;
  };
}

interface AttackWindow {
  __q06Attack: {
    fetchTicket: (
      url: string,
      body?: Record<string, unknown>,
    ) => Promise<{ status: number; text: string }>;
    received: () => { origin: string; type: null | string }[];
    reset: () => boolean;
    send: (payload: unknown, targetOrigin?: string) => boolean;
  };
}

export async function capture(page: Page, name: string): Promise<string> {
  const dir = join(ARTIFACT_ROOT, 'screenshots');
  await mkdir(dir, { recursive: true });
  const path = join(dir, `${name}.png`);
  await page.screenshot({ fullPage: true, path });
  return path;
}

export async function resetFixture(page: Page): Promise<void> {
  const response = await page.request.post(`${HOST.origin}/control/reset`);
  expect(response.ok()).toBe(true);
}

export async function configureTicket(
  page: Page,
  config: { delayMs?: number; failStatus?: number },
): Promise<void> {
  const response = await page.request.post(
    `${HOST.origin}/control/ticket-config`,
    { data: config },
  );
  expect(response.ok()).toBe(true);
}

export async function ticketLog(page: Page): Promise<TicketLogEntry[]> {
  const response = await page.request.get(`${HOST.origin}/control/ticket-log`);
  expect(response.ok()).toBe(true);
  const payload = (await response.json()) as { log: TicketLogEntry[] };
  return payload.log;
}

/** 打开宿主夹具页并复位状态（每个用例都用干净日志）。 */
export async function openHostPage(page: Page): Promise<void> {
  await page.goto(`${HOST.origin}${HOST_PAGE_PATH}`);
  await page.waitForFunction(
    () =>
      document.querySelector('#host-status')?.textContent ===
      'host-fixture-ready',
  );
  await resetFixture(page);
  await page.evaluate(() => {
    (globalThis as unknown as HostWindow).__q06Host.reset();
  });
}

/** 在页面上下文里调用宿主夹具 API 的直通包装（参数必须可序列化）。 */
export async function hostCall<T>(
  page: Page,
  name: keyof HostWindow['__q06Host'],
  args: unknown[] = [],
): Promise<T> {
  return page.evaluate(
    ([method, callArgs]) => {
      const api = (globalThis as unknown as HostWindow).__q06Host;
      const fn = api[method as keyof HostWindow['__q06Host']] as (
        ...rest: unknown[]
      ) => unknown;
      return fn.apply(api, callArgs as unknown[]);
    },
    [name, args] as [string, unknown[]],
  ) as Promise<T>;
}

export async function hostSnapshot(page: Page): Promise<HostSnapshot> {
  return hostCall<HostSnapshot>(page, 'snapshot');
}

export async function hostEvents(page: Page): Promise<HostEvent[]> {
  return hostCall<HostEvent[]>(page, 'events');
}

export async function hostContext(
  page: Page,
  name: string,
): Promise<null | Record<string, unknown>> {
  return hostCall<null | Record<string, unknown>>(page, 'contextSnapshot', [
    name,
  ]);
}

export function frameByName(page: Page, name: string): Frame {
  const frame = page.frame({ name });
  if (frame === null) {
    throw new Error(`没有找到 name=${name} 的 frame`);
  }
  return frame;
}

export async function shellSnapshot(
  page: Page,
  frameName: string,
): Promise<ShellSnapshot> {
  return frameByName(page, frameName).evaluate(() =>
    (globalThis as unknown as ShellWindow).__q06Shell.snapshot(),
  );
}

export async function shellAction(
  page: Page,
  frameName: string,
  action:
    | {
        params?: Record<string, unknown>;
        route: string;
        type: 'requestNavigate';
      }
    | { reason: string; type: 'requestToken' }
    | { reportId: string; type: 'notifyReportCreated'; version: number },
): Promise<void> {
  await frameByName(page, frameName).evaluate((request) => {
    const api = (globalThis as unknown as ShellWindow).__q06Shell;
    if (request.type === 'requestNavigate') {
      api.requestNavigate(request.route, request.params as never);
      return;
    }
    if (request.type === 'requestToken') {
      api.requestToken(request.reason);
      return;
    }
    api.notifyReportCreated({
      reportId: request.reportId,
      version: request.version,
    });
  }, action);
}

/** 已注册 frame 的"伪造报文"出口（AT-051 的非法 instanceId / 协议版本 / 未知类型）。 */
export async function shellRaw(
  page: Page,
  frameName: string,
  payload: unknown,
): Promise<void> {
  await frameByName(page, frameName).evaluate((value) => {
    (globalThis as unknown as ShellWindow).__q06Shell.raw(value);
  }, payload);
}

export async function attackReceived(
  page: Page,
  frameName = 'attacker',
): Promise<{ origin: string; type: null | string }[]> {
  return frameByName(page, frameName).evaluate(() =>
    (globalThis as unknown as AttackWindow).__q06Attack.received(),
  );
}

export async function waitForShellState(
  page: Page,
  frameName: string,
  state: string,
): Promise<ShellSnapshot> {
  await expect
    .poll(
      async () => {
        const snapshot = await shellSnapshot(page, frameName);
        return snapshot.state;
      },
      {
        message: `等待 ${frameName} 进入 ${state}`,
        timeout: 20_000,
      },
    )
    .toBe(state);
  return shellSnapshot(page, frameName);
}

export { ARTIFACT_ROOT, HOST, HOST_PAGE_PATH };

/** 从攻击页向 parent 发报文（默认 `'*'`，攻击者的典型写法）。 */
export async function attackSend(
  page: Page,
  payload: unknown,
  targetOrigin = '*',
  frameName = 'attacker',
): Promise<void> {
  await frameByName(page, frameName).evaluate(
    ([value, target]) => {
      (globalThis as unknown as AttackWindow).__q06Attack.send(value, target);
    },
    [payload, targetOrigin] as [unknown, string],
  );
}

/** 从攻击页跨源要票据：返回 reject 原因（应被宿主后端的 Origin 白名单挡住）。 */
export async function attackFetchTicket(
  page: Page,
  url: string,
  body: Record<string, unknown>,
  frameName = 'attacker',
): Promise<{
  error: null | string;
  status: null | number;
  text: null | string;
}> {
  return frameByName(page, frameName).evaluate(
    async ([target, payload]) => {
      try {
        const result = await (
          globalThis as unknown as AttackWindow
        ).__q06Attack.fetchTicket(target, payload);
        return { error: null, status: result.status, text: result.text };
      } catch (error) {
        return {
          error: error instanceof Error ? error.message : String(error),
          status: null,
          text: null,
        };
      }
    },
    [url, body] as [string, Record<string, unknown>],
  );
}

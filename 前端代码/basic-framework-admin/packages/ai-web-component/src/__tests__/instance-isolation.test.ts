import type {
  HarnessFetch,
  RecordedRequest,
} from './support/component-harness';

import { beforeEach, describe, expect, it, vi } from 'vitest';

import {
  createComponent,
  defineComponent,
  installFetch,
  TEST_APP_CODE,
} from './support/component-harness';

/**
 * X09 验收：**一个页面上两个组件实例的身份隔离**（与 AT-052 同一口径）。
 *
 * <p>场景比 iframe 路径更苛刻的地方：两个实例在**同一个 JS 环境**里，共享 `window`、`document`、
 * 模块状态与页面级事件。判定归属的责任全在组件自己：每实例一个身份令牌（页内桥的来源比对）、
 * 一份独立票据、一份独立上下文、一份独立路由登记表，事件只在自己元素上派发。
 */

const APP_B = 'crm-portal-b';
const TOKEN_A = ['aitkt', 'instance', 'a'].join('_');
const TOKEN_B = ['aitkt', 'instance', 'b'].join('_');
const ROUTES_A = { 'order.detail': { params: { id: 'string' } } };

interface TwoInstances {
  fetch: HarnessFetch;
  instanceA: ReturnType<typeof createComponent>;
  instanceB: ReturnType<typeof createComponent>;
}

function authorizedRequests(
  fetchHarness: HarnessFetch,
  token: string,
): RecordedRequest[] {
  return fetchHarness.requests.filter(
    (request) => request.headers.authorization === `Bearer ${token}`,
  );
}

async function openTwoInstances(): Promise<TwoInstances> {
  const fetchHarness = installFetch();
  const instanceA = createComponent({
    appCode: TEST_APP_CODE,
    instanceId: 'inst-a',
    routes: ROUTES_A,
    serviceId: 'svc_1',
    ticket: async () => ({
      expiresAt: '2026-09-27T10:00:00Z',
      token: TOKEN_A,
    }),
  });
  const instanceB = createComponent({
    appCode: APP_B,
    instanceId: 'inst-b',
    serviceId: 'svc_2',
    ticket: async () => ({
      expiresAt: '2026-09-27T10:00:00Z',
      token: TOKEN_B,
    }),
  });
  instanceA.element.open();
  instanceB.element.open();
  await vi.waitFor(() => {
    expect(instanceA.element.state()).toBe('INITIALIZED');
    expect(instanceB.element.state()).toBe('INITIALIZED');
  });
  return { fetch: fetchHarness, instanceA, instanceB };
}

describe('x09 验收：同页两个组件实例的身份隔离', () => {
  beforeEach(() => {
    vi.unstubAllGlobals();
    defineComponent();
  });

  it('两次换票互不串线：各自拿到自己的票据，请求头里各带各的', async () => {
    const {
      fetch: fetchHarness,
      instanceA,
      instanceB,
    } = await openTwoInstances();

    expect(instanceA.getAccessToken).toHaveBeenCalledTimes(1);
    expect(instanceB.getAccessToken).toHaveBeenCalledTimes(1);

    await vi.waitFor(() => {
      expect(fetchHarness.requests.length).toBeGreaterThanOrEqual(2);
    });
    // 每个实例的请求只带自己的票据（A 的票据不出现在 B 的任何请求里，反之亦然）
    const requestsA = authorizedRequests(fetchHarness, TOKEN_A);
    const requestsB = authorizedRequests(fetchHarness, TOKEN_B);
    expect(requestsA.length).toBeGreaterThan(0);
    expect(requestsB.length).toBeGreaterThan(0);
    for (const request of fetchHarness.requests) {
      const authorization = request.headers.authorization;
      if (authorization === undefined) {
        continue;
      }
      expect([`Bearer ${TOKEN_A}`, `Bearer ${TOKEN_B}`]).toContain(
        authorization,
      );
    }
    expect(instanceA.element.appCode).toBe(TEST_APP_CODE);
    expect(instanceB.element.appCode).toBe(APP_B);
  });

  it('事件按实例归属：A 的导航/报表事件不会派发到 B 上', async () => {
    const { instanceA, instanceB } = await openTwoInstances();
    const eventsA: string[] = [];
    const eventsB: string[] = [];
    for (const name of [
      'ai-navigate-request',
      'ai-report-created',
      'ai-rejected',
    ]) {
      instanceA.element.addEventListener(name, () => eventsA.push(name));
      instanceB.element.addEventListener(name, () => eventsB.push(name));
    }

    instanceA.element.requestNavigate('order.detail', { id: 'order-1' });
    instanceA.element.notifyReportCreated({
      reportId: 'rpt_abc123',
      version: 1,
    });
    // B 没有登记这张路由表：请求被拒（拒绝原因留在 B 自己身上）
    instanceB.element.requestNavigate('order.detail', { id: 'order-1' });

    expect(eventsA).toEqual(['ai-navigate-request', 'ai-report-created']);
    expect(eventsB).toEqual(['ai-rejected']);
  });

  it('上下文与主题互不影响：A 的上下文只出现在 A 的受理请求里', async () => {
    const {
      fetch: fetchHarness,
      instanceA,
      instanceB,
    } = await openTwoInstances();
    instanceA.element.updateContext({ objectId: 'order-1', page: 'crm/order' });

    instanceA.element.notifyReportCreated({
      reportId: 'rpt_abc123',
      version: 1,
    });
    // 用发送动作驱动受理请求（面板输入 + 提交）
    const inputA = instanceA.shadow.querySelector<HTMLInputElement>(
      '[data-testid="ai-conversation-input"]',
    );
    const sendA = instanceA.shadow.querySelector<HTMLButtonElement>(
      '[data-testid="ai-conversation-send"]',
    );
    if (inputA === null || sendA === null) {
      throw new Error('A 的会话面板未挂载');
    }
    inputA.value = 'A 的问题';
    inputA.dispatchEvent(new Event('input'));
    sendA.click();
    await vi.waitFor(() => {
      expect(
        fetchHarness.requests.some((request) =>
          request.url.includes('/ai/run/accept'),
        ),
      ).toBe(true);
    });

    const acceptedA = authorizedRequests(fetchHarness, TOKEN_A).find(
      (request) => request.url.includes('/ai/run/accept'),
    );
    expect(acceptedA?.body).toContain('A 的问题');
    expect(acceptedA?.body).toContain('crm/order');
    // B 从未发出受理请求，也没有 A 的上下文
    expect(
      authorizedRequests(fetchHarness, TOKEN_B).some((request) =>
        request.url.includes('/ai/run/accept'),
      ),
    ).toBe(false);
    expect(instanceB.element.updateContext({ page: 'crm/order' })).toEqual({
      page: 'crm/order',
    });

    // 主题只落在 A 的元素上（只用白名单内的自托管字体）
    instanceA.element.updateTheme({
      fontFamily:
        'system-ui, -apple-system, "PingFang SC", "Microsoft YaHei", sans-serif',
      primaryColor: '#16a34a',
      radius: 12,
    });
    const wrapperA = instanceA.shadow.querySelector<HTMLElement>(
      '[data-testid="ai-chat-component"]',
    );
    const wrapperB = instanceB.shadow.querySelector<HTMLElement>(
      '[data-testid="ai-chat-component"]',
    );
    expect(wrapperA?.style.getPropertyValue('--ai-primary-color')).toBe(
      '#16a34a',
    );
    expect(wrapperB?.style.getPropertyValue('--ai-primary-color')).not.toBe(
      '#16a34a',
    );
  });

  it('销毁 A 不影响 B：B 的面板与桥保持可用', async () => {
    const { instanceA, instanceB } = await openTwoInstances();
    instanceA.element.destroy();

    expect(instanceA.element.state()).toBe('CREATED');
    expect(instanceB.element.state()).toBe('INITIALIZED');
    expect(instanceB.element.isOpen()).toBe(true);
    expect(
      instanceB.shadow.querySelector('[data-testid="ai-conversation"]'),
    ).not.toBeNull();
    // B 的事件与上报仍然工作
    const events: string[] = [];
    instanceB.element.addEventListener('ai-report-created', () =>
      events.push('report'),
    );
    instanceB.element.notifyReportCreated({
      reportId: 'rpt_abc123',
      version: 1,
    });
    expect(events).toEqual(['report']);
  });

  it('切用户（resetSession）：A 换代换票，B 的票据与界面不受影响', async () => {
    const {
      fetch: fetchHarness,
      instanceA,
      instanceB,
    } = await openTwoInstances();
    const surfaceB = instanceB.shadow.querySelector(
      '[data-testid="ai-conversation"]',
    );
    instanceA.element.resetSession();

    await vi.waitFor(() => {
      expect(instanceA.getAccessToken).toHaveBeenCalledTimes(2);
    });
    await vi.waitFor(() => {
      expect(instanceA.element.state()).toBe('INITIALIZED');
    });
    expect(instanceB.getAccessToken).toHaveBeenCalledTimes(1);
    expect(instanceB.element.state()).toBe('INITIALIZED');
    expect(
      instanceB.shadow.querySelector('[data-testid="ai-conversation"]'),
    ).toBe(surfaceB);
    // A 重新挂载出的是新的界面实例（旧会话状态不残留）
    expect(
      instanceA.shadow.querySelector('[data-testid="ai-conversation"]'),
    ).not.toBeNull();
    expect(fetchHarness.requests.length).toBeGreaterThan(0);
  });
});

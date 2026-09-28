import type { BridgeMessage } from '@vben/ai-contracts';
import type { HostEventHandlers } from '@vben/ai-embed-sdk';

import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

import { createChatMount, createHostEventHandlers } from '@vben/ai-embed-sdk';

import { beforeEach, describe, expect, it, vi } from 'vitest';

import { createLocalPort } from '../protocol/local-port';
import {
  createComponent,
  defineComponent,
  flushBridge,
  installFetch,
  TEST_APP_CODE,
} from './support/component-harness';

/**
 * X09 验收：**旧 iframe 路径继续工作**（兼容），且组件路径与它说同一套协议。
 *
 * <p>三条证据：
 * <ol>
 *   <li>iframe 路径本身（`createChatMount` + `createHostBridge`，C06/C07 的真实实现）
 *       在同一个测试进程里照常完成挂载、握手、事件与销毁；</li>
 *   <li>N-1 基线夹具（`ai-embed-sdk/fixtures/n-1/baseline.json`）冻结的握手消息，
 *       组件路径的嵌入侧（`createLocalPort`）逐条接受——两条路径的协议口径一致；</li>
 *   <li>组件路径的宿主侧就是 SDK 的 `HostBridge`：不复制状态机，升级 SDK 时两条路径同时受益。</li>
 * </ol>
 */
const ORIGIN = 'https://platform.example.com';
const FAKE_TICKET = ['aitkt', 'compat'].join('_');

const BASELINE_PATH = resolve(
  import.meta.dirname,
  '../../../ai-embed-sdk/fixtures/n-1/baseline.json',
);

interface Baseline {
  handshake: BridgeMessage[];
  messageTypes: string[];
  protocolVersion: string;
}

const baseline = JSON.parse(readFileSync(BASELINE_PATH, 'utf8')) as Baseline;

/** iframe 路径夹具：真实 `createChatMount` + 真实 `createHostBridge`（消息经两个桥对发）。 */
function mountIframePath(onNavigate?: HostEventHandlers['navigate']) {
  const container = document.createElement('div');
  document.body.append(container);
  let frame: HTMLIFrameElement | null = null;
  const hostMessages: BridgeMessage[] = [];
  const getAccessToken = vi.fn(async () => ({
    expiresAt: '2026-09-27T10:00:00Z',
    token: FAKE_TICKET,
  }));
  const mount = createChatMount({
    allowedOrigins: [ORIGIN],
    appCode: TEST_APP_CODE,
    container,
    frame: {
      create() {
        const element = document.createElement('iframe');
        Object.defineProperty(element, 'contentWindow', {
          configurable: true,
          value: {
            postMessage: (message: BridgeMessage) => hostMessages.push(message),
          },
        });
        frame = element;
        return element;
      },
      destroy() {
        frame?.remove();
        frame = null;
      },
    },
    getAccessToken,
    instanceId: 'inst-1',
    mode: 'inline',
    ...(onNavigate === undefined ? {} : { onNavigate }),
  });
  return {
    container,
    frameElement: (): HTMLIFrameElement => {
      if (frame === null) {
        throw new Error('iframe 尚未创建');
      }
      return frame;
    },
    getAccessToken,
    hostMessages,
    mount,
  };
}

describe('x09 验收：iframe 路径的兼容性', () => {
  beforeEach(() => {
    vi.unstubAllGlobals();
    defineComponent();
  });

  it('旧 iframe 路径照常工作：打开 → 握手 → 导航校验 → 销毁', async () => {
    const harnessed = mountIframePath();
    harnessed.mount.open();
    expect(harnessed.mount.isOpen()).toBe(true);

    // iframe 文档就绪前不发 HELLO（发早了会掉进尚未就绪的文档）
    expect(harnessed.hostMessages).toHaveLength(0);
    harnessed.frameElement().dispatchEvent(new Event('load'));

    const frameWindow = harnessed.frameElement()
      .contentWindow as unknown as MessageEventSource;
    // iframe 侧 READY（本用例直接构造真实契约形状的消息）
    expect(
      harnessed.mount.receive(
        new MessageEvent('message', {
          data: {
            instanceId: 'inst-1',
            protocolVersion: '1.0',
            type: 'READY',
          },
          origin: ORIGIN,
          source: frameWindow,
        }),
      ),
    ).toBe(true);
    await vi.waitFor(() => {
      expect(harnessed.getAccessToken).toHaveBeenCalledTimes(1);
    });
    await vi.waitFor(() => {
      expect(harnessed.hostMessages.map((message) => message.type)).toEqual([
        'HELLO',
        'AUTH',
        'INIT',
      ]);
    });

    // 业务事件：登记路由放行、未登记拒绝（C08 的宿主校验器，真实实现）
    const navigations: string[] = [];
    const handlers = createHostEventHandlers({
      onNavigate: (event) => navigations.push(event.route),
      routes: { 'order.detail': { params: { id: 'string' } } },
    });
    const withNavigation = mountIframePath((request) =>
      handlers.navigate(request),
    );
    withNavigation.mount.open();
    withNavigation.frameElement().dispatchEvent(new Event('load'));
    const navigationSource = withNavigation.frameElement()
      .contentWindow as unknown as MessageEventSource;
    withNavigation.mount.receive(
      new MessageEvent('message', {
        data: {
          instanceId: 'inst-1',
          protocolVersion: '1.0',
          type: 'READY',
        },
        origin: ORIGIN,
        source: navigationSource,
      }),
    );
    await vi.waitFor(() => {
      expect(
        withNavigation.hostMessages.map((message) => message.type),
      ).toEqual(['HELLO', 'AUTH', 'INIT']);
    });
    withNavigation.mount.receive(
      new MessageEvent('message', {
        data: {
          instanceId: 'inst-1',
          params: { id: 'order-1' },
          protocolVersion: '1.0',
          route: 'order.detail',
          type: 'NAVIGATE_REQUEST',
        },
        origin: ORIGIN,
        source: navigationSource,
      }),
    );
    withNavigation.mount.receive(
      new MessageEvent('message', {
        data: {
          instanceId: 'inst-1',
          protocolVersion: '1.0',
          route: 'evil.route',
          type: 'NAVIGATE_REQUEST',
        },
        origin: ORIGIN,
        source: navigationSource,
      }),
    );
    expect(navigations).toEqual(['order.detail']);

    harnessed.mount.destroy();
    expect(harnessed.mount.isOpen()).toBe(false);
    expect(
      harnessed.container.querySelector('[data-testid="ai-chat-panel"]'),
    ).toBeNull();
    withNavigation.mount.destroy();
  });

  it('组件路径接受 N-1 基线夹具的握手消息（两条路径同一份协议口径）', () => {
    expect(baseline.protocolVersion).toBe('1.0');
    const posted: BridgeMessage[] = [];
    const inits: unknown[] = [];
    const self = {};
    const port = createLocalPort({
      allowedOrigins: [ORIGIN],
      appCode: TEST_APP_CODE,
      instanceId: 'inst-1',
      onInit: (payload) => inits.push(payload),
      self,
      transport: { destroy: () => undefined, post: (m) => posted.push(m) },
    });
    for (const message of baseline.handshake) {
      // READY 是嵌入侧发给宿主的消息：夹具里的这一条跳过（方向不同）
      if (message.type === 'READY') {
        continue;
      }
      expect(
        port.receive({ data: message, origin: ORIGIN, source: self }),
        `夹具消息 ${message.type} 必须被组件路径接受`,
      ).toBe(true);
    }
    expect(port.state()).toBe('INITIALIZED');
    expect(port.credential()?.token).toBe('aitkt_once_abcdefg');
    expect(inits).toEqual([{ serviceId: 'svc_1', theme: undefined }]);
    expect(posted.map((message) => message.type)).toEqual(['READY']);
    // 夹具的消息白名单与契约一致（不缩水）
    expect(baseline.messageTypes.length).toBeGreaterThanOrEqual(13);
  });

  it('两条路径可以在同一页面共存：互不干扰（各自的容器/事件/票据）', async () => {
    installFetch();
    const iframePath = mountIframePath();
    iframePath.mount.open();
    iframePath.frameElement().dispatchEvent(new Event('load'));

    const component = createComponent({ open: true });
    await vi.waitFor(() => {
      expect(component.element.state()).toBe('INITIALIZED');
    });

    // iframe 路径的 DOM 与组件路径的 DOM 各自独立
    expect(
      iframePath.container.querySelectorAll('[data-testid="ai-chat-panel"]'),
    ).toHaveLength(1);
    expect(
      component.shadow.querySelectorAll(
        '[data-testid="ai-chat-component-panel"]',
      ),
    ).toHaveLength(1);
    expect(
      document.querySelectorAll('[data-testid="ai-chat-component-panel"]'),
    ).toHaveLength(0);

    component.element.destroy();
    iframePath.mount.destroy();
    await flushBridge();
    expect(component.element.state()).toBe('CREATED');
    expect(iframePath.mount.isOpen()).toBe(false);
  });
});

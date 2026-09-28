import type { BusinessContext, Theme } from '@vben/ai-contracts';
import type {
  BridgeState,
  HostEventHandlers,
  HostNavigationAccepted,
  HostReportCreatedEvent,
} from '@vben/ai-embed-sdk';

import type { ComponentChatConfig } from '../config';
import type { ChatSurface } from '../surface/mount-surface';

import {
  createBusinessContextStore,
  createHostBridge,
  createHostEventHandlers,
} from '@vben/ai-embed-sdk';

import { createLocalPort } from '../protocol/local-port';
import {
  createComponentConversationApi,
  createComponentRunApi,
} from '../runtime/ai-ports';
import { mountChatSurface } from '../surface/mount-surface';

/**
 * 组件控制器（X09）：把**同一个桥状态机**装进宿主页面。
 *
 * <p>接线方式（与 iframe 路径逐条对应）：
 * <ul>
 *   <li>宿主侧 = SDK 的 `HostBridge`（HELLO、换票 single-flight、代次过滤、拒绝原因都在那里，本包不复制）；</li>
 *   <li>嵌入侧 = 本包的页内桥（`createLocalPort`），消息经**页内传输**直达，不走 postMessage；</li>
 *   <li>界面 = 现有 ChatUI 的 `ConversationPanel`（会话状态机不复制），端口是共享开放客户端
 *       `createOpenApiClient`；票据由页内桥在 AUTH 时保存、每次请求时读取（只在内存、只在请求头）。</li>
 * </ul>
 *
 * <p>界面在 **INIT 之后**才挂载：此刻票据已经到手，会话列表的首次请求不会打出无票请求。
 * 宿主切用户（`resetSession`）会重建界面：旧界面连同它的异步结果一起被丢弃（不出现"旧用户的消息
 * 落到新会话"）。
 */

export interface ComponentChatController {
  credential(): null | { expiresAt: string; token: string };
  destroy(): void;
  isReady(): boolean;
  notifyReportCreated(event: {
    reportId: string;
    title?: string;
    version: number;
  }): void;
  requestNavigate(
    route: string,
    params?: Record<string, boolean | number | string>,
  ): void;
  /** 宿主切用户：换代并重建界面（旧代次的响应一律丢弃）。 */
  resetSession(): void;
  /** 开始握手（组件打开时调用一次；重复调用幂等）。 */
  start(): void;
  state(): BridgeState;
  updateContext(input: unknown): BusinessContext | null;
  updateTheme(theme: Theme | undefined): void;
}

export interface ComponentChatControllerOptions {
  config: ComponentChatConfig;
  /** 界面挂载点（Shadow DOM 内） */
  container: HTMLElement;
  fetchImpl?: typeof fetch;
  /** 换票回调：与 iframe 路径同一个签名（只向宿主自己的后端要票） */
  getAccessToken: () => Promise<{ expiresAt: string; token: string }>;
  onContext?: (context: BusinessContext | null) => void;
  onDestroyed?: () => void;
  onError?: (error: { errorCode: string; message: string }) => void;
  onNavigateAccepted?: (event: HostNavigationAccepted) => void;
  onReady?: (payload: {
    appCode: string;
    instanceId: string;
    serviceId: null | string;
  }) => void;
  onRejected?: (reason: string) => void;
  onReportCreated?: (event: HostReportCreatedEvent) => void;
  onTheme?: (theme: Theme) => void;
}

export function createComponentChatController(
  options: ComponentChatControllerOptions,
): ComponentChatController {
  const { config } = options;
  // 每个实例一个身份令牌：宿主侧桥用它做来源比对（同页面另一个实例的消息在此被丢弃）
  const identity = {};
  const contexts = createBusinessContextStore();
  let surface: ChatSurface | undefined;
  let surfaceSession = 0;
  let mountedSession = -1;
  let ready = false;
  let destroyed = false;
  let theme: Theme | undefined = config.theme;

  const handlers: HostEventHandlers = createHostEventHandlers({
    onNavigate: (event) => options.onNavigateAccepted?.(event),
    onReportCreated: (event) => options.onReportCreated?.(event),
    routes: config.routes,
  });

  const accessToken = (): null | string =>
    localPort.credential()?.token ?? null;

  function ensureSurface(): void {
    if (surface !== undefined && mountedSession === surfaceSession) {
      return;
    }
    surface?.destroy();
    mountedSession = surfaceSession;
    surface = mountChatSurface({
      api: createComponentConversationApi({
        accessToken,
        baseUrl: config.apiBaseUrl,
        ...(options.fetchImpl === undefined
          ? {}
          : { fetchImpl: options.fetchImpl }),
      }),
      container: options.container,
      runApi:
        config.serviceId === null
          ? null
          : createComponentRunApi({
              accessToken,
              baseUrl: config.apiBaseUrl,
              context: () => contexts.snapshot(),
              exchangeTicket: () => localPort.renew('EXPIRED'),
              ...(options.fetchImpl === undefined
                ? {}
                : { fetchImpl: options.fetchImpl }),
            }),
      serviceId: config.serviceId ?? '',
      theme,
    });
  }

  const localPort = createLocalPort({
    allowedOrigins: [config.apiOrigin],
    appCode: config.appCode,
    instanceId: config.instanceId,
    onContext: (context) => {
      // 契约已校验形状；落到宿主侧仓库（只作用于下一次运行）
      contexts.update(context);
      options.onContext?.(contexts.current());
    },
    onDestroyed: () => {
      ready = false;
      surface?.destroy();
      surface = undefined;
      mountedSession = -1;
      options.onDestroyed?.();
    },
    onError: (error) => options.onError?.(error),
    onInit: (payload) => {
      ready = true;
      ensureSurface();
      options.onReady?.({
        appCode: config.appCode,
        instanceId: config.instanceId,
        serviceId: payload.serviceId,
      });
    },
    onTheme: (next) => {
      theme = next;
      surface?.setTheme(next);
      options.onTheme?.(next);
    },
    self: identity,
    transport: {
      // 页内传输没有需要释放的资源（DOM、监听器与 Vue 应用由界面层负责）
      destroy: () => undefined,
      post: (message) => {
        hostBridge.receive({
          data: message,
          origin: config.apiOrigin,
          source: identity,
        });
      },
    },
  });

  const hostBridge = createHostBridge({
    allowedOrigins: [config.apiOrigin],
    appCode: config.appCode,
    getAccessToken: options.getAccessToken,
    instanceId: config.instanceId,
    onError: (error) => options.onError?.(error),
    onNavigate: handlers.navigate,
    onRejected: (reason) => options.onRejected?.(reason),
    onReportCreated: handlers.reportCreated,
    serviceId: config.serviceId,
    theme: config.theme,
    transport: {
      destroy: () => localPort.destroy(),
      post: (message) => {
        localPort.receive({
          data: message,
          origin: config.apiOrigin,
          source: identity,
        });
      },
      source: () => identity,
    },
  });

  return {
    credential: () => localPort.credential(),

    destroy(): void {
      if (destroyed) {
        return;
      }
      destroyed = true;
      ready = false;
      // 宿主侧桥 → 传输 → 页内桥 → onDestroyed（界面卸载、令牌清空都在那里）
      hostBridge.destroy();
      surface?.destroy();
      surface = undefined;
    },

    isReady: () => ready,

    notifyReportCreated(event: {
      reportId: string;
      title?: string;
      version: number;
    }): void {
      if (destroyed) {
        return;
      }
      localPort.notifyReportCreated(event);
    },

    requestNavigate(
      route: string,
      params?: Record<string, boolean | number | string>,
    ): void {
      if (destroyed) {
        return;
      }
      // 校验在宿主侧桥里（`createHostEventHandlers` 的登记表）；未登记路由走 onRejected
      localPort.requestNavigate(route, params);
    },

    resetSession(): void {
      if (destroyed) {
        return;
      }
      // 换代并清理宿主侧上下文（旧用户不继承上一个用户的上下文）
      contexts.clear();
      options.onContext?.(null);
      ready = false;
      surfaceSession += 1;
      hostBridge.resetSession();
    },

    start(): void {
      if (destroyed) {
        return;
      }
      // 只在 CREATED 生效：重复打开/重复 start 不会重放 HELLO
      hostBridge.start();
    },

    state: () => hostBridge.state(),

    updateContext(input: unknown): BusinessContext | null {
      // 校验失败即抛错（`createBusinessContextStore` 的口径），不静默丢弃字段
      contexts.update(input);
      const current = contexts.current();
      options.onContext?.(current);
      return current;
    },

    updateTheme(next: Theme): void {
      theme = next;
      surface?.setTheme(next);
    },
  };
}

import type {
  AiChatElement,
  GetAccessToken,
} from '../../element/ai-chat-element';

import { vi } from 'vitest';

import { defineAiChatElement } from '../../element/ai-chat-element';

/**
 * 组件测试夹具（X09）：注册元素、给出宿主侧的换票回调与 fetch 桩。
 *
 * <p>票据是**运行时拼接**的假值：本仓的 secret-scan 会把「token 键 + 引号字面量」视为硬编码凭据，
 * 拼接既避免误报，也不把"看起来像真票据"的字符串留在源码里。
 */
export const TEST_ORIGIN = 'https://platform.example.com';
export const TEST_BASE_URL = `${TEST_ORIGIN}/app-api`;
export const TEST_APP_CODE = 'crm-portal';
export const TEST_TICKET = ['aitkt', 'component', 'fixture'].join('_');

export interface RecordedRequest {
  body: string;
  headers: Record<string, string>;
  method: string;
  url: string;
}

export interface HarnessFetch {
  /** 已记录的请求（按发生顺序） */
  requests: RecordedRequest[];
  /** 注册路由响应：返回 `[status, payload]`；未注册的路径返回 200 + 空信封 */
  respond: (
    matcher: RegExp | string,
    response: () => [number, unknown] | Promise<[number, unknown]>,
  ) => void;
}

export function installFetch(): HarnessFetch {
  const requests: RecordedRequest[] = [];
  const routes: {
    matcher: RegExp | string;
    response: () => [number, unknown] | Promise<[number, unknown]>;
  }[] = [];
  const stub = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = typeof input === 'string' ? input : String(input);
    const headers: Record<string, string> = {};
    new Headers(init?.headers).forEach((value, key) => {
      headers[key.toLowerCase()] = value;
    });
    requests.push({
      body: typeof init?.body === 'string' ? init.body : '',
      headers,
      method: init?.method ?? 'GET',
      url,
    });
    for (const route of routes) {
      const matched =
        typeof route.matcher === 'string'
          ? url.includes(route.matcher)
          : route.matcher.test(url);
      if (matched) {
        const [status, payload] = await route.response();
        return Response.json(payload, {
          headers: { 'content-type': 'application/json' },
          status,
        });
      }
    }
    return Response.json(
      { code: 0, data: { list: [] }, msg: '' },
      {
        headers: { 'content-type': 'application/json' },
        status: 200,
      },
    );
  });
  vi.stubGlobal('fetch', stub);
  return {
    requests,
    respond(matcher, response) {
      routes.push({ matcher, response: async () => response() });
    },
  };
}

/** 等待桥的异步握手（AUTH/INIT 与 Vue 挂载都在微任务里完成）。 */
export async function flushBridge(): Promise<void> {
  await vi.waitFor(() => undefined);
  await new Promise((resolve) => {
    setTimeout(resolve, 0);
  });
}

export interface ComponentHarnessOptions {
  allowOrigins?: string;
  apiBaseUrl?: string;
  appCode?: string;
  instanceId?: string;
  maxHeight?: string;
  mode?: string;
  open?: boolean;
  routes?: Record<string, unknown>;
  serviceId?: string;
  theme?: Record<string, unknown>;
  ticket?: GetAccessToken;
}

export interface ComponentHarness {
  element: AiChatElement;
  getAccessToken: ReturnType<typeof vi.fn<GetAccessToken>>;
  shadow: ShadowRoot;
}

let defined = false;

/**
 * 注册元素（每个测试文件一次）并清空宿主页面。
 *
 * <p>默认把 `fetch` 换成"空会话列表"的桩：用例不因为挂载面板而发出**真实网络请求**
 * （需要断言请求时再用 {@link installFetch} 覆盖）。
 */
export function defineComponent(): void {
  if (!defined) {
    defineAiChatElement();
    defined = true;
  }
  document.body.innerHTML = '';
  vi.stubGlobal(
    'fetch',
    vi.fn(async () =>
      Response.json(
        { code: 0, data: { list: [] }, msg: '' },
        {
          headers: { 'content-type': 'application/json' },
          status: 200,
        },
      ),
    ),
  );
}

export function createTicketProvider(credential?: {
  expiresAt: string;
  token: string;
}): ReturnType<typeof vi.fn<GetAccessToken>> {
  return vi.fn<GetAccessToken>(
    async () =>
      credential ?? { expiresAt: '2026-09-27T10:00:00Z', token: TEST_TICKET },
  );
}

export function createComponent(
  options: ComponentHarnessOptions = {},
): ComponentHarness {
  const getAccessToken = vi.fn<GetAccessToken>(
    options.ticket ??
      (async () => ({
        expiresAt: '2026-09-27T10:00:00Z',
        token: TEST_TICKET,
      })),
  );
  const element = document.createElement('ai-chat-component') as AiChatElement;
  element.setAttribute('app-code', options.appCode ?? TEST_APP_CODE);
  element.setAttribute('api-base-url', options.apiBaseUrl ?? TEST_BASE_URL);
  // 跨源基址必须在允许域里登记（默认给宿主登记的正是平台 Origin）
  element.setAttribute('allow-origins', options.allowOrigins ?? TEST_ORIGIN);
  if (options.instanceId !== undefined) {
    element.setAttribute('instance-id', options.instanceId);
  }
  if (options.maxHeight !== undefined) {
    element.setAttribute('max-height', options.maxHeight);
  }
  if (options.mode !== undefined) {
    element.setAttribute('mode', options.mode);
  }
  if (options.routes !== undefined) {
    element.setAttribute('routes', JSON.stringify(options.routes));
  }
  if (options.serviceId !== undefined) {
    element.setAttribute('service-id', options.serviceId);
  }
  if (options.theme !== undefined) {
    element.setAttribute('theme', JSON.stringify(options.theme));
  }
  (
    element as AiChatElement & { getAccessToken: GetAccessToken }
  ).getAccessToken = getAccessToken;
  document.body.append(element);
  if (options.open === true) {
    element.open();
  }
  const shadow = element.shadowRoot;
  if (shadow === null) {
    throw new Error('组件未建立 Shadow DOM');
  }
  return { element, getAccessToken, shadow };
}

/** 面板内的查询（组件的所有节点都在 Shadow DOM 里）。 */
export function queryShadow<T extends Element = HTMLElement>(
  shadow: ShadowRoot,
  selector: string,
): null | T {
  return shadow.querySelector<T>(selector);
}

export const PANEL = '[data-testid="ai-chat-component-panel"]';
export const STATUS = '[data-testid="ai-chat-component-status"]';
export const SURFACE = '[data-testid="ai-chat-component-surface"]';
export const CLOSE_BUTTON = '[data-testid="ai-chat-component-close"]';
export const CONVERSATION = '[data-testid="ai-conversation"]';
export const COMPOSER_INPUT = '[data-testid="ai-conversation-input"]';
export const COMPOSER_SEND = '[data-testid="ai-conversation-send"]';

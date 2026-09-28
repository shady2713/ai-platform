import type { Theme } from '@vben/ai-contracts';
import type { HostRouteRegistry } from '@vben/ai-embed-sdk';

import { parseThemeTokens } from '@vben/ai-chat-ui';

/**
 * 组件级集成的宿主参数（X09）。
 *
 * <p>与 iframe 路径的 `ChatMountOptions` **同一口径**：应用标识（身份）、换票回调（在元素上以
 * `getAccessToken` 属性给出）、主题 tokens（`@vben/ai-contracts` 的 `Theme`）、导航路由登记表
 * （`HostRouteRegistry`，与 `createHostEventHandlers` 共用）。差别只在**承载方式**：
 * 组件把 ChatUI 直接挂进宿主的 Shadow DOM，不再经过 iframe 与 postMessage。
 *
 * <p>本模块只做"参数是否可用"的判定，全部失败都以稳定错误码返回（不抛异常、不静默兜底）：
 * 缺参数、非法主题、非法路由表、API 基址不属于允许域都必须能被宿主观测到。
 */

export type ComponentChatMode = 'dialog' | 'drawer' | 'inline';

/** 宿主不可用的参数（稳定错误码 + 可读说明，说明里不含凭据）。 */
export interface ComponentConfigFailure {
  errorCode: ComponentConfigErrorCode;
  message: string;
  ok: false;
}

export type ComponentConfigErrorCode =
  | 'ALLOW_ORIGINS_INVALID'
  | 'API_BASE_URL_INVALID'
  | 'API_BASE_URL_REQUIRED'
  | 'API_ORIGIN_NOT_ALLOWED'
  | 'APP_CODE_INVALID'
  | 'APP_CODE_REQUIRED'
  | 'INSTANCE_ID_INVALID'
  | 'MAX_HEIGHT_INVALID'
  | 'MODE_INVALID'
  | 'ROUTES_INVALID'
  | 'SERVICE_ID_INVALID'
  | 'THEME_INVALID';

export interface ComponentChatConfig {
  /** 允许的平台 Origin（精确匹配；用于 API 基址归属判定） */
  allowOrigins: string[];
  /** 平台应用端基址（同源路径或已登记的跨源 Origin） */
  apiBaseUrl: string;
  /** 应用端基址的精确 Origin（桥消息的来源标记；由配置解析得出） */
  apiOrigin: string;
  appCode: string;
  instanceId: string;
  maxHeight: number | undefined;
  mode: ComponentChatMode;
  routes: HostRouteRegistry;
  serviceId: null | string;
  theme: Theme | undefined;
}

/** 元素属性/属性（property）的原始输入；字符串来自 attribute，对象来自 property。 */
export interface ComponentConfigInput {
  allowOrigins?: null | string;
  apiBaseUrl?: null | string;
  appCode?: null | string;
  /** 解析相对基址用的文档基址（默认 `document.baseURI`）；测试可注入 */
  baseUri?: string;
  instanceId?: null | string;
  maxHeight?: null | number | string;
  mode?: null | string;
  routes?: HostRouteRegistry | null | string;
  serviceId?: null | string;
  theme?: null | string | Theme;
}

export interface ComponentConfigOk {
  config: ComponentChatConfig;
  ok: true;
}

export type ComponentConfigResult = ComponentConfigFailure | ComponentConfigOk;

const APP_CODE_MAX_LENGTH = 64;
const INSTANCE_ID_PATTERN = /^[\w-]{1,64}$/u;
const ROUTE_NAME_PATTERN = /^[a-z][a-z0-9_.-]{0,63}$/u;
const SERVICE_ID_PATTERN = /^\S{1,64}$/u;
const PARAM_TYPES = new Set(['boolean', 'number', 'string']);
const MAX_HEIGHT_MIN = 240;
const MAX_HEIGHT_MAX = 4000;
export const DEFAULT_COMPONENT_MODE: ComponentChatMode = 'inline';
const MODES: ComponentChatMode[] = ['dialog', 'drawer', 'inline'];

export const AI_CHAT_ELEMENT_TAG = 'ai-chat-component';

function failure(
  errorCode: ComponentConfigErrorCode,
  message: string,
): ComponentConfigFailure {
  return { errorCode, message, ok: false };
}

function text(value: null | string | undefined): string | undefined {
  if (value === undefined || value === null) {
    return undefined;
  }
  const trimmed = value.trim();
  return trimmed === '' ? undefined : trimmed;
}

function asRecord(value: unknown, label: string): Record<string, unknown> {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) {
    throw new Error(`${label}必须是对象`);
  }
  return value as Record<string, unknown>;
}

/** JSON 文本或已解析对象都接受（attribute 是文本，property 是对象）。 */
function asParsedRecord(
  value: unknown,
  label: string,
): Record<string, unknown> {
  if (typeof value === 'string') {
    try {
      return asRecord(JSON.parse(value) as unknown, label);
    } catch {
      throw new Error(`${label}不是合法 JSON`);
    }
  }
  return asRecord(value, label);
}

/** 生成实例标识：同一页面挂多个组件时用于隔离（不参与身份判定，服务端不看它）。 */
export function generateInstanceId(): string {
  const random =
    globalThis.crypto?.randomUUID?.() ??
    `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 10)}`;
  return `ai-web-${random}`.replaceAll(/[^\w-]/gu, '').slice(0, 64);
}

/**
 * 允许域列表：逗号分隔的**精确 Origin**（不接受通配、路径或带尾斜杠的值）。
 *
 * <p>与后端 `AiEmbedPolicy.allowedOrigins` 同一口径：允许域是配置数据，不是模式匹配。
 */
export function parseAllowOrigins(
  raw: null | string | undefined,
): ComponentConfigFailure | { ok: true; origins: string[] } {
  const value = text(raw);
  if (value === undefined) {
    return { ok: true, origins: [] };
  }
  const origins: string[] = [];
  for (const part of value.split(',')) {
    const candidate = part.trim();
    if (candidate === '') {
      continue;
    }
    let origin: string;
    try {
      const url = new URL(candidate);
      origin = url.origin;
      // 带路径/查询/通配的值不是 Origin 声明：拒绝而不是"取前缀"
      if (url.pathname !== '/' || url.search !== '' || url.hash !== '') {
        return failure(
          'ALLOW_ORIGINS_INVALID',
          `允许域必须是精确 Origin：${candidate}`,
        );
      }
    } catch {
      return failure(
        'ALLOW_ORIGINS_INVALID',
        `允许域不是合法 Origin：${candidate}`,
      );
    }
    if (candidate.includes('*')) {
      return failure('ALLOW_ORIGINS_INVALID', '允许域不接受通配符');
    }
    origins.push(origin);
  }
  return { ok: true, origins: [...new Set(origins)] };
}

export function parseRoutes(
  input: HostRouteRegistry | null | string | undefined,
): ComponentConfigFailure | { ok: true; routes: HostRouteRegistry } {
  if (input === undefined || input === null) {
    return { ok: true, routes: {} };
  }
  let node: Record<string, unknown>;
  try {
    node = asParsedRecord(input, '路由登记表');
  } catch (error) {
    return failure('ROUTES_INVALID', messageOf(error));
  }
  const routes: HostRouteRegistry = {};
  for (const [route, definition] of Object.entries(node)) {
    if (!ROUTE_NAME_PATTERN.test(route)) {
      return failure('ROUTES_INVALID', `路由名不合规：${route}`);
    }
    let paramsNode: unknown;
    try {
      const record = asRecord(definition, `路由 ${route}`);
      paramsNode = record.params;
    } catch (error) {
      return failure('ROUTES_INVALID', messageOf(error));
    }
    if (paramsNode === undefined || paramsNode === null) {
      routes[route] = {};
      continue;
    }
    let paramsRecord: Record<string, unknown>;
    try {
      paramsRecord = asRecord(paramsNode, `路由 ${route} 的参数表`);
    } catch (error) {
      return failure('ROUTES_INVALID', messageOf(error));
    }
    const params: Record<string, 'boolean' | 'number' | 'string'> = {};
    for (const [name, type] of Object.entries(paramsRecord)) {
      if (typeof type !== 'string' || !PARAM_TYPES.has(type)) {
        return failure(
          'ROUTES_INVALID',
          `路由 ${route} 的参数 ${name} 类型不受支持`,
        );
      }
      params[name] = type as 'boolean' | 'number' | 'string';
    }
    routes[route] = { params };
  }
  return { ok: true, routes };
}

function parseTheme(
  input: null | string | Theme | undefined,
): ComponentConfigFailure | { ok: true; theme: Theme | undefined } {
  if (input === undefined || input === null) {
    return { ok: true, theme: undefined };
  }
  try {
    const node = asParsedRecord(input, '主题');
    // 主题走 ChatUI 的同一份解析：未知字段/白名单外字体/非法色值一律拒绝
    return { ok: true, theme: parseThemeTokens(node) };
  } catch (error) {
    return failure('THEME_INVALID', messageOf(error));
  }
}

function parseMaxHeight(
  input: null | number | string | undefined,
): ComponentConfigFailure | { maxHeight: number | undefined; ok: true } {
  if (input === undefined || input === null || input === '') {
    return { ok: true, maxHeight: undefined };
  }
  const value = typeof input === 'number' ? input : Number(input);
  if (
    !Number.isInteger(value) ||
    value < MAX_HEIGHT_MIN ||
    value > MAX_HEIGHT_MAX
  ) {
    return failure(
      'MAX_HEIGHT_INVALID',
      `高度上限必须是 ${MAX_HEIGHT_MIN}..${MAX_HEIGHT_MAX} 的整数（px）`,
    );
  }
  return { ok: true, maxHeight: value };
}

function parseMode(
  raw: null | string | undefined,
): ComponentConfigFailure | { mode: ComponentChatMode; ok: true } {
  const value = text(raw) ?? DEFAULT_COMPONENT_MODE;
  if (!MODES.includes(value as ComponentChatMode)) {
    return failure('MODE_INVALID', `形态只能是 ${MODES.join(' / ')}`);
  }
  return { ok: true, mode: value as ComponentChatMode };
}

function messageOf(error: unknown): string {
  return error instanceof Error ? error.message : '参数不合规';
}

/**
 * 解析宿主参数；任何一项不合规都返回稳定错误码（组件据此给出明确失败，不半启动）。
 *
 * <p>API 基址的归属判定与 iframe 路径的允许域同一语义：**默认不信任跨源基址**——
 * 同源（宿主自己的网关，推荐）直接可用；跨源必须在 `allow-origins` 里登记。
 */
export function parseComponentConfig(
  input: ComponentConfigInput,
): ComponentConfigResult {
  const appCode = text(input.appCode);
  if (appCode === undefined) {
    return failure('APP_CODE_REQUIRED', '缺少应用标识（app-code）');
  }
  if (appCode.length > APP_CODE_MAX_LENGTH) {
    return failure('APP_CODE_INVALID', '应用标识长度不得超过 64');
  }

  const instanceId = text(input.instanceId) ?? generateInstanceId();
  if (!INSTANCE_ID_PATTERN.test(instanceId)) {
    return failure(
      'INSTANCE_ID_INVALID',
      '实例标识只允许字母数字与 -_，长度 1..64',
    );
  }

  const origins = parseAllowOrigins(input.allowOrigins);
  if (!origins.ok) {
    return origins;
  }

  const rawBase = text(input.apiBaseUrl);
  if (rawBase === undefined) {
    return failure('API_BASE_URL_REQUIRED', '缺少应用端基址（api-base-url）');
  }
  const baseUri =
    input.baseUri ?? globalThis.document?.baseURI ?? 'http://localhost/';
  let url: URL;
  try {
    url = new URL(rawBase, baseUri);
  } catch {
    return failure('API_BASE_URL_INVALID', '应用端基址不是合法地址');
  }
  if (url.protocol !== 'http:' && url.protocol !== 'https:') {
    return failure(
      'API_BASE_URL_INVALID',
      '应用端基址只接受 http/https（不接受脚本或数据地址）',
    );
  }
  const baseDocumentOrigin = new URL(baseUri, 'http://localhost/').origin;
  if (
    url.origin !== baseDocumentOrigin &&
    !origins.origins.includes(url.origin)
  ) {
    return failure(
      'API_ORIGIN_NOT_ALLOWED',
      `跨源基址 ${url.origin} 未登记在 allow-origins 中（同源基址无需登记）`,
    );
  }

  const mode = parseMode(input.mode);
  if (!mode.ok) {
    return mode;
  }
  const maxHeight = parseMaxHeight(input.maxHeight);
  if (!maxHeight.ok) {
    return maxHeight;
  }
  const routes = parseRoutes(input.routes);
  if (!routes.ok) {
    return routes;
  }
  const theme = parseTheme(input.theme);
  if (!theme.ok) {
    return theme;
  }

  const serviceId = text(input.serviceId);
  if (serviceId !== undefined && !SERVICE_ID_PATTERN.test(serviceId)) {
    return failure('SERVICE_ID_INVALID', '服务标识必须是 1..64 的非空白字符');
  }

  return {
    config: {
      allowOrigins: origins.origins,
      apiBaseUrl: rawBase,
      apiOrigin: url.origin,
      appCode,
      instanceId,
      maxHeight: maxHeight.maxHeight,
      mode: mode.mode,
      routes: routes.routes,
      serviceId: serviceId ?? null,
      theme: theme.theme,
    },
    ok: true,
  };
}

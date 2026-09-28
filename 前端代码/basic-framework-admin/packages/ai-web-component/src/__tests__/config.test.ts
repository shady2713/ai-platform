import { describe, expect, it } from 'vitest';

import {
  AI_CHAT_ELEMENT_TAG,
  generateInstanceId,
  parseAllowOrigins,
  parseComponentConfig,
  parseRoutes,
} from '../config';

const BASE_URI = 'https://host.example.com/crm/order';

function expectFailure(result: ReturnType<typeof parseComponentConfig>): {
  errorCode: string;
  message: string;
} {
  if (result.ok) {
    throw new Error('期望解析失败，但得到成功结果');
  }
  return result;
}

describe('组件宿主参数的解析（X09）', () => {
  it('同源基址可用：默认形态 inline、实例标识自动生成、无路由无主题', () => {
    const result = parseComponentConfig({
      apiBaseUrl: '/app-api',
      appCode: 'crm-portal',
      baseUri: BASE_URI,
    });
    expect(result.ok).toBe(true);
    if (!result.ok) return;
    expect(result.config.apiBaseUrl).toBe('/app-api');
    expect(result.config.apiOrigin).toBe('https://host.example.com');
    expect(result.config.mode).toBe('inline');
    expect(result.config.serviceId).toBeNull();
    expect(result.config.theme).toBeUndefined();
    expect(result.config.routes).toEqual({});
    expect(result.config.maxHeight).toBeUndefined();
    expect(result.config.instanceId).toMatch(/^ai-web-[\w-]{1,64}$/u);
    expect(AI_CHAT_ELEMENT_TAG).toBe('ai-chat-component');
  });

  it('跨源基址必须登记在 allow-origins 中（默认不信任未登记来源）', () => {
    const denied = parseComponentConfig({
      apiBaseUrl: 'https://platform.example.com/app-api',
      appCode: 'crm-portal',
      baseUri: BASE_URI,
    });
    expect(expectFailure(denied).errorCode).toBe('API_ORIGIN_NOT_ALLOWED');

    const allowed = parseComponentConfig({
      allowOrigins: 'https://platform.example.com',
      apiBaseUrl: 'https://platform.example.com/app-api',
      appCode: 'crm-portal',
      baseUri: BASE_URI,
    });
    expect(allowed.ok).toBe(true);
    if (!allowed.ok) return;
    expect(allowed.config.apiOrigin).toBe('https://platform.example.com');
    expect(allowed.config.allowOrigins).toEqual([
      'https://platform.example.com',
    ]);
  });

  it('缺失参数与非法取值都返回稳定错误码（不静默兜底）', () => {
    const cases: [Record<string, unknown>, string][] = [
      [{ apiBaseUrl: '/app-api' }, 'APP_CODE_REQUIRED'],
      [{ appCode: 'x'.repeat(65), apiBaseUrl: '/app-api' }, 'APP_CODE_INVALID'],
      [{ appCode: 'crm' }, 'API_BASE_URL_REQUIRED'],
      [
        { appCode: 'crm', apiBaseUrl: 'ftp://host/app' },
        'API_BASE_URL_INVALID',
      ],
      [
        {
          appCode: 'crm',
          apiBaseUrl: '/app-api',
          instanceId: 'bad id!',
        },
        'INSTANCE_ID_INVALID',
      ],
      [
        { appCode: 'crm', apiBaseUrl: '/app-api', mode: 'popup' },
        'MODE_INVALID',
      ],
      [
        { appCode: 'crm', apiBaseUrl: '/app-api', maxHeight: '12' },
        'MAX_HEIGHT_INVALID',
      ],
      [
        { appCode: 'crm', apiBaseUrl: '/app-api', maxHeight: 99_999 },
        'MAX_HEIGHT_INVALID',
      ],
      [
        { appCode: 'crm', apiBaseUrl: '/app-api', serviceId: 'svc 1' },
        'SERVICE_ID_INVALID',
      ],
      [
        { appCode: 'crm', apiBaseUrl: '/app-api', theme: '{"colour":"#fff"}' },
        'THEME_INVALID',
      ],
      [
        { appCode: 'crm', apiBaseUrl: '/app-api', theme: '{oops' },
        'THEME_INVALID',
      ],
      [
        {
          appCode: 'crm',
          apiBaseUrl: '/app-api',
          theme: {
            colorScheme: 'light',
            fontFamily: '"Remote Font", cursive',
            primaryColor: '#1677ff',
            radius: 6,
          },
        },
        'THEME_INVALID',
      ],
      [
        { appCode: 'crm', apiBaseUrl: '/app-api', routes: 'not json' },
        'ROUTES_INVALID',
      ],
      [
        {
          appCode: 'crm',
          apiBaseUrl: '/app-api',
          routes: { Evil_Route: { params: {} } },
        },
        'ROUTES_INVALID',
      ],
      [
        {
          appCode: 'crm',
          apiBaseUrl: '/app-api',
          routes: { 'order.detail': { params: { id: 'url' } } },
        },
        'ROUTES_INVALID',
      ],
      [
        {
          appCode: 'crm',
          apiBaseUrl: '/app-api',
          routes: { 'order.detail': 'nope' },
        },
        'ROUTES_INVALID',
      ],
      [
        { appCode: 'crm', apiBaseUrl: '/app-api', allowOrigins: '*' },
        'ALLOW_ORIGINS_INVALID',
      ],
      [
        {
          appCode: 'crm',
          apiBaseUrl: '/app-api',
          allowOrigins: 'https://a.example.com/path',
        },
        'ALLOW_ORIGINS_INVALID',
      ],
      [
        { appCode: 'crm', apiBaseUrl: '/app-api', allowOrigins: 'not a url' },
        'ALLOW_ORIGINS_INVALID',
      ],
    ];
    for (const [input, errorCode] of cases) {
      const result = parseComponentConfig({
        baseUri: BASE_URI,
        ...input,
      });
      expect(expectFailure(result).errorCode, JSON.stringify(input)).toBe(
        errorCode,
      );
    }
  });

  it('合法主题与路由登记表被接受，并规范化（去重/补空参数表）', () => {
    const result = parseComponentConfig({
      apiBaseUrl: 'https://platform.example.com/app-api',
      allowOrigins:
        'https://platform.example.com, https://platform.example.com',
      appCode: 'crm-portal',
      baseUri: BASE_URI,
      maxHeight: '480',
      mode: 'dialog',
      routes: {
        'order.detail': { params: { id: 'string', pinned: 'boolean' } },
        'report.list': {},
      },
      serviceId: 'svc_1',
      theme: {
        fontFamily:
          'system-ui, -apple-system, "PingFang SC", "Microsoft YaHei", sans-serif',
        primaryColor: '#1677ff',
        radius: 6,
      },
    });
    expect(result.ok).toBe(true);
    if (!result.ok) return;
    expect(result.config.allowOrigins).toEqual([
      'https://platform.example.com',
    ]);
    expect(result.config.maxHeight).toBe(480);
    expect(result.config.mode).toBe('dialog');
    expect(result.config.serviceId).toBe('svc_1');
    expect(result.config.theme?.primaryColor).toBe('#1677ff');
    expect(result.config.routes).toEqual({
      'order.detail': { params: { id: 'string', pinned: 'boolean' } },
      'report.list': {},
    });
  });

  it('允许域解析：（部分）空值/多个值与非法值', () => {
    expect(parseAllowOrigins(undefined)).toEqual({ ok: true, origins: [] });
    expect(parseAllowOrigins(' , ')).toEqual({ ok: true, origins: [] });
    const many = parseAllowOrigins(
      'https://a.example.com, https://b.example.com, ',
    );
    expect(many).toEqual({
      ok: true,
      origins: ['https://a.example.com', 'https://b.example.com'],
    });
    expect(parseAllowOrigins('*.example.com').ok).toBe(false);
  });

  it('路由登记表解析：字符串与对象形式等价，非法即失败', () => {
    const fromText = parseRoutes('{"order.detail":{"params":{"id":"string"}}}');
    expect(fromText).toEqual({
      ok: true,
      routes: { 'order.detail': { params: { id: 'string' } } },
    });
    expect(parseRoutes(undefined)).toEqual({ ok: true, routes: {} });
    expect(parseRoutes(null)).toEqual({ ok: true, routes: {} });
    expect(
      parseRoutes({ 'order.detail': { params: { id: 'string' } } }),
    ).toEqual(fromText);
    expect(parseRoutes('[1,2]').ok).toBe(false);
    expect(
      parseRoutes({ 'order.detail': { params: ['id'] } } as never).ok,
    ).toBe(false);
  });

  it('实例标识生成：形状固定且互不相同', () => {
    const first = generateInstanceId();
    const second = generateInstanceId();
    expect(first).toMatch(/^ai-web-[\w-]{1,64}$/u);
    expect(first).not.toBe(second);
  });
});

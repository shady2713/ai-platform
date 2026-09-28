import { describe, expect, it, vi } from 'vitest';

import { createHandler, isConfigured, readConfig } from './server.mjs';

const CONFIG = {
  appCode: 'crm-portal',
  appSecret: 'sample-secret',
  hostOrigin: 'http://localhost:5180',
  platformBase: 'http://localhost:48080',
};

interface FakeResponse {
  body: unknown;
  chunks: unknown[];
  headers: Record<string, string>;
  status: number;
}

function invoke(
  handler: (request: unknown, response: unknown) => Promise<void>,
  request: {
    body?: unknown;
    headers?: Record<string, string>;
    method?: string;
    url: string;
  },
): Promise<FakeResponse> {
  const captured: FakeResponse = {
    body: undefined,
    chunks: [],
    headers: {},
    status: 0,
  };
  const response = {
    end: (body: unknown) => {
      captured.body = body;
    },
    write: (chunk: unknown) => {
      captured.chunks.push(chunk);
    },
    writeHead: (status: number, headers: Record<string, string>) => {
      captured.status = status;
      captured.headers = headers;
    },
  };
  return new Promise((resolve, reject) => {
    void handler(
      {
        body: request.body,
        headers: request.headers ?? {},
        method: request.method ?? 'GET',
        url: request.url,
      },
      response,
    ).then(() => resolve(captured), reject);
  });
}

/** 上游响应桩：只需要处理器用到的三个字段。 */
function upstreamResponse({
  body = ['{"code":0,"data":{}}'],
  headers = { 'content-type': 'application/json' },
  status = 200,
}: {
  body?: string[];
  headers?: Record<string, string>;
  status?: number;
} = {}) {
  return { body, headers: new Headers(headers), status };
}

describe('hTML 宿主示例的后端约束（C10）', () => {
  it('未配置应用凭据时直接失败，不提供匿名换票', async () => {
    expect(isConfigured(readConfig({}))).toBe(false);
    const handler = createHandler({ config: readConfig({}) });

    const response = await invoke(handler, {
      url: '/your-backend/ai-ticket',
      method: 'POST',
    });

    expect(response.status).toBe(503);
    expect(String(response.body)).toContain('未配置应用凭据');
  });

  it('只接受配置的宿主 Origin；其他 Origin 403 且不回通配 CORS', async () => {
    const handler = createHandler({ config: CONFIG });

    const evil = await invoke(handler, {
      headers: { origin: 'http://evil.example.com' },
      method: 'POST',
      url: '/your-backend/ai-ticket',
    });
    expect(evil.status).toBe(403);
    expect(evil.headers['access-control-allow-origin']).toBeUndefined();

    const fetchImpl = vi.fn(async () => ({
      json: async () => ({
        data: { expiresAt: '2026-09-25T10:00:00Z', token: 'aitk_short' },
      }),
      ok: true,
      status: 200,
    }));
    const allowed = await invoke(
      createHandler({ config: CONFIG, fetch: fetchImpl }),
      {
        headers: { origin: CONFIG.hostOrigin },
        method: 'POST',
        url: '/your-backend/ai-ticket',
      },
    );
    expect(allowed.status).toBe(200);
    // 平台基址来自服务端配置，客户端不能指定
    expect(fetchImpl).toHaveBeenCalledWith(
      `${CONFIG.platformBase}/app-api/ai/auth/ticket`,
      expect.objectContaining({ method: 'POST' }),
    );
    // 响应里不含凭据
    expect(String(allowed.body)).not.toContain(CONFIG.appSecret);
  });

  it('sDK 产物只提供版本化文件名，且不暴露目录', async () => {
    const handler = createHandler({ config: CONFIG });

    const traversal = await invoke(handler, {
      url: '/sdk/..%2F..%2Fpackage.json',
    });
    expect(traversal.status).toBe(404);

    const unknown = await invoke(handler, { url: '/sdk/index.js' });
    expect(unknown.status).toBe(404);

    const versioned = await invoke(handler, {
      url: '/sdk/ai-embed-sdk-5.6.0.js',
    });
    // 产物已构建时应为 200 + immutable；未构建时为 404（两种情况都不得返回目录内容）
    expect([200, 404]).toContain(versioned.status);
    if (versioned.status === 200) {
      expect(versioned.headers['cache-control']).toContain('immutable');
    }
  });

  it('宿主页面与未登记路径的响应明确', async () => {
    const handler = createHandler({ config: CONFIG });

    const page = await invoke(handler, { url: '/' });
    const hostScript = await invoke(handler, { url: '/host.js' });
    const hostModel = await invoke(handler, { url: '/host-model.mjs' });
    const missing = await invoke(handler, { url: '/not-found' });

    // 状态码必须断言：示例页的模块导入路径错了会 404，只断言响应头会漏掉这类缺陷
    expect(page.status).toBe(200);
    expect(hostScript.status).toBe(200);
    expect(hostModel.status).toBe(200);
    expect(hostScript.headers['x-content-type-options']).toBe('nosniff');
    expect(missing.status).toBe(404);
  });
});

describe('x09 组件路径：产物、同源网关与红线', () => {
  it('组件示例页面与脚本可获取（宿主侧逻辑不是内联在页面里）', async () => {
    const handler = createHandler({ config: CONFIG });

    const page = await invoke(handler, { url: '/component.html' });
    const componentScript = await invoke(handler, { url: '/component.js' });
    const model = await invoke(handler, { url: '/component-model.mjs' });
    const hostModel = await invoke(handler, { url: '/host-model.mjs' });

    for (const response of [page, componentScript, model, hostModel]) {
      expect(response.status).toBe(200);
    }
    expect(page.headers['content-type']).toContain('text/html');
    expect(String(componentScript.body)).toContain('ai-chat-component');
    // 组件示例只引用自托管产物（无公共 CDN）
    expect(String(componentScript.body)).not.toMatch(
      /https?:\/\/(?:unpkg|cdn|jsdelivr|esm\.sh)/u,
    );
  });

  it('组件产物只提供版本化命名（入口/CSS/分包），未登记名与穿越一律 404', async () => {
    const handler = createHandler({ config: CONFIG });

    const traversal = await invoke(handler, {
      url: '/component/..%2F..%2Fpackage.json',
    });
    const unknown = await invoke(handler, { url: '/component/index.js' });
    const floating = await invoke(handler, {
      url: '/component/ai-web-component-latest.js',
    });
    expect([traversal.status, unknown.status, floating.status]).toEqual([
      404, 404, 404,
    ]);

    const entry = await invoke(handler, {
      url: '/component/ai-web-component-5.6.0.js',
    });
    const style = await invoke(handler, {
      url: '/component/ai-web-component-5.6.0.css',
    });
    // 产物已构建时应为 200 + immutable；未构建时 404（两种情况都不暴露目录内容）
    for (const response of [entry, style]) {
      expect([200, 404]).toContain(response.status);
      if (response.status === 200) {
        expect(response.headers['cache-control']).toContain('immutable');
      }
    }
    if (style.status === 200) {
      expect(style.headers['content-type']).toContain('text/css');
    }
  });

  it('同源网关：只转发白名单前缀到配置的平台基址，Cookie 与自定义头不进上游', async () => {
    const fetchImpl = vi.fn(async () => upstreamResponse());
    const handler = createHandler({ config: CONFIG, fetch: fetchImpl });

    const response = await invoke(handler, {
      body: '{"message":"hi"}',
      headers: {
        authorization: 'Bearer short-ticket',
        cookie: 'session=should-not-leak',
        'idempotency-key': 'idem_1234567890',
        'x-custom': 'drop-me',
      },
      method: 'POST',
      url: '/your-backend/app-api/ai/run/accept',
    });

    expect(response.status).toBe(200);
    expect(fetchImpl).toHaveBeenCalledTimes(1);
    const [target, init] = fetchImpl.mock.calls[0] as [
      URL,
      { body?: unknown; headers: Record<string, string>; method: string },
    ];
    expect(String(target)).toBe(`${CONFIG.platformBase}/app-api/ai/run/accept`);
    expect(init.method).toBe('POST');
    expect(init.headers.authorization).toBe('Bearer short-ticket');
    expect(init.headers['idempotency-key']).toBe('idem_1234567890');
    expect(init.headers.cookie).toBeUndefined();
    expect(init.headers['x-custom']).toBeUndefined();
    expect(init.body).toBe('{"message":"hi"}');
    // 网关不缓存响应（事件流与票据都不该被缓存）
    expect(response.headers['cache-control']).toBe('no-store');
  });

  it('同源网关：事件流逐块回传（SSE 不被攒成一坨）', async () => {
    const fetchImpl = vi.fn(async () =>
      upstreamResponse({
        body: ['data: {"seq":1}\n\n', 'data: {"seq":2}\n\n'],
        headers: { 'content-type': 'text/event-stream' },
      }),
    );
    const handler = createHandler({ config: CONFIG, fetch: fetchImpl });
    const response = await invoke(handler, {
      url: '/your-backend/app-api/ai/run/events?runId=1',
    });
    expect(response.chunks).toEqual([
      'data: {"seq":1}\n\n',
      'data: {"seq":2}\n\n',
    ]);
    expect(response.headers['content-type']).toBe('text/event-stream');
  });

  it('同源网关的红线：方法白名单、来源、路径形状与上游失败', async () => {
    const fetchImpl = vi.fn(async () => upstreamResponse());
    const handler = createHandler({ config: CONFIG, fetch: fetchImpl });

    const deleted = await invoke(handler, {
      method: 'DELETE',
      url: '/your-backend/app-api/ai/run/accept',
    });
    expect(deleted.status).toBe(405);

    const evilOrigin = await invoke(handler, {
      headers: { origin: 'http://evil.example.com' },
      method: 'POST',
      url: '/your-backend/app-api/ai/run/accept',
    });
    expect(evilOrigin.status).toBe(403);

    // 路径形状：目录穿越、绝对路径、带协议的目标、超长路径一律拒绝
    for (const url of [
      '/your-backend/app-api/../../etc/passwd',
      '/your-backend/app-api//evil.example.com/app-api',
      '/your-backend/app-api/https://evil.example.com/app-api',
      `/your-backend/app-api/${'a'.repeat(600)}`,
    ]) {
      const rejected = await invoke(handler, { method: 'GET', url });
      expect([400, 404]).toContain(rejected.status);
    }
    expect(fetchImpl).not.toHaveBeenCalled();

    // 上游不可达：502（不回显内部错误细节）
    const unreachable = createHandler({
      config: CONFIG,
      fetch: vi.fn(async () => {
        throw new Error('ECONNREFUSED 127.0.0.1:48080');
      }),
    });
    const failed = await invoke(unreachable, {
      method: 'GET',
      url: '/your-backend/app-api/ai/run/events?runId=1',
    });
    expect(failed.status).toBe(502);
    expect(String(failed.body)).not.toContain('127.0.0.1');
  });
});

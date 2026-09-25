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
  headers: Record<string, string>;
  status: number;
}

function invoke(
  handler: (request: unknown, response: unknown) => Promise<void>,
  request: { headers?: Record<string, string>; method?: string; url: string },
): Promise<FakeResponse> {
  const captured: FakeResponse = { body: undefined, headers: {}, status: 0 };
  const response = {
    end: (body: unknown) => {
      captured.body = body;
    },
    writeHead: (status: number, headers: Record<string, string>) => {
      captured.status = status;
      captured.headers = headers;
    },
  };
  return new Promise((resolve, reject) => {
    void handler(
      {
        headers: request.headers ?? {},
        method: request.method ?? 'GET',
        url: request.url,
      },
      response,
    ).then(() => resolve(captured), reject);
  });
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
    const missing = await invoke(handler, { url: '/not-found' });

    expect(page.status).toBe(200);
    expect(hostScript.headers['x-content-type-options']).toBe('nosniff');
    expect(missing.status).toBe(404);
  });
});

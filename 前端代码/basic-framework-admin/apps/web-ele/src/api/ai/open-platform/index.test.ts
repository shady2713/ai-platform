import { beforeEach, describe, expect, it, vi } from 'vitest';

import {
  AI_OPEN_API_CATALOG,
  callDebugEndpoint,
  findEndpoint,
  issueDebugTicket,
} from './index';

const requestClient = vi.hoisted(() => ({
  post: vi.fn(),
  request: vi.fn(),
}));

const constructed = vi.hoisted(() => ({ options: [] as unknown[] }));

vi.mock('@vben/request', () => ({
  RequestClient: class {
    post = requestClient.post;
    request = requestClient.request;
    constructor(options: unknown) {
      constructed.options.push(options);
    }
  },
}));

/** O08 开放平台 API 契约：目录只含已发布接口，调试走应用端通道且不回显凭据。 */
describe('ai open platform api', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('目录只登记已发布的开放端点且不含管理端路径', () => {
    expect(AI_OPEN_API_CATALOG.length).toBeGreaterThan(0);
    for (const endpoint of AI_OPEN_API_CATALOG) {
      expect(endpoint.path).toMatch(/^\/app-api\/ai\//);
      expect(endpoint.path).not.toContain('/admin-api');
      expect(endpoint.summary.length).toBeGreaterThan(0);
      expect(endpoint.auth.length).toBeGreaterThan(0);
    }
    // 未发布的接口查不到（不提供调试入口）
    expect(findEndpoint('not-published')).toBeUndefined();
    expect(findEndpoint('run-accept')?.method).toBe('POST');
  });

  it('调试通道使用应用端前缀且不携带管理端凭据', async () => {
    requestClient.post.mockResolvedValue({ token: 'aitkt_once' });

    await issueDebugTicket('crm-portal', 'aiapp_secret', 'USER', 'u-1001');

    expect(constructed.options).toContainEqual({
      baseURL: '/app-api',
      withCredentials: false,
    });
    expect(requestClient.post).toHaveBeenCalledWith('/ai/auth/ticket', {
      appCode: 'crm-portal',
      appSecret: 'aiapp_secret',
      externalUserId: 'u-1001',
      subjectType: 'USER',
    });
  });

  it('调用开放端点只带本次票据且不把票据写进 URL', async () => {
    requestClient.request.mockResolvedValue({ code: 0, data: true, msg: '' });

    const result = await callDebugEndpoint(
      'aitkt_once',
      'GET',
      '/app-api/ai/task/progress',
    );

    expect(requestClient.request).toHaveBeenCalledWith('/ai/task/progress', {
      headers: { Authorization: 'Bearer aitkt_once' },
      method: 'GET',
    });
    expect(result.status).toBe(200);
    expect(result.body).toContain('"code":0');
  });
});

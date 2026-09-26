import { AiChatApiError } from '@vben/ai-embed-sdk';

import { describe, expect, it, vi } from 'vitest';

import { createConversationApi } from '../conversation-api';

function jsonResponse(body: unknown, status = 200): Response {
  return Response.json(body, {
    headers: { 'Content-Type': 'application/json' },
    status,
  });
}

function apiWith(
  fetchImpl: typeof fetch,
  ticket: null | string = 'aitkt_memory',
) {
  return createConversationApi({
    baseUrl: 'https://host/app-api/',
    fetchImpl,
    ticketStorage: { getItem: () => ticket },
  });
}

/** 稳定错误的可读形态：`AiChatApiError` 由 SDK 以值导出，测试侧按结构断言。 */
interface StableError {
  code: string;
  message: string;
  status: number;
}

/** 等待调用失败，并断言失败是 `AiChatApiError`，返回其稳定字段。 */
async function stableFailure(call: Promise<unknown>): Promise<StableError> {
  const failure = await call.then(
    () => {
      throw new Error('预期失败，但调用成功');
    },
    (error: unknown) => error,
  );
  expect(failure).toBeInstanceOf(AiChatApiError);
  return failure as StableError;
}

describe('会话接口端口（应用端）', () => {
  it('列表/新建/重命名/删除走应用端路径，票据只在请求头', async () => {
    const calls: { init: RequestInit; url: string }[] = [];
    const api = apiWith((async (input: string | URL, init?: RequestInit) => {
      calls.push({ init: init ?? {}, url: String(input) });
      if (String(input).endsWith('/page')) {
        return jsonResponse({
          code: 0,
          data: {
            list: [{ conversationKey: 'conv_1', id: 1, title: '会话一' }],
          },
        });
      }
      if (String(input).endsWith('/create')) {
        return jsonResponse({
          code: 0,
          data: { conversationKey: 'conv_2', id: 2, title: '新会话' },
        });
      }
      return jsonResponse({ code: 0, data: true });
    }) as unknown as typeof fetch);

    await expect(api.list()).resolves.toEqual([
      { conversationKey: 'conv_1', id: 1, title: '会话一' },
    ]);
    await expect(api.create('新会话')).resolves.toMatchObject({
      id: 2,
      title: '新会话',
    });
    await api.rename(2, '改名后');
    await api.remove(2);

    expect(calls.map((call) => call.url)).toEqual([
      'https://host/app-api/ai/conversation/page',
      'https://host/app-api/ai/conversation/create',
      'https://host/app-api/ai/conversation/rename',
      'https://host/app-api/ai/conversation/delete',
    ]);
    // 票据只进请求头；URL 与请求体里不出现
    expect(calls[0]?.url).not.toContain('aitkt_');
    expect(new Headers(calls[0]?.init.headers).get('Authorization')).toBe(
      'Bearer aitkt_memory',
    );
    expect(JSON.parse(String(calls[2]?.init.body))).toEqual({
      id: 2,
      title: '改名后',
    });
    expect(String(calls[2]?.init.body)).not.toContain('aitkt_');
  });

  it('无票据时不发送 Authorization 头', async () => {
    const fetchImpl = vi.fn(async () =>
      jsonResponse({ code: 0, data: { list: [] } }),
    );
    const api = apiWith(fetchImpl as unknown as typeof fetch, null);

    await api.list();

    const [, init] = (fetchImpl as unknown as ReturnType<typeof vi.fn>).mock
      .calls[0] as [string, RequestInit];
    expect(new Headers(init.headers).get('Authorization')).toBeNull();
  });

  it('业务失败/非 JSON 响应/缺 data 都抛稳定错误', async () => {
    const failing = apiWith((async () =>
      jsonResponse(
        { code: 1_003_003_002, msg: '会话不存在' },
        404,
      )) as unknown as typeof fetch);
    await expect(failing.list()).rejects.toBeInstanceOf(AiChatApiError);
    await expect(failing.list()).rejects.toMatchObject({
      code: '1003003002',
      status: 404,
    });

    const gateway = apiWith(
      (async () =>
        new Response('bad gateway', {
          status: 502,
        })) as unknown as typeof fetch,
    );
    await expect(gateway.list()).rejects.toMatchObject({ code: 'HTTP_502' });

    const empty = apiWith((async () =>
      jsonResponse({ code: 0 })) as unknown as typeof fetch);
    await expect(empty.list()).rejects.toMatchObject({ code: 'EMPTY_DATA' });

    const malformed = apiWith((async () =>
      jsonResponse({ data: { list: [] } })) as unknown as typeof fetch);
    await expect(malformed.list()).rejects.toMatchObject({
      code: 'MALFORMED_RESPONSE',
    });
  });

  it('传输层失败给出稳定原因码（不是原始 TypeError），消息不回显原始异常', async () => {
    const offline = apiWith((async () => {
      throw new TypeError('Failed to fetch');
    }) as unknown as typeof fetch);

    const notice = await stableFailure(offline.list());
    expect(notice.code).toBe('NETWORK_UNREACHABLE');
    // 传输层失败没有 HTTP 状态码：0 表示"没有响应"
    expect(notice.status).toBe(0);
    // 可读且可定位：说明连不上哪个基址
    expect(notice.message).toContain('https://host/app-api');
    expect(notice.message).not.toContain('Failed to fetch');
  });

  it('响应声明 JSON 但正文非法时抛稳定错误，不回显正文', async () => {
    const brokenJson = apiWith(
      (async () =>
        new Response('<html><body>网关错误</body></html>', {
          headers: { 'Content-Type': 'application/json' },
          status: 200,
        })) as unknown as typeof fetch,
    );

    const notice = await stableFailure(brokenJson.list());
    expect(notice.code).toBe('MALFORMED_RESPONSE');
    expect(notice.status).toBe(200);
    expect(notice.message).not.toContain('<html>');
  });

  it('错误页正文是 HTML 时只暴露状态码，绝不把页面正文当消息展示', async () => {
    const htmlError = apiWith(
      (async () =>
        new Response('<html><body>502 Bad Gateway</body></html>', {
          headers: { 'Content-Type': 'text/html' },
          status: 502,
        })) as unknown as typeof fetch,
    );

    const notice = await stableFailure(htmlError.list());
    expect(notice.code).toBe('HTTP_502');
    expect(notice.message).not.toContain('<html>');
    expect(notice.message).not.toContain('Bad Gateway');
  });

  it('已经是稳定错误时原样透传，不二次包装', async () => {
    const stable = new AiChatApiError(0, 'INVALID_REQUEST', '本地校验失败');
    const passthrough = apiWith((async () => {
      throw stable;
    }) as unknown as typeof fetch);

    const failure = await passthrough.list().catch((error: unknown) => error);
    expect(failure).toBe(stable);
  });
});

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
});

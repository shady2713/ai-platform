import type { BusinessContext } from '@vben/ai-contracts';

import { afterEach, describe, expect, it, vi } from 'vitest';

import {
  createComponentConversationApi,
  createComponentRunApi,
  numericId,
} from '../ai-ports';

/**
 * 组件端口（X09）：票据只进请求头、信封错误归一、受理携带上下文快照。
 *
 * <p>网络是外部边界：这里注入 fetch 桩，断言的是**请求形状与错误通道**，
 * 不声称已与真实后端联调。
 */
const BASE_URL = 'https://platform.example.com/app-api';
const TOKEN = ['aitkt', 'ports'].join('_');

interface Recorded {
  body: string;
  headers: Record<string, string>;
  method: string;
  url: string;
}

function stubFetch(
  handler: (url: string, init: RequestInit | undefined) => Response,
): Recorded[] {
  const recorded: Recorded[] = [];
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = typeof input === 'string' ? input : String(input);
      const headers: Record<string, string> = {};
      new Headers(init?.headers).forEach((value, key) => {
        headers[key.toLowerCase()] = value;
      });
      recorded.push({
        body: typeof init?.body === 'string' ? init.body : '',
        headers,
        method: init?.method ?? 'GET',
        url,
      });
      return handler(url, init);
    }),
  );
  return recorded;
}

function json(payload: unknown, status = 200): Response {
  return Response.json(payload, {
    headers: { 'content-type': 'application/json' },
    status,
  });
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('会话端口（X09）', () => {
  it('列表/新建/重命名/删除：调用应用端契约路径，票据只进 Authorization 头', async () => {
    const requests = stubFetch((url) =>
      json({
        code: 0,
        data: url.includes('/page')
          ? {
              list: [
                {
                  conversationKey: 'conv_1',
                  id: 1,
                  title: '订单问题',
                  updateTime: '2026-09-27T10:00:00Z',
                },
              ],
            }
          : { conversationKey: 'conv_2', id: 2, title: '新会话' },
        msg: '',
      }),
    );
    const api = createComponentConversationApi({
      accessToken: () => TOKEN,
      baseUrl: `${BASE_URL}/`,
    });

    expect(await api.list()).toEqual([
      {
        conversationKey: 'conv_1',
        id: 1,
        title: '订单问题',
        updateTime: '2026-09-27T10:00:00Z',
      },
    ]);
    expect(await api.create('新会话')).toEqual({
      conversationKey: 'conv_2',
      id: 2,
      title: '新会话',
    });
    await api.rename(2, '改名');
    await api.remove(2);

    expect(requests.map((request) => request.url)).toEqual([
      `${BASE_URL}/ai/conversation/page`,
      `${BASE_URL}/ai/conversation/create`,
      `${BASE_URL}/ai/conversation/rename`,
      `${BASE_URL}/ai/conversation/delete`,
    ]);
    for (const request of requests) {
      expect(request.headers.authorization).toBe(`Bearer ${TOKEN}`);
      expect(request.url).not.toContain(TOKEN);
      expect(request.body).not.toContain(TOKEN);
    }
    expect(requests[0]?.method).toBe('GET');
    expect(requests[1]?.body).toBe(JSON.stringify({ title: '新会话' }));
    expect(requests[3]?.body).toBe(JSON.stringify({ id: 2 }));
  });

  it('无票据时不带 Authorization 头（不打无票请求的伪装）', async () => {
    const requests = stubFetch(() => json({ code: 0, data: { list: [] } }));
    const api = createComponentConversationApi({
      accessToken: () => null,
      baseUrl: BASE_URL,
    });
    await api.list();
    expect(requests[0]?.headers.authorization).toBeUndefined();
  });

  it('业务失败与信封缺失：抛稳定错误码（不把失败当成功）', async () => {
    stubFetch((url) =>
      url.includes('/page')
        ? json({ code: 1_003_004_001, data: null, msg: '无权限' }, 200)
        : json({ msg: 'not an envelope' }, 200),
    );
    const api = createComponentConversationApi({
      accessToken: () => TOKEN,
      baseUrl: BASE_URL,
    });
    await expect(api.list()).rejects.toMatchObject({
      code: '1003004001',
      status: 200,
    });
    await expect(api.create()).rejects.toMatchObject({
      code: 'MALFORMED_RESPONSE',
    });
  });

  it('非 JSON 正文与网络不可达：归一为稳定原因码', async () => {
    stubFetch(
      () =>
        new Response('<html>500</html>', {
          headers: { 'content-type': 'text/html' },
          status: 500,
        }),
    );
    const api = createComponentConversationApi({
      accessToken: () => TOKEN,
      baseUrl: BASE_URL,
    });
    await expect(api.list()).rejects.toMatchObject({ code: 'HTTP_500' });

    // 传输层失败（断网/连接被拒）：没有 HTTP 状态码，归一为 NETWORK_UNREACHABLE
    stubFetch(() => {
      throw new TypeError('Failed to fetch');
    });
    const offline = createComponentConversationApi({
      accessToken: () => TOKEN,
      baseUrl: BASE_URL,
    });
    await expect(offline.list()).rejects.toMatchObject({
      code: 'NETWORK_UNREACHABLE',
      status: 0,
    });
  });

  it('返回非法 JSON 的业务成功响应：MALFORMED_RESPONSE', async () => {
    stubFetch(
      () =>
        new Response('{not json', {
          headers: { 'content-type': 'application/json' },
          status: 200,
        }),
    );
    const api = createComponentConversationApi({
      accessToken: () => TOKEN,
      baseUrl: BASE_URL,
    });
    await expect(api.list()).rejects.toMatchObject({
      code: 'MALFORMED_RESPONSE',
    });
  });
});

describe('运行端口（X09）', () => {
  const context: BusinessContext = {
    objectId: 'order-1',
    page: 'crm/order',
  };

  function runPort(
    overrides: {
      accessToken?: () => null | string;
      context?: () => BusinessContext | null;
      exchangeTicket?: () => Promise<null | string>;
    } = {},
  ) {
    return createComponentRunApi({
      accessToken: overrides.accessToken ?? (() => TOKEN),
      baseUrl: BASE_URL,
      context: overrides.context ?? (() => null),
      exchangeTicket: overrides.exchangeTicket ?? (async () => null),
    });
  }

  it('受理：服务标识转数值、带幂等键与数据级别，上下文快照进入请求体', async () => {
    const requests = stubFetch(() =>
      json({
        code: 0,
        data: {
          releaseId: 1,
          releaseVersion: 1,
          reused: false,
          runId: 7,
          runKey: 'run_0007',
          status: 'QUEUED',
        },
      }),
    );
    const api = runPort({ context: () => context });

    expect(
      await api.createRun(
        { conversationId: 3, message: '查订单', serviceId: 'svc_7' },
        'idem_key_1234567890',
      ),
    ).toEqual({ runId: 7, runKey: 'run_0007', status: 'QUEUED' });

    const request = requests[0];
    expect(request?.url).toBe(`${BASE_URL}/ai/run/accept`);
    expect(request?.headers['idempotency-key']).toBe('idem_key_1234567890');
    expect(request?.headers.authorization).toBe(`Bearer ${TOKEN}`);
    expect(JSON.parse(request?.body ?? '{}')).toEqual({
      businessContext: JSON.stringify(context),
      conversationId: 3,
      dataLevel: 'L2_INTERNAL',
      idempotencyKey: 'idem_key_1234567890',
      message: '查订单',
      serviceId: 7,
    });
  });

  it('无上下文时请求体不带 businessContext（不发明空上下文）', async () => {
    const requests = stubFetch(() =>
      json({
        code: 0,
        data: {
          reused: false,
          runId: 1,
          runKey: 'run_0001',
          status: 'QUEUED',
        },
      }),
    );
    await runPort().createRun(
      { message: '你好', serviceId: 'svc_1' },
      'idem_key_1234567890',
    );
    expect(JSON.parse(requests[0]?.body ?? '{}')).not.toHaveProperty(
      'businessContext',
    );
  });

  it('服务标识不是受支持的形状：本地拒绝（不发请求）', async () => {
    const requests = stubFetch(() => json({ code: 0, data: {} }));
    await expect(
      runPort().createRun(
        { message: '你好', serviceId: 'not-a-service-key' },
        'idem_key_1234567890',
      ),
    ).rejects.toMatchObject({ code: 'INVALID_REQUEST' });
    expect(requests).toHaveLength(0);
  });

  it('取消与事件流：走共享开放客户端（seq 去重、终态结束）', async () => {
    const frames = [
      'event: message\ndata: {"schemaVersion":"1.0","seq":1,"runId":"run_0007","status":"RUNNING","createdAt":"2026-09-27T10:00:00Z"}\n\n',
      'event: message\ndata: {"schemaVersion":"1.0","seq":1,"runId":"run_0007","status":"RUNNING","createdAt":"2026-09-27T10:00:00Z"}\n\n',
      'event: message\ndata: {"schemaVersion":"1.0","seq":2,"runId":"run_0007","status":"SUCCEEDED","block":{"kind":"text","text":"完成"},"createdAt":"2026-09-27T10:00:01Z"}\n\n',
    ];
    const requests = stubFetch((url) => {
      if (url.includes('/ai/run/events')) {
        const stream = new ReadableStream<Uint8Array>({
          start(controller) {
            for (const frame of frames) {
              controller.enqueue(new TextEncoder().encode(frame));
            }
            controller.close();
          },
        });
        return new Response(stream, {
          headers: { 'content-type': 'text/event-stream' },
          status: 200,
        });
      }
      return json({
        code: 0,
        data: { id: 7, runKey: 'run_0007', status: 'CANCELLED', version: 2 },
      });
    });
    const api = runPort();

    await expect(api.cancelRun('run_0007', 2)).resolves.toMatchObject({
      status: 'CANCELLED',
    });
    expect(requests[0]?.url).toBe(`${BASE_URL}/ai/run/cancel`);

    const events: { seq: number; status: string }[] = [];
    const result = await api.streamRunEvents?.('run_0007', {
      onEvent: (event) => events.push({ seq: event.seq, status: event.status }),
    });
    // 重复的 seq=1 被丢弃：只处理两条不重复的事件
    expect(events).toEqual([
      { seq: 1, status: 'RUNNING' },
      { seq: 2, status: 'SUCCEEDED' },
    ]);
    expect(result).toEqual({ lastSeq: 2, reason: 'terminal' });
  });

  it('401：换票一次并重试一次（换票回调更新内存票据，重试带上新票据）', async () => {
    const renewed = ['aitkt', 'renewed'].join('_');
    let currentToken = TOKEN;
    const exchangeTicket = vi.fn(async () => {
      currentToken = renewed;
      return renewed;
    });
    let attempts = 0;
    stubFetch((_url, init) => {
      const authorized = new Headers(init?.headers).get('authorization');
      attempts += 1;
      if (authorized === `Bearer ${renewed}`) {
        return json({
          code: 0,
          data: {
            reused: false,
            runId: 1,
            runKey: 'run_0001',
            status: 'QUEUED',
          },
        });
      }
      return json({ code: 1_003_004_001, data: null, msg: '票据无效' }, 401);
    });
    const api = runPort({
      accessToken: () => currentToken,
      exchangeTicket,
    });
    await expect(
      api.createRun(
        { message: '你好', serviceId: 'svc_1' },
        'idem_key_1234567890',
      ),
    ).resolves.toMatchObject({ runKey: 'run_0001' });
    expect(exchangeTicket).toHaveBeenCalledTimes(1);
    expect(attempts).toBe(2);
  });
});

describe('业务键 → 数值编号（X09）', () => {
  it('去掉前缀取数值（与共享客户端同一口径）', () => {
    expect(numericId('svc_123')).toBe(123);
    expect(numericId('run_0007')).toBe(7);
    expect(numericId('12')).toBe(12);
    // 没有数字部分：0（受理前的本地校验据此拒绝，不发出无编号的请求）
    expect(numericId('oops')).toBe(0);
  });
});

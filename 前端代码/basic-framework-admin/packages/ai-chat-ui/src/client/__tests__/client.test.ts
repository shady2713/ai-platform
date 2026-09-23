import { AiOpenApiError } from '@vben/ai-contracts';

import { describe, expect, it, vi } from 'vitest';

import { createOpenApiClient, RUN_EVENT_WINDOW_EXPIRED } from '../index';

function envelope(data: unknown, code = 0, msg = '') {
  return Response.json(
    { code, data, msg },
    {
      headers: { 'content-type': 'application/json' },
      status: 200,
    },
  );
}

function sseResponse(frames: string[]) {
  const encoder = new TextEncoder();
  const stream = new ReadableStream<Uint8Array>({
    start(controller) {
      for (const frame of frames) {
        controller.enqueue(encoder.encode(frame));
      }
      controller.close();
    },
  });
  return new Response(stream, {
    headers: { 'content-type': 'text/event-stream' },
    status: 200,
  });
}

const acceptRequest = {
  dataLevel: 'L2_INTERNAL',
  idempotencyKey: 'idem-0123456789abcdef',
  message: '帮我看下 8 月销售',
  serviceId: 9,
};

describe('共享开放客户端', () => {
  it('票据只进请求头（URL 与请求体里都不出现）', async () => {
    const calls: { init: RequestInit; url: string }[] = [];
    const client = createOpenApiClient({
      accessToken: () => 'aitkt_memory_only',
      baseUrl: 'https://host/app-api',
      fetchImpl: (async (input: string | URL, init?: RequestInit) => {
        calls.push({ init: init ?? {}, url: String(input) });
        return envelope({
          reused: false,
          runId: 1,
          runKey: 'run_x',
          status: 'ACCEPTED',
        });
      }) as unknown as typeof fetch,
    });

    await client.acceptRun(acceptRequest);

    const call = calls[0];
    expect(call?.url).toBe('https://host/app-api/ai/run/accept');
    expect(call?.url).not.toContain('aitkt_');
    expect(new Headers(call?.init.headers).get('Authorization')).toBe(
      'Bearer aitkt_memory_only',
    );
    expect(String(call?.init.body)).not.toContain('aitkt_');
    expect(new Headers(call?.init.headers).get('Idempotency-Key')).toBe(
      acceptRequest.idempotencyKey,
    );
  });

  it('401 只换票一次并重试一次；再次 401 直接失败', async () => {
    const exchangeTicket = vi.fn(() => Promise.resolve('aitkt_new'));
    let attempt = 0;
    const client = createOpenApiClient({
      accessToken: () => 'aitkt_stale',
      baseUrl: 'https://host/app-api',
      exchangeTicket,
      fetchImpl: (async () => {
        attempt += 1;
        if (attempt === 1) {
          return Response.json(
            { code: 1_003_003_001, msg: '未认证' },
            {
              headers: { 'content-type': 'application/json' },
              status: 401,
            },
          );
        }
        return envelope({ id: 7, status: 'RUNNING' });
      }) as unknown as typeof fetch,
    });

    await expect(client.getRun(7)).resolves.toEqual({
      id: 7,
      status: 'RUNNING',
    });
    expect(exchangeTicket).toHaveBeenCalledTimes(1);

    // 第二次 401：不再换票
    exchangeTicket.mockClear();
    const alwaysUnauthorized = createOpenApiClient({
      accessToken: () => 'aitkt_stale',
      baseUrl: 'https://host/app-api',
      exchangeTicket,
      fetchImpl: (async () =>
        Response.json(
          { code: 1_003_003_001, msg: '未认证' },
          {
            headers: { 'content-type': 'application/json' },
            status: 401,
          },
        )) as unknown as typeof fetch,
    });
    await expect(alwaysUnauthorized.getRun(7)).rejects.toBeInstanceOf(
      AiOpenApiError,
    );
    expect(exchangeTicket).toHaveBeenCalledTimes(1);
  });

  it('本地校验先于请求（幂等键长度、消息长度、服务编号）', async () => {
    const doFetch = vi.fn();
    const client = createOpenApiClient({
      baseUrl: 'https://host/app-api',
      fetchImpl: doFetch as unknown as typeof fetch,
    });

    await expect(
      client.acceptRun({ ...acceptRequest, idempotencyKey: 'short' }),
    ).rejects.toThrow('idempotencyKey');
    await expect(
      client.acceptRun({ ...acceptRequest, message: '   ' }),
    ).rejects.toThrow('message');
    await expect(
      client.acceptRun({ ...acceptRequest, serviceId: 0 }),
    ).rejects.toThrow('serviceId');
    expect(doFetch).not.toHaveBeenCalled();
  });

  it('事件流按 seq 去重、心跳不计事件、终态即停止', async () => {
    const events: number[] = [];
    const heartbeats: string[] = [];
    const client = createOpenApiClient({
      baseUrl: 'https://host/app-api',
      fetchImpl: (async () =>
        sseResponse([
          ': heartbeat\n\n',
          'data: {"schemaVersion":"1.0","seq":1,"runId":"run_abc12345","status":"RUNNING","createdAt":"2026-09-23T10:00:00Z"}\n\n',
          // 重复 seq 与乱序 seq：必须丢弃
          'data: {"schemaVersion":"1.0","seq":1,"runId":"run_abc12345","status":"RUNNING","createdAt":"2026-09-23T10:00:00Z"}\n\n',
          'data: {"schemaVersion":"1.0","seq":2,"runId":"run_abc12345","status":"SUCCEEDED","createdAt":"2026-09-23T10:00:01Z"}\n\n',
          'data: {"schemaVersion":"1.0","seq":3,"runId":"run_abc12345","status":"RUNNING","createdAt":"2026-09-23T10:00:02Z"}\n\n',
        ])) as unknown as typeof fetch,
    });

    const result = await client.streamRunEvents(1, {
      onEvent: (event) => events.push(event.seq),
      onHeartbeat: (comment) => heartbeats.push(comment),
    });

    expect(events).toEqual([1, 2]);
    expect(heartbeats).toEqual(['heartbeat']);
    expect(result.reason).toBe('terminal');
    expect(result.lastSeq).toBe(2);
  });

  it('断线（连接关闭）返回 closed 与 lastSeq，重连带 afterSeq 且不重执行', async () => {
    const urls: string[] = [];
    let round = 0;
    const client = createOpenApiClient({
      baseUrl: 'https://host/app-api',
      fetchImpl: (async (input: string | URL) => {
        urls.push(String(input));
        round += 1;
        if (round === 1) {
          // 第一轮：只发一条事件后连接关闭（没有终态）
          return sseResponse([
            'data: {"schemaVersion":"1.0","seq":4,"runId":"run_abc12345","status":"RUNNING","createdAt":"2026-09-23T10:00:00Z"}\n\n',
          ]);
        }
        return sseResponse([
          'data: {"schemaVersion":"1.0","seq":5,"runId":"run_abc12345","status":"SUCCEEDED","createdAt":"2026-09-23T10:00:03Z"}\n\n',
        ]);
      }) as unknown as typeof fetch,
    });

    const first = await client.streamRunEvents(1, { onEvent: () => undefined });
    expect(first).toMatchObject({ lastSeq: 4, reason: 'closed' });
    const second = await client.streamRunEvents(1, {
      afterSeq: first.lastSeq,
      onEvent: () => undefined,
    });
    expect(second).toMatchObject({ lastSeq: 5, reason: 'terminal' });
    expect(urls[0]).not.toContain('afterSeq');
    expect(urls[1]).toContain('afterSeq=4');
  });

  it('重放窗口过期改为读取快照（不重新发起运行）', async () => {
    const urls: string[] = [];
    const client = createOpenApiClient({
      baseUrl: 'https://host/app-api',
      fetchImpl: (async (input: string | URL) => {
        urls.push(String(input));
        if (String(input).includes('/events')) {
          return Response.json(
            {
              code: Number(RUN_EVENT_WINDOW_EXPIRED),
              msg: '重放窗口过期',
            },
            {
              headers: { 'content-type': 'application/json' },
              status: 409,
            },
          );
        }
        return envelope({ id: 1, status: 'SUCCEEDED', version: 3 });
      }) as unknown as typeof fetch,
    });

    const result = await client.streamRunEvents(1, {
      afterSeq: 99,
      onEvent: () => undefined,
    });

    expect(result.reason).toBe('snapshot');
    expect(result.snapshot).toMatchObject({ id: 1, status: 'SUCCEEDED' });
    expect(urls).toEqual([
      'https://host/app-api/ai/run/events?runId=1&afterSeq=99',
      'https://host/app-api/ai/run/get?id=1',
    ]);
  });

  it('取消运行带乐观锁版本；分页查询拼查询串', async () => {
    const calls: { init: RequestInit; url: string }[] = [];
    const client = createOpenApiClient({
      baseUrl: 'https://host/app-api',
      fetchImpl: (async (input: string | URL, init?: RequestInit) => {
        calls.push({ init: init ?? {}, url: String(input) });
        return envelope({ id: 1, status: 'CANCELLED' });
      }) as unknown as typeof fetch,
    });

    await client.cancelRun(1, 2);
    expect(JSON.parse(String(calls[0]?.init.body))).toEqual({
      runId: 1,
      version: 2,
    });

    await client.pageRuns({ pageNo: 1, pageSize: 10 });
    expect(calls[1]?.url).toBe(
      'https://host/app-api/ai/run/page?pageNo=1&pageSize=10',
    );
  });
});

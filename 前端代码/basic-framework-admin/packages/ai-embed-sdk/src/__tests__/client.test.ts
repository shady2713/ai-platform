import { describe, expect, it, vi } from 'vitest';

import { AiChatApiError, createAiChatClient } from '../index';

const BASE = 'https://ai.example.com/app-api';

function jsonResponse(body: unknown, status = 200): Response {
  return Response.json(body, {
    headers: { 'Content-Type': 'application/json' },
    status,
  });
}

function sseResponse(frames: string[]): Response {
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

function clientWith(
  fetchImpl: typeof fetch,
  accessToken?: () => null | string,
) {
  return createAiChatClient({ accessToken, baseUrl: `${BASE}/`, fetchImpl });
}

describe('createAiChatClient', () => {
  it('受理运行：POST /ai/run/accept，带幂等键与 Bearer（票据不进 URL）', async () => {
    const fetchImpl = vi.fn(async () =>
      jsonResponse({
        code: 0,
        data: { reused: false, runId: 1, runKey: 'run_x', status: 'QUEUED' },
        msg: '',
      }),
    ) as unknown as typeof fetch;
    const client = clientWith(fetchImpl, () => 'aitkt_memory');

    const accepted = await client.createRun(
      { message: '你好', serviceId: 'svc_9' },
      'idem-key-0123456789',
    );

    expect(accepted.runKey).toBe('run_x');
    const [url, init] = (fetchImpl as unknown as ReturnType<typeof vi.fn>).mock
      .calls[0] as [string, RequestInit];
    expect(url).toBe(`${BASE}/ai/run/accept`);
    expect(url).not.toContain('aitkt_');
    expect(init.method).toBe('POST');
    const headers = init.headers as Headers;
    expect(headers.get('Idempotency-Key')).toBe('idem-key-0123456789');
    expect(headers.get('Authorization')).toBe('Bearer aitkt_memory');
    expect(JSON.parse(String(init.body))).toMatchObject({
      message: '你好',
      serviceId: 9,
    });
    expect(String(init.body)).not.toContain('aitkt_');
  });

  it('查询与取消使用契约路径；业务失败抛稳定错误', async () => {
    const fetchImpl = vi.fn(async () =>
      jsonResponse({ code: 0, data: { id: 3, status: 'RUNNING' }, msg: '' }),
    ) as unknown as typeof fetch;
    const client = clientWith(fetchImpl);

    await client.getRun('run_3');
    expect(
      (fetchImpl as unknown as ReturnType<typeof vi.fn>).mock.calls[0]?.[0],
    ).toBe(`${BASE}/ai/run/get?id=3`);
    await client.cancelRun('run_3', 2);
    expect(
      (fetchImpl as unknown as ReturnType<typeof vi.fn>).mock.calls[1]?.[0],
    ).toBe(`${BASE}/ai/run/cancel`);

    const failing = clientWith((async () =>
      jsonResponse(
        { code: 1_003_004_001, msg: '运行不存在' },
        404,
      )) as unknown as typeof fetch);
    await expect(failing.getRun('run_3')).rejects.toBeInstanceOf(
      AiChatApiError,
    );
  });

  it('本地校验：幂等键长度与服务/消息必填', async () => {
    const doFetch = vi.fn();
    const client = clientWith(doFetch as unknown as typeof fetch);
    await expect(
      client.createRun({ message: '你好', serviceId: 'svc_9' }, 'short'),
    ).rejects.toThrow('幂等键');
    await expect(
      client.createRun(
        { message: '  ', serviceId: 'svc_9' },
        'idem-key-0123456789',
      ),
    ).rejects.toThrow('不能为空');
    expect(doFetch).not.toHaveBeenCalled();
  });

  it('事件流去重、心跳不计事件、终态停止；断线可带 afterSeq 重连', async () => {
    const urls: string[] = [];
    let round = 0;
    const client = clientWith((async (input: string | URL) => {
      urls.push(String(input));
      round += 1;
      if (round === 1) {
        return sseResponse([
          ': heartbeat\n\n',
          'data: {"schemaVersion":"1.0","seq":1,"runId":"run_abc12345","status":"RUNNING","createdAt":"2026-09-23T10:00:00Z"}\n\n',
          'data: {"schemaVersion":"1.0","seq":1,"runId":"run_abc12345","status":"RUNNING","createdAt":"2026-09-23T10:00:00Z"}\n\n',
        ]);
      }
      return sseResponse([
        'data: {"schemaVersion":"1.0","seq":2,"runId":"run_abc12345","status":"SUCCEEDED","createdAt":"2026-09-23T10:00:01Z"}\n\n',
      ]);
    }) as unknown as typeof fetch);

    const events: number[] = [];
    const heartbeats: string[] = [];
    const first = await client.streamRunEvents('run_1', {
      onEvent: (event) => events.push(event.seq),
      onHeartbeat: (comment) => heartbeats.push(comment),
    });
    expect(events).toEqual([1]);
    expect(heartbeats).toEqual(['heartbeat']);
    expect(first).toMatchObject({ lastSeq: 1, reason: 'closed' });

    const second = await client.streamRunEvents('run_1', {
      afterSeq: first.lastSeq,
      onEvent: (event) => events.push(event.seq),
    });
    expect(events).toEqual([1, 2]);
    expect(second.reason).toBe('terminal');
    expect(urls[1]).toContain('afterSeq=1');
  });

  it('重放窗口过期改为读取快照', async () => {
    const urls: string[] = [];
    const client = clientWith((async (input: string | URL) => {
      urls.push(String(input));
      if (String(input).includes('/events')) {
        return jsonResponse({ code: 1_003_004_009, msg: '重放窗口过期' }, 409);
      }
      return jsonResponse({
        code: 0,
        data: { id: 1, status: 'SUCCEEDED' },
        msg: '',
      });
    }) as unknown as typeof fetch);

    const result = await client.streamRunEvents('run_1', {
      afterSeq: 9,
      onEvent: () => undefined,
    });
    expect(result.reason).toBe('snapshot');
    expect(result.snapshot).toMatchObject({ id: 1, status: 'SUCCEEDED' });
  });

  it('401 只换票一次并重试一次（不复用管理端 Cookie 刷新）', async () => {
    const exchangeTicket = vi.fn(() => Promise.resolve('aitkt_new'));
    let attempt = 0;
    const client = createAiChatClient({
      accessToken: () => 'aitkt_stale',
      baseUrl: BASE,
      exchangeTicket,
      fetchImpl: (async () => {
        attempt += 1;
        return attempt === 1
          ? jsonResponse({ code: 1_003_003_001, msg: '未认证' }, 401)
          : jsonResponse({
              code: 0,
              data: { id: 5, status: 'RUNNING' },
              msg: '',
            });
      }) as unknown as typeof fetch,
    });

    await expect(client.getRun('run_5')).resolves.toMatchObject({ id: 5 });
    expect(exchangeTicket).toHaveBeenCalledTimes(1);
  });

  it('事件流非 2xx：业务错误原样抛出；非 JSON 响应给 HTTP 错误；空响应体拒绝', async () => {
    // 业务错误（非窗口过期）：原样抛出稳定错误
    const failing = clientWith((async () =>
      jsonResponse(
        { code: 1_003_004_002, msg: '运行不可取消' },
        409,
      )) as unknown as typeof fetch);
    await expect(
      failing.streamRunEvents('run_1', { onEvent: () => undefined }),
    ).rejects.toMatchObject({ code: '1003004002' });

    // 非 JSON 响应（网关错误页）：转成 HTTP_<status>
    const gateway = clientWith(
      (async () =>
        new Response('<html>bad gateway</html>', {
          status: 502,
        })) as unknown as typeof fetch,
    );
    await expect(
      gateway.streamRunEvents('run_1', { onEvent: () => undefined }),
    ).rejects.toMatchObject({ code: 'HTTP_502' });

    // 没有响应体：拒绝而不是静默结束
    const empty = clientWith(
      (async () =>
        new Response(null, {
          status: 200,
          headers: { 'content-type': 'text/event-stream' },
        })) as unknown as typeof fetch,
    );
    await expect(
      empty.streamRunEvents('run_1', { onEvent: () => undefined }),
    ).rejects.toMatchObject({ code: 'EMPTY_STREAM' });

    // 窗口过期但快照读取也失败：把快照错误如实抛出
    const snapshotFails = clientWith((async (input: string | URL) => {
      if (String(input).includes('/events')) {
        return jsonResponse({ code: 1_003_004_009, msg: '重放窗口过期' }, 409);
      }
      return jsonResponse({ code: 1_003_004_001, msg: '运行不存在' }, 404);
    }) as unknown as typeof fetch);
    await expect(
      snapshotFails.streamRunEvents('run_1', {
        afterSeq: 9,
        onEvent: () => undefined,
      }),
    ).rejects.toMatchObject({ code: '1003004001' });
  });

  it('非 JSON 成功响应视为信封缺失；事件流请求同样带 Bearer', async () => {
    const calls: { init: RequestInit; url: string }[] = [];
    const client = clientWith(
      (async (input: string | URL, init?: RequestInit) => {
        calls.push({ init: init ?? {}, url: String(input) });
        if (String(input).includes('/events')) {
          return sseResponse([': ping\n\n']);
        }
        return new Response('plain text', { status: 200 });
      }) as unknown as typeof fetch,
      () => 'aitkt_memory',
    );

    await expect(client.getRun('run_1')).rejects.toMatchObject({
      code: 'MALFORMED_RESPONSE',
    });

    const result = await client.streamRunEvents('run_1', {
      onEvent: () => undefined,
    });
    expect(result.reason).toBe('closed');
    // 事件流同样只通过请求头带票据（URL 里不出现）
    expect(calls[1]?.url).not.toContain('aitkt_');
    expect(new Headers(calls[1]?.init.headers).get('Authorization')).toBe(
      'Bearer aitkt_memory',
    );
    expect(new Headers(calls[1]?.init.headers).get('Accept')).toBe(
      'text/event-stream',
    );
  });

  it('取消运行带乐观锁版本；心跳注释不产生事件', async () => {
    const calls: { init: RequestInit; url: string }[] = [];
    const client = clientWith((async (
      input: string | URL,
      init?: RequestInit,
    ) => {
      calls.push({ init: init ?? {}, url: String(input) });
      if (String(input).includes('/events')) {
        return sseResponse([': ping\n\n']);
      }
      return jsonResponse({
        code: 0,
        data: { id: 3, status: 'CANCELLED' },
        msg: '',
      });
    }) as unknown as typeof fetch);

    await client.cancelRun('run_3', 4);
    expect(JSON.parse(String(calls[0]?.init.body))).toEqual({
      runId: 3,
      version: 4,
    });
    // 默认版本 0（调用方不传时）
    await client.cancelRun('run_3');
    expect(JSON.parse(String(calls[1]?.init.body))).toEqual({
      runId: 3,
      version: 0,
    });

    const events: number[] = [];
    const heartbeats: string[] = [];
    const result = await client.streamRunEvents('run_3', {
      onEvent: (event) => events.push(event.seq),
      onHeartbeat: (comment) => heartbeats.push(comment),
    });
    expect(events).toEqual([]);
    expect(heartbeats).toEqual(['ping']);
    expect(result.reason).toBe('closed');
  });
});

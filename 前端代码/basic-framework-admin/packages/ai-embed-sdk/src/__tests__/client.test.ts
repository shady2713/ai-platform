import { describe, expect, it, vi } from 'vitest';

import { AiChatApiError, createAiChatClient } from '../index';

const BASE = 'https://ai.example.com/app-api/ai/v1';

function jsonResponse(body: unknown, status = 200): Response {
  return Response.json(body, {
    headers: { 'Content-Type': 'application/json' },
    status,
  });
}

function clientWith(
  fetchImpl: typeof fetch,
  accessToken?: () => null | string,
) {
  return createAiChatClient({ baseUrl: `${BASE}/`, fetchImpl, accessToken });
}

describe('createAiChatClient', () => {
  it('受理运行：POST /runs，带幂等键与 JSON 正文', async () => {
    const fetchImpl = vi.fn(async () =>
      jsonResponse({
        code: 0,
        data: { runId: 'run_abc123', status: 'QUEUED' },
        msg: '',
      }),
    ) as unknown as typeof fetch;
    const client = clientWith(fetchImpl);

    const accepted = await client.createRun(
      { message: '你好', serviceId: 'svc_demo1' },
      'idem-key-0123456789',
    );

    expect(accepted).toEqual({ runId: 'run_abc123', status: 'QUEUED' });
    const [url, init] = (fetchImpl as unknown as ReturnType<typeof vi.fn>).mock
      .calls[0] as [string, RequestInit];
    expect(url).toBe(`${BASE}/runs`);
    expect(init.method).toBe('POST');
    const headers = init.headers as Headers;
    expect(headers.get('Idempotency-Key')).toBe('idem-key-0123456789');
    expect(headers.get('Content-Type')).toBe('application/json');
    expect(init.body).toBe(
      JSON.stringify({ message: '你好', serviceId: 'svc_demo1' }),
    );
  });

  it('查询与取消使用契约路径并对 runId 转义', async () => {
    const fetchImpl = vi.fn(async () =>
      jsonResponse({
        code: 0,
        data: { blocks: [], runId: 'run_x', status: 'RUNNING' },
        msg: '',
      }),
    ) as unknown as typeof fetch;
    const client = clientWith(fetchImpl);

    await client.getRun('run_x');
    await client.cancelRun('run_x');

    const calls = (fetchImpl as unknown as ReturnType<typeof vi.fn>).mock.calls;
    expect(calls[0]?.[0]).toBe(`${BASE}/runs/run_x`);
    expect((calls[0]?.[1] as RequestInit).method).toBe('GET');
    expect(calls[1]?.[0]).toBe(`${BASE}/runs/run_x/cancel`);
    expect((calls[1]?.[1] as RequestInit).method).toBe('POST');
  });

  it('携带宿主提供的 Bearer 票据，没有票据时不发送 Authorization', async () => {
    const fetchImpl = vi.fn(async () =>
      jsonResponse({
        code: 0,
        data: { blocks: [], runId: 'r', status: 'QUEUED' },
        msg: '',
      }),
    ) as unknown as typeof fetch;

    await clientWith(fetchImpl, () => 'ticket-1').getRun('r');
    await clientWith(fetchImpl, () => null).getRun('r');

    const calls = (fetchImpl as unknown as ReturnType<typeof vi.fn>).mock.calls;
    expect((calls[0]?.[1] as RequestInit).headers instanceof Headers).toBe(
      true,
    );
    expect(
      ((calls[0]?.[1] as RequestInit).headers as Headers).get('Authorization'),
    ).toBe('Bearer ticket-1');
    expect(
      ((calls[1]?.[1] as RequestInit).headers as Headers).get('Authorization'),
    ).toBeNull();
  });

  it('本地校验先于网络请求：非法幂等键与空消息不发请求', async () => {
    const fetchImpl = vi.fn() as unknown as typeof fetch;
    const client = clientWith(fetchImpl);

    await expect(
      client.createRun({ message: '你好', serviceId: 'svc_a' }, 'short'),
    ).rejects.toThrow(AiChatApiError);
    await expect(
      client.createRun(
        { message: '   ', serviceId: 'svc_a' },
        'idem-key-0123456789',
      ),
    ).rejects.toThrow('message 长度');
    await expect(
      client.createRun(
        { message: '你好', serviceId: ' ' },
        'idem-key-0123456789',
      ),
    ).rejects.toThrow('serviceId');
    expect(fetchImpl).not.toHaveBeenCalled();
  });

  it('业务错误码与 HTTP 错误映射为 AiChatApiError', async () => {
    const businessError = vi.fn(async () =>
      jsonResponse({ code: 1_002_000_001, data: null, msg: '服务不存在' }, 200),
    ) as unknown as typeof fetch;
    await expect(clientWith(businessError).getRun('r')).rejects.toMatchObject({
      code: '1002000001',
      message: '服务不存在',
      name: 'AiChatApiError',
    });

    const httpError = vi.fn(async () =>
      jsonResponse({ code: 0, msg: '' }, 401),
    ) as unknown as typeof fetch;
    await expect(clientWith(httpError).getRun('r')).rejects.toMatchObject({
      status: 401,
    });
  });

  it('响应缺少 data 时报稳定错误', async () => {
    const emptyData = vi.fn(async () =>
      jsonResponse({ code: 0, msg: '' }),
    ) as unknown as typeof fetch;

    await expect(clientWith(emptyData).getRun('r')).rejects.toMatchObject({
      code: 'EMPTY_DATA',
    });
  });
});

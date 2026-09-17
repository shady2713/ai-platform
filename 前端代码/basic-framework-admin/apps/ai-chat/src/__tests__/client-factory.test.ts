import { afterEach, describe, expect, it, vi } from 'vitest';

import { buildAiChatClient } from '../client-factory';

function fetchReturning(payload: unknown) {
  return vi.fn(async () =>
    Response.json(payload, {
      headers: { 'Content-Type': 'application/json' },
      status: 200,
    }),
  ) as unknown as typeof fetch;
}

describe('buildAiChatClient', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('未配置或空白基址时返回 null', () => {
    expect(buildAiChatClient({})).toBeNull();
    expect(buildAiChatClient({ baseUrl: '   ' })).toBeNull();
  });

  it('配置基址时创建客户端，请求发往该基址的开放 API 路径', async () => {
    const storage = {
      getItem: vi.fn((key: string) =>
        key === 'ai_access_ticket' ? 'ticket-7' : null,
      ),
    };
    const fetchImpl = fetchReturning({
      code: 0,
      data: { runId: 'run_1', status: 'QUEUED' },
    });
    // SDK 在创建客户端时取用 fetch，必须在构建前替换，避免真实网络请求
    vi.stubGlobal('fetch', fetchImpl);
    const client = buildAiChatClient({
      baseUrl: 'https://ai.example.com/app-api/ai/v1/',
      ticketStorage: storage,
    });

    expect(client).not.toBeNull();
    await client?.createRun(
      { message: '你好', serviceId: 'svc_demo' },
      'idem-key-0123456789',
    );

    const [url, init] = (fetchImpl as unknown as ReturnType<typeof vi.fn>).mock
      .calls[0] as [string, RequestInit];
    expect(url).toBe('https://ai.example.com/app-api/ai/v1/runs');
    expect((init.headers as Headers).get('Authorization') ?? '').toBe(
      'Bearer ticket-7',
    );
    expect(storage.getItem).toHaveBeenCalledWith('ai_access_ticket');
  });

  it('没有票据时不发送 Authorization 头', async () => {
    const fetchImpl = fetchReturning({
      code: 0,
      data: { runId: 'r', status: 'QUEUED' },
    });
    vi.stubGlobal('fetch', fetchImpl);
    const client = buildAiChatClient({
      baseUrl: 'https://ai.example.com/app-api/ai/v1',
      ticketStorage: { getItem: () => null },
    });

    await client?.getRun('run_1');

    const [, init] = (fetchImpl as unknown as ReturnType<typeof vi.fn>).mock
      .calls[0] as [string, RequestInit];
    expect((init.headers as Headers).get('Authorization')).toBeNull();
  });
});

import { describe, expect, it, vi } from 'vitest';

import { createIdempotencyKey, useAiChat } from '../use-chat';

describe('createIdempotencyKey', () => {
  it('生成长度落在契约区间内的唯一键', () => {
    const first = createIdempotencyKey();
    const second = createIdempotencyKey();

    expect(first).not.toBe(second);
    expect(first.length).toBeGreaterThanOrEqual(16);
    expect(first.length).toBeLessThanOrEqual(128);
  });
});

describe('useAiChat', () => {
  it('未配置客户端时给出明确错误块', async () => {
    const chat = useAiChat({ client: null, serviceId: 'svc_x' });

    await chat.send('你好');

    expect(chat.messages.value.map((message) => message.role)).toEqual([
      'user',
      'assistant',
    ]);
    expect(chat.messages.value[1]?.blocks[0]).toMatchObject({
      kind: 'error',
      message: '未配置 AI 服务地址，无法发送',
    });
  });

  it('受理成功时展示 runId 与状态，并复用同一幂等键语义', async () => {
    const createRun = vi.fn(
      async (
        _request: { message: string; serviceId: string },
        _idempotencyKey: string,
      ) => ({
        reused: false,
        runId: 1,
        runKey: 'run_1',
        status: 'QUEUED' as const,
      }),
    );
    const chat = useAiChat({
      client: {
        cancelRun: vi.fn(),
        createRun,
        getRun: vi.fn(),
        streamRunEvents: vi.fn(),
      },
      serviceId: 'svc_x',
    });

    await chat.send('画销售额');

    expect(createRun).toHaveBeenCalledTimes(1);
    const [request, idempotencyKey] = createRun.mock.calls[0] ?? [];
    expect(request).toMatchObject({ message: '画销售额', serviceId: 'svc_x' });
    expect(String(idempotencyKey).length).toBeGreaterThanOrEqual(16);
    expect(chat.messages.value[1]?.blocks[0]).toMatchObject({
      kind: 'text',
      // 展示用 runKey（业务键）而不是数值编号
      text: '已受理运行 run_1（QUEUED）',
    });
  });

  it('受理失败时输出错误块且状态复位', async () => {
    const createRun = vi.fn(
      async (
        _request: { message: string; serviceId: string },
        _idempotencyKey: string,
      ) => {
        throw new Error('服务不存在');
      },
    );
    const chat = useAiChat({
      client: {
        cancelRun: vi.fn(),
        createRun,
        getRun: vi.fn(),
        streamRunEvents: vi.fn(),
      },
      serviceId: 'svc_x',
    });

    await chat.send('你好');

    expect(chat.messages.value[1]?.blocks[0]).toMatchObject({
      kind: 'error',
      message: '服务不存在',
    });
    expect(chat.pending.value).toBe(false);
  });

  it('受理期间 pending 为真，避免重复提交', async () => {
    let release: (() => void) | undefined;
    const createRun = vi.fn(
      (
        _request: { message: string; serviceId: string },
        _idempotencyKey: string,
      ) =>
        new Promise<{
          reused: boolean;
          runId: number;
          runKey: string;
          status: 'QUEUED';
        }>((resolve) => {
          release = () =>
            resolve({
              reused: false,
              runId: 2,
              runKey: 'run_2',
              status: 'QUEUED',
            });
        }),
    );
    const chat = useAiChat({
      client: {
        cancelRun: vi.fn(),
        createRun,
        getRun: vi.fn(),
        streamRunEvents: vi.fn(),
      },
      serviceId: 'svc_x',
    });

    const sending = chat.send('你好');
    expect(chat.pending.value).toBe(true);

    release?.();
    await sending;
    expect(chat.pending.value).toBe(false);
  });
});

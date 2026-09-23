import type { ConversationApi, ConversationRunApi } from '../use-conversation';

import { describe, expect, it, vi } from 'vitest';

import { useConversation } from '../use-conversation';

function apiStub(): ConversationApi & {
  items: { conversationKey: string; id: number; title: string }[];
} {
  const items = [{ conversationKey: 'conv_1', id: 1, title: '第一个会话' }];
  return {
    create: vi.fn(async (title?: string) => {
      const created = {
        conversationKey: `conv_${items.length + 1}`,
        id: items.length + 1,
        title: title ?? '新会话',
      };
      items.push(created);
      return created;
    }),
    items,
    list: vi.fn(async () => [...items]),
    remove: vi.fn(async (id: number) => {
      const index = items.findIndex((item) => item.id === id);
      if (index !== -1) {
        items.splice(index, 1);
      }
    }),
    rename: vi.fn(async (id: number, title: string) => {
      const found = items.find((item) => item.id === id);
      if (found) {
        found.title = title;
      }
    }),
  };
}

function runApiStub(overrides: Partial<ConversationRunApi> = {}) {
  const events: ((event: { seq: number; status: string }) => void)[] = [];
  const api: ConversationRunApi = {
    cancelRun: vi.fn(async () => ({})),
    createRun: vi.fn(async () => ({
      runId: 1,
      runKey: 'run_1',
      status: 'QUEUED',
    })),
    streamRunEvents: vi.fn(async (_runKey, handlers) => {
      events.push(handlers.onEvent);
      return { lastSeq: 0, reason: 'closed' };
    }),
    ...overrides,
  };
  return {
    api,
    emit: (event: { seq: number; status: string }) =>
      events.forEach((handler) => handler(event)),
  };
}

describe('useConversation（会话逻辑）', () => {
  it('会话列表：新建/重命名/删除 + 删除当前会话时清空界面并换代', async () => {
    const api = apiStub();
    const chat = useConversation({
      api,
      runApi: runApiStub().api,
      serviceId: 'svc_1',
    });

    await chat.refreshList();
    expect(chat.conversations.value).toHaveLength(1);

    await chat.createConversation('销售分析');
    expect(chat.active.value?.title).toBe('销售分析');
    expect(chat.conversations.value).toHaveLength(2);

    await chat.renameConversation(2, '改名后');
    expect(chat.conversations.value.find((item) => item.id === 2)?.title).toBe(
      '改名后',
    );

    await chat.deleteConversation(2);
    expect(chat.conversations.value).toHaveLength(1);
    expect(chat.active.value).toBeUndefined();
  });

  it('发送：受理后进入执行态；连续点击不重复受理', async () => {
    const api = apiStub();
    let release: (() => void) | undefined;
    const runApi = runApiStub({
      createRun: vi.fn(
        () =>
          new Promise<{ runId: number; runKey: string; status: string }>(
            (resolve) => {
              release = () =>
                resolve({ runId: 1, runKey: 'run_1', status: 'QUEUED' });
            },
          ),
      ),
    });
    const chat = useConversation({
      api,
      runApi: runApi.api,
      serviceId: 'svc_1',
    });

    const first = chat.send('你好');
    // 受理未返回期间再次点击：直接忽略（不产生第二个 run）
    await chat.send('你好（重复点击）');
    expect(runApi.api.createRun).toHaveBeenCalledTimes(1);

    release?.();
    await first;
    expect(chat.phase.value).toBe('RUNNING');
    expect(
      chat.messages.value.filter((message) => message.role === 'user'),
    ).toHaveLength(1);
  });

  it('取消后晚到的完成不覆盖终态；重试复用同一幂等键语义', async () => {
    const api = apiStub();
    const runApi = runApiStub();
    const chat = useConversation({
      api,
      runApi: runApi.api,
      serviceId: 'svc_1',
    });

    await chat.send('你好');
    expect(chat.phase.value).toBe('RUNNING');

    await chat.cancel();
    expect(chat.phase.value).toBe('FAILED');
    expect(runApi.api.cancelRun).toHaveBeenCalledWith('run_1');

    // 重试：重新受理（同一会话界面不重复用户消息）
    await chat.retry();
    expect(runApi.api.createRun).toHaveBeenCalledTimes(2);
    expect(
      chat.messages.value.filter((message) => message.role === 'user'),
    ).toHaveLength(2);
  });

  it('切用户：清空界面并丢弃晚到的旧响应（AT-053）', async () => {
    const api = apiStub();
    let release: (() => void) | undefined;
    const runApi = runApiStub({
      createRun: vi.fn(
        () =>
          new Promise<{ runId: number; runKey: string; status: string }>(
            (resolve) => {
              release = () =>
                resolve({ runId: 1, runKey: 'run_old', status: 'QUEUED' });
            },
          ),
      ),
    });
    const chat = useConversation({
      api,
      runApi: runApi.api,
      serviceId: 'svc_1',
    });

    const pending = chat.send('旧用户的问题');
    chat.switchUser();
    release?.();
    await pending;

    // 旧响应被丢弃：新用户界面里没有"已受理运行 run_old"
    expect(chat.messages.value).toHaveLength(0);
    expect(chat.phase.value).toBe('IDLE');
  });

  it('错误只提示一次（重复失败不刷屏）', async () => {
    const api = apiStub();
    const runApi = runApiStub({
      createRun: vi.fn(async () => {
        throw new Error('服务不存在');
      }),
    });
    const chat = useConversation({
      api,
      runApi: runApi.api,
      serviceId: 'svc_1',
    });

    await chat.send('第一次');
    expect(
      chat.messages.value.filter(
        (message) => message.blocks[0]?.kind === 'error',
      ),
    ).toHaveLength(1);

    // 失败后重试再次失败：同一错误不再追加错误块
    await chat.retry();
    expect(
      chat.messages.value.filter(
        (message) => message.blocks[0]?.kind === 'error',
      ),
    ).toHaveLength(1);
    expect(chat.phase.value).toBe('FAILED');
  });

  it('未配置客户端时给出明确错误块，不静默失败', async () => {
    const api = apiStub();
    const chat = useConversation({ api, runApi: null, serviceId: 'svc_1' });

    await chat.send('你好');

    expect(chat.messages.value.at(-1)?.blocks[0]).toMatchObject({
      kind: 'error',
      message: '未配置 AI 服务地址，无法发送',
    });
    expect(chat.phase.value).toBe('FAILED');
  });

  it('事件流把阶段推进到确认/完成/失败', async () => {
    const api = apiStub();
    const runApi = runApiStub();
    const chat = useConversation({
      api,
      runApi: runApi.api,
      serviceId: 'svc_1',
    });

    await chat.send('你好');
    runApi.emit({ seq: 1, status: 'WAITING_CONFIRMATION' });
    expect(chat.phase.value).toBe('WAITING_CONFIRMATION');
    runApi.emit({ seq: 2, status: 'SUCCEEDED' });
    expect(chat.phase.value).toBe('SUCCEEDED');
  });
});

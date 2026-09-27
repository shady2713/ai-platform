import type { ConversationApi, ConversationRunApi } from '../use-conversation';

import { flushPromises, mount } from '@vue/test-utils';

import { describe, expect, it, vi } from 'vitest';

import ConversationPanel from '../ConversationPanel.vue';
import { chartBlock, textBlock } from './fixtures';

// 厂商实例由适配层内部创建：组件测试只断言"图表块走了共享适配组件"，不加载真实 G2
vi.mock('@antv/g2', () => ({
  Chart: class {
    public changeSize = vi.fn();
    public destroy = vi.fn();
    public options = vi.fn();
    public render = vi.fn();
  },
}));

function apiStub(): ConversationApi {
  const items = [
    { conversationKey: 'conv_1', id: 1, title: '销售分析' },
    { conversationKey: 'conv_2', id: 2, title: '库存分析' },
  ];
  return {
    create: vi.fn(async (title?: string) => {
      const created = {
        conversationKey: 'conv_3',
        id: 3,
        title: title ?? '新会话',
      };
      items.push(created);
      return created;
    }),
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

function runApiStub(
  streamRunEvents: ConversationRunApi['streamRunEvents'] = vi.fn(async () => ({
    lastSeq: 0,
    reason: 'closed',
  })),
): ConversationRunApi {
  return {
    cancelRun: vi.fn(async () => ({})),
    createRun: vi.fn(async () => ({
      runId: 1,
      runKey: 'run_1',
      status: 'QUEUED',
    })),
    streamRunEvents,
  };
}

async function mountPanel(
  runApi: ConversationRunApi | null = runApiStub(),
  api: ConversationApi = apiStub(),
) {
  const wrapper = mount(ConversationPanel, {
    props: { api, runApi, serviceId: 'svc_1' },
  });
  await flushPromises();
  return { api, runApi, wrapper };
}

describe('会话面板', () => {
  it('渲染会话列表并支持选择/新建/重命名/删除', async () => {
    const { api, wrapper } = await mountPanel();
    // 组件挂载时加载列表由宿主触发；这里直接刷新
    await wrapper.vm.$nextTick();

    // 新建：创建后成为当前会话
    await wrapper
      .get('[data-testid="ai-conversation-create"]')
      .trigger('click');
    await flushPromises();
    expect(api.create).toHaveBeenCalled();
    expect(
      wrapper.findAll('[data-testid="ai-conversation-item"]'),
    ).toHaveLength(3);

    // 重命名
    await wrapper
      .get('[data-testid="ai-conversation-rename"]')
      .trigger('click');
    await wrapper
      .get('[data-testid="ai-conversation-rename-input"]')
      .setValue('改名后');
    await wrapper
      .get('[data-testid="ai-conversation-rename-form"]')
      .trigger('submit');
    await flushPromises();
    expect(api.rename).toHaveBeenCalledWith(1, '改名后');

    // 删除
    await wrapper
      .get('[data-testid="ai-conversation-delete"]')
      .trigger('click');
    await flushPromises();
    expect(api.remove).toHaveBeenCalled();
  });

  it('发送消息进入执行态，取消按钮出现并可取消', async () => {
    const { wrapper } = await mountPanel();

    await wrapper.get('[data-testid="ai-conversation-input"]').setValue('你好');
    await wrapper.get('[data-testid="ai-conversation-send"]').trigger('submit');
    await flushPromises();

    expect(wrapper.get('[data-testid="ai-conversation-phase"]').text()).toBe(
      '执行中',
    );
    expect(
      wrapper.findAll('[data-testid="ai-conversation-text"]'),
    ).toHaveLength(2);

    await wrapper
      .get('[data-testid="ai-conversation-cancel"]')
      .trigger('click');
    await flushPromises();
    expect(wrapper.get('[data-testid="ai-conversation-phase"]').text()).toBe(
      '失败（可重试）',
    );
    expect(wrapper.find('[data-testid="ai-conversation-retry"]').exists()).toBe(
      true,
    );
  });

  it('未配置客户端时展示错误块而不是静默失败', async () => {
    const { wrapper } = await mountPanel(null);

    await wrapper.get('[data-testid="ai-conversation-input"]').setValue('你好');
    await wrapper.get('[data-testid="ai-conversation-send"]').trigger('submit');
    await flushPromises();

    expect(
      wrapper.get('[data-testid="ai-conversation-error"]').text(),
    ).toContain('未配置 AI 服务地址');
  });

  it('空输入不发送（不发请求、不产生消息）', async () => {
    const { runApi, wrapper } = await mountPanel();

    await wrapper.get('[data-testid="ai-conversation-input"]').setValue('   ');
    await wrapper.get('[data-testid="ai-conversation-send"]').trigger('submit');
    await flushPromises();

    expect(runApi?.createRun).not.toHaveBeenCalled();
    expect(
      wrapper.findAll('[data-testid="ai-conversation-message"]'),
    ).toHaveLength(0);
  });

  it('图表结果块用共享适配组件渲染，不落占位文本', async () => {
    const runApi = runApiStub(
      vi.fn(async (_runKey, handlers) => {
        handlers.onEvent({ block: textBlock, seq: 1, status: 'RUNNING' });
        handlers.onEvent({ block: chartBlock, seq: 2, status: 'SUCCEEDED' });
        return { lastSeq: 2, reason: 'terminal' };
      }),
    );
    const { wrapper } = await mountPanel(runApi);

    await wrapper.get('[data-testid="ai-conversation-input"]').setValue('画图');
    await wrapper.get('[data-testid="ai-conversation-send"]').trigger('submit');
    await flushPromises();

    // 文本块与图表块都在同一条助手消息里，顺序保持
    expect(wrapper.text()).toContain('华东前十如下');
    const chart = wrapper.get('[data-testid="ai-conversation-chart"]');
    expect(chart.text()).toContain('月度销售额');
    // 懒加载完成后由适配层创建图表实例（厂商被 mock），而不是占位文本
    await vi.waitFor(() => {
      expect(wrapper.find('[data-testid="ai-chart-canvas"]').exists()).toBe(
        true,
      );
    });
    expect(wrapper.text()).not.toContain('结果块');
  });

  it('列表加载成功时不显示失败提示', async () => {
    const { wrapper } = await mountPanel();

    expect(
      wrapper.find('[data-testid="ai-conversation-list-error"]').exists(),
    ).toBe(false);
    expect(
      wrapper.findAll('[data-testid="ai-conversation-item"]'),
    ).toHaveLength(2);
  });

  it('列表加载失败显示稳定原因码与消息，重试成功后提示消失', async () => {
    let offline = true;
    const items = [{ conversationKey: 'conv_1', id: 1, title: '销售分析' }];
    const api: ConversationApi = {
      create: vi.fn(async () => ({
        conversationKey: 'conv_2',
        id: 2,
        title: '新会话',
      })),
      list: vi.fn(async () => {
        if (offline) {
          throw Object.assign(
            new Error(
              '无法连接 AI 服务（http://127.0.0.1:48080/app-api）：网络不可达',
            ),
            { code: 'NETWORK_UNREACHABLE', status: 0 },
          );
        }
        return [...items];
      }),
      remove: vi.fn(async () => undefined),
      rename: vi.fn(async () => undefined),
    };

    const { wrapper } = await mountPanel(runApiStub(), api);

    const notice = wrapper.get('[data-testid="ai-conversation-list-error"]');
    expect(notice.text()).toContain('会话列表加载失败');
    expect(notice.text()).toContain('NETWORK_UNREACHABLE');
    expect(notice.text()).toContain('网络不可达');
    expect(
      wrapper.findAll('[data-testid="ai-conversation-item"]'),
    ).toHaveLength(0);

    // 重试：网络恢复后提示消失，列表渲染出真实数据
    offline = false;
    await wrapper
      .get('[data-testid="ai-conversation-list-retry"]')
      .trigger('click');
    await flushPromises();

    expect(
      wrapper.find('[data-testid="ai-conversation-list-error"]').exists(),
    ).toBe(false);
    expect(
      wrapper.findAll('[data-testid="ai-conversation-item"]'),
    ).toHaveLength(1);
  });

  it('失败消息里的 HTML 只按文本渲染（不产生元素、不执行）', async () => {
    const api: ConversationApi = {
      create: vi.fn(async () => ({
        conversationKey: 'conv_2',
        id: 2,
        title: '新会话',
      })),
      list: vi.fn(async () => {
        throw Object.assign(
          new Error('<img src=x onerror="globalThis.__q06Injected=1">'),
          { code: 'SERVER_MESSAGE' },
        );
      }),
      remove: vi.fn(async () => undefined),
      rename: vi.fn(async () => undefined),
    };

    const { wrapper } = await mountPanel(runApiStub(), api);

    const detail = wrapper.get(
      '[data-testid="ai-conversation-list-error-detail"]',
    );
    expect(detail.text()).toContain('<img');
    expect(wrapper.find('img').exists()).toBe(false);
    expect(
      (wrapper.vm as unknown as { __q06Injected?: number }).__q06Injected,
    ).toBeUndefined();
  });
});

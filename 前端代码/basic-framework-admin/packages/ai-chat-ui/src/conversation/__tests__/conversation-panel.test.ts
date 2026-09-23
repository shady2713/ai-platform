import type { ConversationApi, ConversationRunApi } from '../use-conversation';

import { flushPromises, mount } from '@vue/test-utils';

import { describe, expect, it, vi } from 'vitest';

import ConversationPanel from '../ConversationPanel.vue';

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

function runApiStub(): ConversationRunApi {
  return {
    cancelRun: vi.fn(async () => ({})),
    createRun: vi.fn(async () => ({
      runId: 1,
      runKey: 'run_1',
      status: 'QUEUED',
    })),
    streamRunEvents: vi.fn(async () => ({ lastSeq: 0, reason: 'closed' })),
  };
}

async function mountPanel(runApi: ConversationRunApi | null = runApiStub()) {
  const api = apiStub();
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
});

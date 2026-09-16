import type { SystemNotifyMessageApi } from '#/api/system/notify/message';

import { flushPromises, mount } from '@vue/test-utils';

import { beforeEach, describe, expect, it, vi } from 'vitest';

import {
  getNotifyMessagePage,
  updateNotifyMessageRead,
} from '#/api/system/notify/message';
import { showSuccessMessage } from '#/utils/feedback';

import MyNotifyMessage from './index.vue';

vi.mock('@vben/common-ui', async () => {
  const { defineComponent, h } = await import('vue');
  return {
    Page: defineComponent({
      name: 'PageStub',
      setup(_props, { slots }) {
        return () => h('main', slots.default?.());
      },
    }),
  };
});

vi.mock('element-plus', async () => {
  const { defineComponent, h } = await import('vue');
  const slotOnly = (name: string, tag: string) =>
    defineComponent({
      name,
      setup(_props, { slots }) {
        return () => h(tag, slots.default?.());
      },
    });
  return {
    ElButton: defineComponent({
      name: 'ElButton',
      emits: ['click'],
      setup(_props, { emit, slots }) {
        return () =>
          h('button', { onClick: () => emit('click') }, slots.default?.());
      },
    }),
    ElCard: defineComponent({
      name: 'ElCard',
      setup(_props, { slots }) {
        return () => h('section', [slots.header?.(), slots.default?.()]);
      },
    }),
    ElEmpty: defineComponent({
      name: 'ElEmpty',
      setup() {
        return () => h('div', { 'data-test': 'empty' });
      },
    }),
    ElPagination: defineComponent({
      name: 'ElPagination',
      emits: ['current-change'],
      setup(_props, { emit }) {
        return () =>
          h(
            'button',
            {
              'data-test': 'pagination',
              onClick: () => emit('current-change', 2),
            },
            '下一页',
          );
      },
    }),
    ElTabPane: slotOnly('ElTabPane', 'div'),
    ElTabs: defineComponent({
      name: 'ElTabs',
      emits: ['tab-change', 'update:modelValue'],
      setup(_props, { emit, slots }) {
        const tabs = ['unread', 'read', 'all'];
        return () =>
          h('div', [
            ...tabs.map((name) =>
              h(
                'button',
                {
                  'data-tab': name,
                  onClick: () => {
                    emit('update:modelValue', name);
                    emit('tab-change', name);
                  },
                },
                name,
              ),
            ),
            slots.default?.(),
          ]);
      },
    }),
    ElTag: slotOnly('ElTag', 'span'),
  };
});

vi.mock('#/api/system/notify/message', () => ({
  getNotifyMessagePage: vi.fn(),
  updateNotifyMessageRead: vi.fn(),
}));

vi.mock('#/utils/feedback', () => ({
  showSuccessMessage: vi.fn(),
}));

function message(
  overrides: Partial<SystemNotifyMessageApi.Message> = {},
): SystemNotifyMessageApi.Message {
  return {
    createTime: new Date('2026-09-01T00:00:00Z'),
    id: 6,
    readStatus: false,
    templateCode: 'password_reset',
    templateContent: '您的验证码是 {code}',
    templateId: 3,
    templateNickname: '系统',
    templateParams: { code: '123456' },
    templateType: 1,
    userId: 1,
    userType: 1,
    ...overrides,
  };
}

function mountPage() {
  return mount(MyNotifyMessage, {
    attachTo: document.body,
    global: { directives: { loading: () => undefined } },
  });
}

function textButton(wrapper: ReturnType<typeof mountPage>, text: string) {
  const button = wrapper
    .findAll('button')
    .find((candidate) => candidate.text() === text);
  if (!button) {
    throw new Error(`未找到按钮：${text}`);
  }
  return button;
}

describe('my notify message page', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(getNotifyMessagePage).mockResolvedValue({
      list: [message()],
      total: 1,
    });
    vi.mocked(updateNotifyMessageRead).mockResolvedValue(true);
  });

  it('loads unread messages on mount and renders them', async () => {
    const wrapper = mountPage();
    await flushPromises();

    expect(getNotifyMessagePage).toHaveBeenCalledWith({
      pageNo: 1,
      pageSize: 10,
      readStatus: false,
    });
    expect(wrapper.text()).toContain('系统');
    expect(wrapper.text()).toContain('您的验证码是 {code}');
    expect(wrapper.text()).toContain('未读');
    expect(wrapper.find('[data-test="pagination"]').exists()).toBe(false);
  });

  it('switches tabs and queries with the matching read status', async () => {
    const wrapper = mountPage();
    await flushPromises();

    await wrapper.find('[data-tab="read"]').trigger('click');
    await flushPromises();
    expect(getNotifyMessagePage).toHaveBeenLastCalledWith({
      pageNo: 1,
      pageSize: 10,
      readStatus: true,
    });

    await wrapper.find('[data-tab="all"]').trigger('click');
    await flushPromises();
    expect(getNotifyMessagePage).toHaveBeenLastCalledWith({
      pageNo: 1,
      pageSize: 10,
      readStatus: undefined,
    });
  });

  it('marks a single message as read and reloads the list', async () => {
    const wrapper = mountPage();
    await flushPromises();

    await textButton(wrapper, '标记已读').trigger('click');
    await flushPromises();

    expect(updateNotifyMessageRead).toHaveBeenCalledWith(6);
    expect(showSuccessMessage).toHaveBeenCalledWith('已标记为已读');
    expect(getNotifyMessagePage).toHaveBeenCalledTimes(2);
  });

  it('marks only unread messages when marking all as read', async () => {
    vi.mocked(getNotifyMessagePage).mockResolvedValue({
      list: [
        message({ id: 6, readStatus: false }),
        message({ id: 7, readStatus: true }),
        message({ id: 8, readStatus: false }),
      ],
      total: 3,
    });
    const wrapper = mountPage();
    await flushPromises();

    await textButton(wrapper, '全部已读').trigger('click');
    await flushPromises();

    expect(updateNotifyMessageRead).toHaveBeenCalledTimes(2);
    expect(updateNotifyMessageRead).toHaveBeenCalledWith(6);
    expect(updateNotifyMessageRead).toHaveBeenCalledWith(8);
    expect(showSuccessMessage).toHaveBeenCalledWith('全部标记为已读');
    expect(getNotifyMessagePage).toHaveBeenCalledTimes(2);
  });

  it('clears the list and shows the empty state when loading fails', async () => {
    vi.mocked(getNotifyMessagePage).mockRejectedValue(new Error('network'));
    const wrapper = mountPage();
    await flushPromises();

    expect(wrapper.find('[data-test="empty"]').exists()).toBe(true);
    expect(wrapper.text()).not.toContain('您的验证码是 {code}');
  });

  it('paginates when the total exceeds the page size', async () => {
    vi.mocked(getNotifyMessagePage).mockResolvedValue({
      list: [message()],
      total: 25,
    });
    const wrapper = mountPage();
    await flushPromises();

    expect(wrapper.find('[data-test="pagination"]').exists()).toBe(true);

    await wrapper.find('[data-test="pagination"]').trigger('click');
    await flushPromises();

    expect(getNotifyMessagePage).toHaveBeenLastCalledWith({
      pageNo: 2,
      pageSize: 10,
      readStatus: false,
    });
  });
});

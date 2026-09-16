import { flushPromises, mount } from '@vue/test-utils';

import { useUserStore } from '@vben/stores';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import BasicApplicationLayout from './basic.vue';

const mocks = vi.hoisted(() => ({
  authStore: { logout: vi.fn() },
  destroyWatermark: vi.fn(),
  getUnreadCount: vi.fn(),
  getUnreadList: vi.fn(),
  routerPush: vi.fn(),
  updateAllRead: vi.fn(),
  updateRead: vi.fn(),
  updateWatermark: vi.fn(),
  userStore: {
    userInfo: {
      avatar: '/avatar.png',
      email: 'masked@example.com',
      id: 7,
      nickname: 'Operator',
      username: 'operator',
    },
  },
}));

vi.mock('@vben/common-ui', async () => {
  const { defineComponent, h } = await import('vue');
  return {
    AuthenticationLoginExpiredModal: defineComponent({
      name: 'AuthenticationLoginExpiredModal',
      setup:
        (_props, { slots }) =>
        () =>
          h('section', slots.default?.()),
    }),
  };
});
vi.mock('@vben/hooks', () => ({
  useWatermark: () => ({
    destroyWatermark: mocks.destroyWatermark,
    updateWatermark: mocks.updateWatermark,
  }),
}));
vi.mock('@vben/icons', () => ({
  AntdProfileOutlined: { name: 'ProfileIcon' },
}));
vi.mock('@vben/layouts', async () => {
  const { defineComponent, h } = await import('vue');
  const BasicLayout = defineComponent({
    name: 'BasicLayout',
    emits: ['clear-preferences-and-logout'],
    setup:
      (_props, { slots }) =>
      () =>
        h(
          'main',
          Object.values(slots).flatMap((slot) => slot?.() ?? []),
        ),
  });
  const Notification = defineComponent({
    name: 'NotificationPanel',
    props: {
      dot: Boolean,
      notifications: { default: () => [], type: Array },
    },
    emits: ['clear', 'make-all', 'open', 'read', 'view-all'],
    setup: () => () => h('aside'),
  });
  const simpleComponent = (name: string) =>
    defineComponent({ name, setup: () => () => h('div') });
  return {
    BasicLayout,
    LockScreen: simpleComponent('LockScreen'),
    Notification,
    UserDropdown: simpleComponent('UserDropdown'),
  };
});
vi.mock('@vben/preferences', () => ({
  preferences: {
    app: {
      defaultAvatar: '/default-avatar.png',
      watermark: true,
      watermarkContent: '',
    },
  },
}));
vi.mock('@vben/stores', async () => {
  const { reactive } = await import('vue');
  return {
    useAccessStore: () => ({ loginExpired: false }),
    useUserStore: () => reactive(mocks.userStore),
  };
});
vi.mock('@vben/utils', () => ({
  formatDateTime: (value: unknown) => `formatted:${String(value)}`,
}));
vi.mock('#/api/system/notify/message', () => ({
  getUnreadNotifyMessageCount: mocks.getUnreadCount,
  getUnreadNotifyMessageList: mocks.getUnreadList,
  updateAllNotifyMessageRead: mocks.updateAllRead,
  updateNotifyMessageRead: mocks.updateRead,
}));
vi.mock('#/locales', () => ({ $t: (key: string) => key }));
vi.mock('#/router', () => ({ router: { push: mocks.routerPush } }));
vi.mock('#/store', () => ({ useAuthStore: () => mocks.authStore }));
vi.mock('#/views/_core/authentication/login.vue', async () => {
  const { defineComponent } = await import('vue');
  return {
    default: defineComponent({ name: 'LoginForm', template: '<div />' }),
  };
});

describe('basic application layout', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mocks.userStore.userInfo.id = 7;
    vi.useFakeTimers();
    mocks.getUnreadCount.mockResolvedValue(2);
    mocks.getUnreadList.mockResolvedValue([
      {
        createTime: '2026-09-01T10:00:00Z',
        id: 11,
        templateContent: 'Review required',
        templateNickname: 'Security review',
      },
    ]);
    mocks.updateAllRead.mockResolvedValue(undefined);
    mocks.updateRead.mockResolvedValue(undefined);
    mocks.updateWatermark.mockResolvedValue(undefined);
  });

  afterEach(() => vi.useRealTimers());

  it('refreshes unread counts on schedule and tears the poller down on unmount', async () => {
    const wrapper = mount(BasicApplicationLayout);
    await flushPromises();

    expect(mocks.getUnreadCount).toHaveBeenCalledOnce();
    expect(mocks.updateWatermark).toHaveBeenCalledWith({
      content: '7 - Operator',
    });

    await vi.advanceTimersByTimeAsync(2 * 60 * 1000);
    expect(mocks.getUnreadCount).toHaveBeenCalledTimes(2);

    wrapper.unmount();
    await vi.advanceTimersByTimeAsync(2 * 60 * 1000);
    expect(mocks.getUnreadCount).toHaveBeenCalledTimes(2);
  });

  it('maps, reads and clears notification state through shared layout events', async () => {
    const wrapper = mount(BasicApplicationLayout);
    await flushPromises();
    const notification = wrapper.getComponent({ name: 'NotificationPanel' });

    notification.vm.$emit('open', true);
    await flushPromises();
    expect(notification.props('dot')).toBe(true);
    expect(notification.props('notifications')).toEqual([
      expect.objectContaining({
        date: 'formatted:2026-09-01T10:00:00Z',
        id: 11,
        message: 'Review required',
        title: 'Security review',
      }),
    ]);

    notification.vm.$emit('read', { id: 11 });
    await flushPromises();
    expect(mocks.updateRead).toHaveBeenCalledWith(11);
    expect(notification.props('notifications')).toEqual([]);

    notification.vm.$emit('view-all');
    expect(mocks.routerPush).toHaveBeenCalledWith({ name: 'MyNotifyMessage' });

    notification.vm.$emit('clear');
    await flushPromises();
    expect(mocks.updateAllRead).toHaveBeenCalledOnce();
    expect(notification.props('dot')).toBe(false);

    wrapper.unmount();
  });

  it('clears the previous account notifications and updates its watermark', async () => {
    const wrapper = mount(BasicApplicationLayout);
    const notification = wrapper.getComponent({ name: 'NotificationPanel' });
    notification.vm.$emit('open', true);
    await flushPromises();
    expect(notification.props('notifications')).toHaveLength(1);
    const userInfo = useUserStore().userInfo;
    if (!userInfo) throw new Error('Test user is missing');
    userInfo.id = 8;
    await flushPromises();
    expect(notification.props('notifications')).toEqual([]);
    expect(notification.props('dot')).toBe(false);
    expect(mocks.updateWatermark).toHaveBeenLastCalledWith({
      content: '8 - Operator',
    });
    wrapper.unmount();
  });

  it('discards notification data that arrives after switching accounts', async () => {
    let finishList!: (value: unknown[]) => void;
    let finishCount!: (value: number) => void;
    mocks.getUnreadList.mockReturnValue(
      new Promise((resolve) => {
        finishList = resolve;
      }),
    );
    mocks.getUnreadCount.mockReturnValue(
      new Promise((resolve) => {
        finishCount = resolve;
      }),
    );
    const wrapper = mount(BasicApplicationLayout);
    const notification = wrapper.getComponent({ name: 'NotificationPanel' });
    notification.vm.$emit('open', true);
    const userInfo = useUserStore().userInfo;
    if (!userInfo) throw new Error('Test user is missing');
    userInfo.id = 8;
    finishList([{ id: 11, templateContent: 'Previous account message' }]);
    finishCount(99);
    await flushPromises();
    expect(notification.props('notifications')).toEqual([]);
    expect(notification.props('dot')).toBe(false);
    wrapper.unmount();
  });
});

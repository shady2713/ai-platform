import type { VueWrapper } from '@vue/test-utils';

import type { NotificationItem } from './types';

import { mount, shallowMount } from '@vue/test-utils';
import { defineComponent } from 'vue';

import { beforeEach, describe, expect, it, vi } from 'vitest';

import Notification from './notification.vue';

const state = vi.hoisted(() => ({
  openWindow: vi.fn(),
  routerPush: vi.fn(),
}));

vi.mock('vue-router', () => ({
  useRouter: () => ({ push: state.routerPush }),
}));

vi.mock('@vben/icons', () => {
  const component = (name: string) =>
    defineComponent({ name, template: '<i />' });
  return {
    Bell: component('Bell'),
    CircleCheckBig: component('CircleCheckBig'),
    CircleX: component('CircleX'),
    MailCheck: component('MailCheck'),
  };
});

vi.mock('@vben/locales', () => ({
  $t: (key: string) => key,
}));

vi.mock('@vben/utils', () => ({
  openWindow: state.openWindow,
}));

vi.mock('@vben-core/shadcn-ui', () => {
  return {
    VbenButton: defineComponent({
      name: 'VbenButton',
      template: '<button><slot /></button>',
    }),
    VbenIconButton: defineComponent({
      name: 'VbenIconButton',
      template: '<button><slot /></button>',
    }),
    VbenPopover: defineComponent({
      name: 'VbenPopover',
      template: '<div><slot name="trigger" /><slot /></div>',
    }),
    VbenScrollbar: defineComponent({
      name: 'VbenScrollbar',
      template: '<div><slot /></div>',
    }),
  };
});

interface NotificationComponent {
  handleClear: () => void;
  handleClick: (item: NotificationItem) => void;
  handleMakeAll: () => void;
  handleOpen: () => void;
  handleViewAll: () => void;
  navigateTo: (
    link: string,
    query?: Record<string, unknown>,
    state?: Record<string, unknown>,
  ) => void;
  open: boolean;
}

function component(wrapper: VueWrapper) {
  return wrapper.vm as unknown as NotificationComponent;
}

describe('notification popup', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('routes only allowlisted internal paths with their navigation state', () => {
    const wrapper = shallowMount(Notification);
    const query = { source: 'notification' };
    const historyState = { fromNotification: true };

    component(wrapper).navigateTo('/system/notify/my', query, historyState);

    expect(state.routerPush).toHaveBeenCalledWith({
      path: '/system/notify/my',
      query,
      state: historyState,
    });
    expect(state.openWindow).not.toHaveBeenCalled();
  });

  it('opens canonical HTTPS destinations and rejects unsafe links', () => {
    const wrapper = shallowMount(Notification);

    component(wrapper).navigateTo('https://example.com/notices');
    component(wrapper).navigateTo('http://example.com/notices');
    component(wrapper).navigateTo('//example.com/notices');

    expect(state.openWindow).toHaveBeenCalledOnce();
    expect(state.openWindow).toHaveBeenCalledWith(
      'https://example.com/notices',
    );
    expect(state.routerPush).not.toHaveBeenCalled();
  });

  it('navigates only when the clicked notification has a link', () => {
    const wrapper = shallowMount(Notification);
    const item: NotificationItem = {
      avatar: '',
      date: '2026-09-03',
      id: 1,
      message: 'message',
      title: 'title',
    };

    component(wrapper).handleClick(item);
    component(wrapper).handleClick({ ...item, link: '/system/notify/my' });

    expect(state.routerPush).toHaveBeenCalledOnce();
  });

  it('emits popup and bulk action events', () => {
    const wrapper = shallowMount(Notification);

    component(wrapper).handleOpen();
    component(wrapper).handleMakeAll();
    component(wrapper).handleClear();
    component(wrapper).handleViewAll();

    expect(wrapper.emitted('open')).toEqual([[true]]);
    expect(wrapper.emitted('makeAll')).toHaveLength(1);
    expect(wrapper.emitted('clear')).toHaveLength(1);
    expect(wrapper.emitted('viewAll')).toHaveLength(1);
  });

  it('renders linked and static notifications with valid accessible controls', async () => {
    const linked: NotificationItem = {
      avatar: '/avatar-1.png',
      date: '2026-09-03',
      id: 1,
      link: '/system/notify/my',
      message: 'Unread message',
      title: 'Unread notification',
    };
    const staticItem: NotificationItem = {
      avatar: '/avatar-2.png',
      date: '2026-09-03',
      id: 2,
      isRead: true,
      message: 'Read message',
      title: 'Read notification',
    };
    const wrapper = mount(Notification, {
      props: { dot: true, notifications: [linked, staticItem] },
    });
    const rows = wrapper.findAll('li');

    expect(
      wrapper.get('button[aria-label="ui.widgets.notifications"]').element
        .tagName,
    ).toBe('BUTTON');
    expect(rows[0]?.get('.notification-content').element.tagName).toBe(
      'BUTTON',
    );
    expect(rows[0]?.get('.notification-content').attributes('type')).toBe(
      'button',
    );
    expect(rows[1]?.get('.notification-content').element.tagName).toBe('DIV');
    expect(
      wrapper.findAll('img').every((image) => image.attributes('alt') === ''),
    ).toBe(true);

    await rows[0]?.get('.notification-content').trigger('click');
    await rows[0]
      ?.get('button[aria-label="ui.widgets.markAsRead"]')
      .trigger('click');
    await rows[1]?.get('button[aria-label="common.delete"]').trigger('click');

    expect(state.routerPush).toHaveBeenCalledWith({
      path: '/system/notify/my',
      query: {},
      state: undefined,
    });
    expect(wrapper.emitted('read')).toEqual([[linked]]);
    expect(wrapper.emitted('remove')).toEqual([[staticItem]]);
    expect(wrapper.findAll('button button')).toHaveLength(0);
  });

  it('renders the empty state and synchronizes popover model updates', async () => {
    const wrapper = mount(Notification);

    expect(wrapper.text()).toContain('common.noData');
    wrapper.getComponent({ name: 'VbenPopover' }).vm.$emit('update:open', true);
    await wrapper.vm.$nextTick();

    expect(component(wrapper).open).toBe(true);
  });
});

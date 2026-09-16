import { mount } from '@vue/test-utils';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import Analytics from './index.vue';

const state = vi.hoisted(() => ({
  accessStore: { getMenuByPath: vi.fn() },
  router: { push: vi.fn() },
  userStore: {
    userInfo: null as null | Record<string, unknown>,
    userRoles: [] as string[],
  },
}));

vi.mock('vue-router', () => ({
  useRouter: () => state.router,
}));

vi.mock('@vben/stores', () => ({
  useAccessStore: () => state.accessStore,
  useUserStore: () => state.userStore,
}));

vi.mock('element-plus', async () => {
  const { defineComponent, h } = await import('vue');
  const card = defineComponent({
    name: 'ElCard',
    setup(_props, { slots }) {
      return () => h('section', [slots.header?.(), slots.default?.()]);
    },
  });
  const slotOnly = (name: string) =>
    defineComponent({
      name,
      setup(_props, { slots }) {
        return () => h('div', slots.default?.());
      },
    });
  return {
    ElCard: card,
    ElCol: slotOnly('ElCol'),
    ElRow: slotOnly('ElRow'),
    ElTag: slotOnly('ElTag'),
  };
});

function expectGreeting(hour: number, text: string) {
  vi.spyOn(Date.prototype, 'getHours').mockReturnValue(hour);
  const wrapper = mount(Analytics);
  expect(wrapper.find('.welcome-title').text()).toContain(text);
  wrapper.unmount();
}

describe('dashboard analytics welcome page', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    state.userStore.userInfo = {
      avatar: '',
      email: 'admin@example.com',
      nickname: '管理员',
      username: 'admin',
    };
    state.userStore.userRoles = ['admin'];
    state.accessStore.getMenuByPath.mockImplementation(
      (path: string) => path === '/system/user' || path === '/system/role',
    );
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('greets the user by nickname with a time-based salutation', () => {
    expectGreeting(8, '管理员');
  });

  it('falls back to username and generic greeting without profile data', () => {
    state.userStore.userInfo = { username: 'tester' };
    expectGreeting(19, 'tester');
  });

  it('renders user info, role tags and the avatar image', () => {
    state.userStore.userInfo = {
      avatar: 'http://cdn.example/me.png',
      email: 'admin@example.com',
      nickname: '管理员',
      username: 'admin',
    };
    const wrapper = mount(Analytics);

    for (const label of ['admin', '管理员', 'admin@example.com']) {
      expect(wrapper.text()).toContain(label);
    }
    expect(wrapper.find('.welcome-avatar img').attributes('src')).toBe(
      'http://cdn.example/me.png',
    );
  });

  it('renders a dash for missing user info fields', () => {
    state.userStore.userInfo = {};
    state.userStore.userRoles = [];
    const wrapper = mount(Analytics);

    expect(wrapper.find('.welcome-title').text()).toContain('用户');
    expect(wrapper.findAll('.info-value').at(0)?.text()).toBe('-');
    expect(wrapper.findAll('.info-value').at(3)?.text()).toBe('-');
    expect(wrapper.find('.welcome-avatar').exists()).toBe(false);
  });

  it('only lists quick links the user can access and navigates on click', async () => {
    const wrapper = mount(Analytics);

    const links = wrapper.findAll('.quick-link-item');
    expect(links).toHaveLength(2);
    const [firstLink, secondLink] = links;
    expect(firstLink?.text()).toContain('用户管理');

    await secondLink?.trigger('click');
    expect(state.router.push).toHaveBeenCalledWith('/system/role');
  });
});

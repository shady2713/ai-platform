import type { MenuRecordRaw } from '@vben/types';

import { mount } from '@vue/test-utils';
import { defineComponent, h } from 'vue';

import { describe, expect, it, vi } from 'vitest';

import LayoutExtraMenu from '../extra-menu.vue';
import LayoutMenu from '../menu.vue';
import LayoutMixedMenu from '../mixed-menu.vue';

const route = vi.hoisted(() => ({ meta: {}, path: '/system' }));

const router = vi.hoisted(() => ({
  afterEach: vi.fn(),
  beforeEach: vi.fn(),
  getRoutes: vi.fn(() => []),
  push: vi.fn(),
  resolve: vi.fn((path: string) => ({ href: path })),
}));

vi.mock('vue-router', () => ({
  useRoute: () => route,
  useRouter: () => router,
}));

const MenuStub = defineComponent({
  name: 'MenuStub',
  emits: ['open', 'select'],
  setup(_, { emit, slots }) {
    return () =>
      h('div', { id: 'menu-stub' }, [
        h('button', { id: 'sel', onClick: () => emit('select', '/a') }, 'sel'),
        h(
          'button',
          { id: 'opn', onClick: () => emit('open', '/a', ['/a']) },
          'opn',
        ),
        slots.default?.(),
      ]);
  },
});

const menuStubs = {
  Menu: MenuStub,
  // the barrel 'Menu' is the MenuView root; stub it so the inner
  // Menu/SubMenu chain (which needs the real context) never renders
  MenuView: MenuStub,
  SubMenu: { render: () => null },
};

const menus: MenuRecordRaw[] = [
  {
    children: [{ name: 'User', parents: ['/system'], path: '/system/user' }],
    name: 'System',
    path: '/system',
  },
];

describe('layoutMenu wrapper', () => {
  it('renders the underlying Menu with the forwarded props', () => {
    const wrapper = mount(LayoutMenu, {
      global: { stubs: menuStubs },
      props: { menus, mode: 'vertical', theme: 'dark' },
    });
    expect(wrapper.find('#menu-stub').exists()).toBe(true);
  });

  it('re-emits select with the mode attached', async () => {
    const wrapper = mount(LayoutMenu, {
      global: { stubs: menuStubs },
      props: { menus, mode: 'horizontal' },
    });
    await wrapper.get('#sel').trigger('click');
    expect(wrapper.emitted('select')?.[0]).toEqual(['/a', 'horizontal']);
  });

  it('re-emits open events unchanged', async () => {
    const wrapper = mount(LayoutMenu, {
      global: { stubs: menuStubs },
      props: { menus },
    });
    await wrapper.get('#opn').trigger('click');
    expect(wrapper.emitted('open')?.[0]).toEqual(['/a', ['/a']]);
  });
});

describe('layoutExtraMenu', () => {
  it('navigates when a menu item is selected', async () => {
    route.path = '/system';
    const wrapper = mount(LayoutExtraMenu, {
      global: { stubs: menuStubs },
      props: { menus },
    });
    await wrapper.get('#sel').trigger('click');
    expect(router.push).toHaveBeenCalledWith({
      path: '/a',
      query: {},
    });
  });
});

describe('layoutMixedMenu', () => {
  it('emits the default select for the current route on mount', () => {
    route.path = '/system/user';
    route.meta = { activePath: '/system/user' };
    const wrapper = mount(LayoutMixedMenu, {
      props: {
        activePath: '/system/user',
        menus,
      },
      global: {
        stubs: {
          VbenIcon: {
            template: '<i class="stub-icon"></i>',
          },
          VbenTooltip: { template: '<div><slot/><slot name="trigger"/></div>' },
          tooltip: { template: '<div><slot/><slot name="trigger"/></div>' },
        },
      },
    });
    const emitted = wrapper.emitted('defaultSelect');
    expect(emitted?.[0]?.[0]).toMatchObject({ path: '/system/user' });
    expect(emitted?.[0]?.[1]).toMatchObject({ path: '/system' });
  });

  it('forwards enter and select events with the menu record', async () => {
    route.path = '/system';
    route.meta = {};
    const wrapper = mount(LayoutMixedMenu, {
      props: { activePath: '', menus },
      global: { stubs: { VbenIcon: { template: '<i></i>' } } },
    });
    const items = wrapper.findAll('li');
    await items[0]?.trigger('click');
    expect(wrapper.emitted('select')?.[0]?.[0]).toMatchObject({
      path: '/system',
    });
    await items[0]?.trigger('mouseenter');
    expect(wrapper.emitted('enter')?.[0]?.[0]).toMatchObject({
      path: '/system',
    });
  });
});

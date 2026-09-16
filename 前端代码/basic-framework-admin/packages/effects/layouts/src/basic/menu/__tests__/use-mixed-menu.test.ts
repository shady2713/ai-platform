import type { MenuRecordRaw } from '@vben/types';

import {
  preferences,
  resetPreferences,
  updatePreferences,
} from '@vben/preferences';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { useMixedMenu } from '../use-mixed-menu';

const route = vi.hoisted(() => ({
  meta: {},
  path: '/system/user',
}));

const router = vi.hoisted(() => ({
  afterEach: vi.fn(),
  beforeEach: vi.fn(),
  getRoutes: vi.fn(() => []),
  push: vi.fn(),
  resolve: vi.fn((path: string) => ({ href: path })),
}));

const storeState = vi.hoisted(() => ({
  accessMenus: [] as MenuRecordRaw[],
}));

vi.mock('vue-router', () => ({
  useRoute: () => route,
  useRouter: () => router,
}));

vi.mock('@vben/stores', () => ({
  useAccessStore: () => ({
    get accessMenus() {
      return storeState.accessMenus;
    },
  }),
}));

const menus: MenuRecordRaw[] = [
  {
    children: [
      {
        name: 'User',
        parents: ['/system'],
        path: '/system/user',
      },
    ],
    name: 'System',
    path: '/system',
  },
  { name: 'Dash', path: '/dash' },
];

describe('useMixedMenu', () => {
  beforeEach(() => {
    resetPreferences();
    storeState.accessMenus = menus;
    route.path = '/system/user';
    route.meta = {};
    router.push.mockClear();
  });

  afterEach(() => {
    resetPreferences();
  });

  it('exposes the plain access menus when no split navigation is needed', () => {
    const { headerMenus, mixExtraMenus, sidebarMenus, sidebarVisible } =
      useMixedMenu();
    expect(headerMenus.value).toHaveLength(2);
    expect(headerMenus.value[0]).toEqual(menus[0]);
    expect(sidebarMenus.value).toHaveLength(2);
    expect(sidebarVisible.value).toBe(preferences.sidebar.enable);
    expect(mixExtraMenus.value).toEqual([]);
  });

  it('computes the active paths from route meta or path', () => {
    const { headerActive, sidebarActive } = useMixedMenu();
    expect(sidebarActive.value).toBe('/system/user');
    expect(headerActive.value).toBe('/system/user');
    route.meta = { activePath: '/dash' };
    const next = useMixedMenu();
    expect(next.sidebarActive.value).toBe('/dash');
    expect(next.headerActive.value).toBe('/dash');
  });

  it('splits the header and sidebar menus when split navigation is enabled', () => {
    updatePreferences({
      app: { layout: 'mixed-nav' },
      navigation: { split: true },
    });
    const { headerActive, headerMenus, sidebarMenus, sidebarVisible } =
      useMixedMenu();
    expect(headerActive.value).toBe('/system');
    expect(headerMenus.value).toHaveLength(2);
    // children are detached in the header menu
    expect(headerMenus.value[0]?.children).toEqual([]);
    expect(sidebarMenus.value).toHaveLength(1);
    expect(sidebarVisible.value).toBe(true);
  });

  it('handleMenuSelect with vertical mode navigates directly', async () => {
    const { handleMenuSelect } = useMixedMenu();
    await handleMenuSelect('/system/user', 'vertical');
    expect(router.push).toHaveBeenCalledWith({
      path: '/system/user',
      query: {},
    });
  });

  it('handleMenuSelect splits the root menu children into the sidebar', async () => {
    updatePreferences({
      app: { layout: 'mixed-nav' },
      sidebar: { autoActivateChild: true },
    });
    const { handleMenuSelect, sidebarMenus } = useMixedMenu();
    await handleMenuSelect('/system');
    expect(sidebarMenus.value).toHaveLength(1);
    expect(sidebarMenus.value[0]?.path).toBe('/system/user');
    // auto activate the remembered child
    expect(router.push).toHaveBeenCalledWith({
      path: '/system/user',
      query: {},
    });
  });

  it('handleMenuSelect navigates leaf roots directly without a sidebar', async () => {
    updatePreferences({ app: { layout: 'mixed-nav' } });
    const { handleMenuSelect, sidebarMenus } = useMixedMenu();
    expect(sidebarMenus.value).toHaveLength(1);
    await handleMenuSelect('/dash');
    expect(sidebarMenus.value).toHaveLength(0);
    expect(router.push).toHaveBeenCalledWith({ path: '/dash', query: {} });
  });

  it('handleMenuOpen activates the default child of the opened root', async () => {
    updatePreferences({ sidebar: { autoActivateChild: true } });
    const { handleMenuOpen } = useMixedMenu();
    await handleMenuOpen('/system', ['/system']);
    expect(router.push).toHaveBeenCalledWith({
      path: '/system/user',
      query: {},
    });
  });

  it('handleMenuOpen ignores deep parent chains', async () => {
    const { handleMenuOpen } = useMixedMenu();
    await handleMenuOpen('/system', ['/root', '/system']);
    expect(router.push).not.toHaveBeenCalled();
  });
});

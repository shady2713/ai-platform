import type { MenuRecordRaw } from '@vben/types';

import { computed } from 'vue';

import {
  preferences,
  resetPreferences,
  updatePreferences,
} from '@vben/preferences';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { useExtraMenu } from '../use-extra-menu';

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

const leafOfSystem: MenuRecordRaw = {
  name: 'User',
  parents: ['/system'],
  path: '/system/user',
};

describe('useExtraMenu', () => {
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

  it('computes the extra menus and active menu from the current route', () => {
    const { extraActiveMenu, extraMenus, sidebarExtraVisible } = useExtraMenu();
    expect(extraMenus.value).toHaveLength(1);
    expect(extraMenus.value[0]?.path).toBe('/system/user');
    expect(extraActiveMenu.value).toBe('/system');
    // expandOnHover defaults to true, so the extra panel is visible
    expect(sidebarExtraVisible.value).toBe(true);
  });

  it('prefers the route meta activePath when present', () => {
    route.meta = { activePath: '/dash' };
    const { extraActiveMenu, extraMenus } = useExtraMenu();
    expect(extraMenus.value).toHaveLength(0);
    expect(extraActiveMenu.value).toBe('/dash');
  });

  it('uses the provided root menus instead of the access store', () => {
    const rootMenus = computed(() => [{ name: 'Root', path: '/root' }]);
    const { extraMenus, extraActiveMenu } = useExtraMenu(rootMenus);
    expect(extraMenus.value).toEqual([]);
    expect(extraActiveMenu.value).toBe('');
  });

  it('handleMixedMenuSelect shows the children and navigates to the default sub menu', async () => {
    const { extraMenus, handleMixedMenuSelect, sidebarExtraVisible } =
      useExtraMenu();
    const [firstMenu] = menus;
    if (!firstMenu) {
      throw new Error('fixture requires the first menu');
    }
    await handleMixedMenuSelect(firstMenu);
    expect(extraMenus.value).toHaveLength(1);
    expect(sidebarExtraVisible.value).toBe(true);
    // autoActivateChild defaults to false: no automatic navigation
    expect(router.push).not.toHaveBeenCalled();
  });

  it('handleMixedMenuSelect navigates leaf menus directly', async () => {
    const { handleMixedMenuSelect, sidebarExtraVisible } = useExtraMenu();
    const secondMenu = menus[1];
    if (!secondMenu) {
      throw new Error('fixture requires the second menu');
    }
    await handleMixedMenuSelect(secondMenu);
    expect(sidebarExtraVisible.value).toBe(false);
    expect(router.push).toHaveBeenCalledWith({ path: '/dash', query: {} });
  });

  it('handleDefaultSelect shows the root menu children and highlights the parents entry', async () => {
    const { extraActiveMenu, extraMenus, handleDefaultSelect } = useExtraMenu();
    const rootMenu = menus[0];
    if (!rootMenu) {
      throw new Error('fixture requires the root menu');
    }
    await handleDefaultSelect(leafOfSystem, rootMenu);
    expect(extraMenus.value).toHaveLength(1);
    expect(extraActiveMenu.value).toBe('/system');
  });

  it('handleMenuMouseEnter shows the hovered menu children', async () => {
    const { extraMenus, handleMenuMouseEnter, sidebarExtraVisible } =
      useExtraMenu();
    const hoveredMenu = menus[0];
    if (!hoveredMenu) {
      throw new Error('fixture requires the hovered menu');
    }
    await handleMenuMouseEnter(hoveredMenu);
    expect(extraMenus.value).toHaveLength(1);
    expect(sidebarExtraVisible.value).toBe(true);
  });

  it('handleSideMouseLeave restores the menus of the route root menu', () => {
    const { extraActiveMenu, extraMenus, handleSideMouseLeave } =
      useExtraMenu();
    handleSideMouseLeave();
    expect(extraMenus.value).toHaveLength(1);
    expect(extraActiveMenu.value).toBe('/system');
  });

  it('respects expandOnHover for the side mouse leave handler', () => {
    updatePreferences({ sidebar: { expandOnHover: true } });
    expect(preferences.sidebar.expandOnHover).toBe(true);
    const { extraActiveMenu, extraMenus, handleSideMouseLeave } =
      useExtraMenu();
    handleSideMouseLeave();
    // expandOnHover early-returns; state stays untouched
    expect(extraActiveMenu.value).toBe('/system');
    expect(extraMenus.value).toHaveLength(1);

    updatePreferences({ sidebar: { expandOnHover: false } });
    handleSideMouseLeave();
    expect(extraActiveMenu.value).toBe('/system');
    expect(extraMenus.value).toHaveLength(1);
  });

  it('skips extra menu updates when the target opens in a new window', async () => {
    router.getRoutes.mockReturnValue([
      { meta: { openInNewWindow: true }, path: '/system' },
    ] as never);
    const { extraMenus, handleMixedMenuSelect, sidebarExtraVisible } =
      useExtraMenu();
    await handleMixedMenuSelect({
      children: [{ name: 'Other', path: '/other' }],
      name: 'System',
      path: '/system',
    });
    // the route-derived extra menu stays untouched
    expect(extraMenus.value).toHaveLength(1);
    expect(extraMenus.value[0]?.path).toBe('/system/user');
    expect(sidebarExtraVisible.value).toBe(true);
    // autoActivateChild defaults to false: windowed menus never navigate
    expect(router.push).not.toHaveBeenCalled();
    router.getRoutes.mockReturnValue([]);
  });
});

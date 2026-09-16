import type { RouteLocationNormalized } from 'vue-router';

import { beforeEach, describe, expect, it, vi } from 'vitest';

import { useTabs } from './use-tabs';

const mocks = vi.hoisted(() => ({
  route: {
    fullPath: '/current',
    meta: {},
    path: '/current',
  },
  router: { name: 'router' },
  store: {
    affixTabs: [] as RouteLocationNormalized[],
    closeAllTabs: vi.fn(),
    closeLeftTabs: vi.fn(),
    closeOtherTabs: vi.fn(),
    closeRightTabs: vi.fn(),
    closeTab: vi.fn(),
    closeTabByKey: vi.fn(),
    getTabs: [] as RouteLocationNormalized[],
    openTabInNewWindow: vi.fn(),
    pinTab: vi.fn(),
    refresh: vi.fn(),
    resetTabTitle: vi.fn(),
    setTabTitle: vi.fn(),
    setUpdateTime: vi.fn(),
    toggleTabPin: vi.fn(),
    unpinTab: vi.fn(),
  },
}));

vi.mock('vue-router', () => ({
  useRoute: () => mocks.route,
  useRouter: () => mocks.router,
}));

vi.mock('@vben/stores', () => ({
  getTabKey: (tab: RouteLocationNormalized) =>
    tab.meta.fullPathKey === false ? tab.path : tab.fullPath,
  useTabbarStore: () => mocks.store,
}));

function createRoute(
  path: string,
  fullPath = path,
  meta?: RouteLocationNormalized['meta'],
): RouteLocationNormalized {
  return {
    fullPath,
    hash: '',
    matched: [],
    meta: meta ?? { title: '' },
    name: path,
    params: {},
    path,
    query: {},
    redirectedFrom: undefined,
  };
}

describe('useTabs', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    Object.assign(mocks.route, createRoute('/current'));
    mocks.store.getTabs = [];
    mocks.store.affixTabs = [];
  });

  it('delegates tab operations with explicit tabs and the router', async () => {
    const api = useTabs();
    const target = createRoute('/target');

    await api.closeLeftTabs(target);
    await api.closeRightTabs(target);
    await api.closeOtherTabs(target);
    await api.closeCurrentTab(target);
    await api.closeAllTabs();
    await api.pinTab(target);
    await api.unpinTab(target);
    await api.toggleTabPin(target);
    await api.openTabInNewWindow(target);
    await api.closeTabByKey('/target');
    await api.refreshTab('Target');

    expect(mocks.store.closeLeftTabs).toHaveBeenCalledWith(target);
    expect(mocks.store.closeRightTabs).toHaveBeenCalledWith(target);
    expect(mocks.store.closeOtherTabs).toHaveBeenCalledWith(target);
    expect(mocks.store.closeTab).toHaveBeenCalledWith(target, mocks.router);
    expect(mocks.store.closeAllTabs).toHaveBeenCalledWith(mocks.router);
    expect(mocks.store.pinTab).toHaveBeenCalledWith(target);
    expect(mocks.store.unpinTab).toHaveBeenCalledWith(target);
    expect(mocks.store.toggleTabPin).toHaveBeenCalledWith(target);
    expect(mocks.store.openTabInNewWindow).toHaveBeenCalledWith(target);
    expect(mocks.store.closeTabByKey).toHaveBeenCalledWith(
      '/target',
      mocks.router,
    );
    expect(mocks.store.refresh).toHaveBeenCalledWith('Target');
  });

  it('uses the current route and router when optional arguments are omitted', async () => {
    const api = useTabs();

    await api.closeLeftTabs();
    await api.closeRightTabs();
    await api.closeOtherTabs();
    await api.closeCurrentTab();
    await api.pinTab();
    await api.unpinTab();
    await api.toggleTabPin();
    await api.openTabInNewWindow();
    await api.refreshTab();

    expect(mocks.store.closeLeftTabs).toHaveBeenCalledWith(mocks.route);
    expect(mocks.store.closeRightTabs).toHaveBeenCalledWith(mocks.route);
    expect(mocks.store.closeOtherTabs).toHaveBeenCalledWith(mocks.route);
    expect(mocks.store.closeTab).toHaveBeenCalledWith(
      mocks.route,
      mocks.router,
    );
    expect(mocks.store.pinTab).toHaveBeenCalledWith(mocks.route);
    expect(mocks.store.unpinTab).toHaveBeenCalledWith(mocks.route);
    expect(mocks.store.toggleTabPin).toHaveBeenCalledWith(mocks.route);
    expect(mocks.store.openTabInNewWindow).toHaveBeenCalledWith(mocks.route);
    expect(mocks.store.refresh).toHaveBeenCalledWith(mocks.router);
  });

  it('updates the presentation timestamp before setting or resetting a title', async () => {
    const api = useTabs();

    await api.setTabTitle('Dashboard');
    await api.resetTabTitle();

    expect(mocks.store.setUpdateTime).toHaveBeenCalledTimes(2);
    expect(mocks.store.setTabTitle).toHaveBeenCalledWith(
      mocks.route,
      'Dashboard',
    );
    expect(mocks.store.resetTabTitle).toHaveBeenCalledWith(mocks.route);
  });

  it('treats tabs with the same path but different keys as different tabs', () => {
    const current = createRoute('/detail', '/detail?pageKey=first');
    const target = createRoute('/detail', '/detail?pageKey=second');
    Object.assign(mocks.route, current);
    mocks.store.getTabs = [current, target];
    const api = useTabs();

    expect(api.getTabDisableState(target)).toEqual({
      disabledCloseAll: false,
      disabledCloseCurrent: false,
      disabledCloseLeft: true,
      disabledCloseOther: true,
      disabledCloseRight: true,
      disabledRefresh: true,
    });
  });

  it('enables valid operations for a current middle tab', () => {
    const affix = createRoute('/home', '/home', {
      affixTab: true,
      title: 'Home',
    });
    const left = createRoute('/left');
    const current = createRoute('/current');
    const right = createRoute('/right');
    Object.assign(mocks.route, current);
    mocks.store.affixTabs = [affix];
    mocks.store.getTabs = [affix, left, current, right];
    const api = useTabs();

    expect(api.getTabDisableState()).toEqual({
      disabledCloseAll: false,
      disabledCloseCurrent: false,
      disabledCloseLeft: false,
      disabledCloseOther: false,
      disabledCloseRight: false,
      disabledRefresh: false,
    });
  });

  it('disables destructive operations when only an affix tab remains', () => {
    const affix = createRoute('/current', '/current', {
      affixTab: true,
      title: 'Current',
    });
    Object.assign(mocks.route, affix);
    mocks.store.affixTabs = [affix];
    mocks.store.getTabs = [affix];
    const api = useTabs();

    expect(api.getTabDisableState()).toEqual({
      disabledCloseAll: true,
      disabledCloseCurrent: true,
      disabledCloseLeft: true,
      disabledCloseOther: true,
      disabledCloseRight: true,
      disabledRefresh: false,
    });
  });
});

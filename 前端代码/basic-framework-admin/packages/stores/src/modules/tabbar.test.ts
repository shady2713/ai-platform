import type { RouteMeta } from 'vue-router';

import type { TabDefinition } from '@vben-core/typings';

import { createRouter, createWebHistory } from 'vue-router';

import { preferences } from '@vben-core/preferences';

import { createPinia, setActivePinia } from 'pinia';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { getTabKey, useTabbarStore } from './tabbar';

vi.mock('@vben-core/preferences', () => ({
  preferences: {
    tabbar: {
      maxCount: 0,
      visitHistory: true,
    },
  },
}));

describe('useTabbarStore', () => {
  const router = createRouter({
    history: createWebHistory(),
    routes: [
      {
        children: [
          {
            component: { template: '<div />' },
            name: 'NestedChild',
            path: 'child',
          },
        ],
        component: { template: '<router-view />' },
        name: 'Nested',
        path: '/nested',
      },
      {
        component: { template: '<div />' },
        name: 'TestRoute',
        path: '/:pathMatch(.*)*',
      },
    ],
  });
  router.push = vi.fn();
  router.replace = vi.fn();

  function createTab(
    path: string,
    name: string,
    meta: RouteMeta = {},
  ): TabDefinition {
    const resolved = router.resolve(path);
    return {
      ...resolved,
      key: path,
      meta: { ...resolved.meta, ...meta },
      name,
      params: {},
    };
  }

  beforeEach(() => {
    setActivePinia(createPinia());
    preferences.tabbar.maxCount = 0;
    preferences.tabbar.visitHistory = true;
    vi.clearAllMocks();
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('adds a new tab', () => {
    const store = useTabbarStore();
    const tab = createTab('/home', 'Home');
    const addNewTab = store.addTab(tab);
    expect(store.tabs.length).toBe(1);
    expect(store.tabs[0]).toEqual(addNewTab);
  });

  it('adds a new tab if it does not exist', () => {
    const store = useTabbarStore();
    const newTab = createTab('/new', 'New');
    const addNewTab = store.addTab(newTab);
    expect(store.tabs).toContainEqual(addNewTab);
  });

  it('updates an existing tab instead of adding a new one', () => {
    const store = useTabbarStore();
    const initialTab = createTab('/existing', 'Existing', {
      fullPathKey: false,
    });
    store.addTab(initialTab);
    const updatedTab = { ...initialTab, query: { id: '1' } };
    store.addTab(updatedTab);
    expect(store.tabs.length).toBe(1);
    expect(store.tabs[0]?.query).toEqual({ id: '1' });
  });

  it('generates a key and ignores tabs hidden by their own or matched metadata', () => {
    const store = useTabbarStore();
    const visible = createTab('/visible', 'Visible');
    delete visible.key;
    const hidden = createTab('/hidden', 'Hidden', { hideInTab: true });
    const nestedHidden = createTab('/nested/child', 'NestedChild');
    const matched = nestedHidden.matched?.[0];
    if (matched) {
      matched.meta.hideInTab = true;
    }

    expect(store.addTab(visible).key).toBe('/visible');
    store.addTab(hidden);
    store.addTab(nestedHidden);

    expect(store.tabs).toHaveLength(1);
  });

  it('enforces the global tab limit without evicting an affix tab', () => {
    preferences.tabbar.maxCount = 2;
    const store = useTabbarStore();
    store.addTab(createTab('/affix', 'Affix', { affixTab: true }));
    store.addTab(createTab('/first', 'First'));

    store.addTab(createTab('/second', 'Second'));

    expect(store.tabs.map((tab) => tab.name)).toEqual(['Affix', 'Second']);
  });

  it('enforces a route-specific open-tab limit', () => {
    const store = useTabbarStore();
    const first = createTab('/detail?pageKey=first', 'Detail', {
      maxNumOfOpenTab: 1,
    });
    const second = createTab('/detail?pageKey=second', 'Detail', {
      maxNumOfOpenTab: 1,
    });

    store.addTab(first);
    store.addTab(second);

    expect(store.tabs).toHaveLength(1);
    expect(store.tabs[0]?.key).toBe('/detail?pageKey=second');
  });

  it('preserves affix state and a custom title when merging an existing tab', () => {
    const store = useTabbarStore();
    const initial = createTab('/fixed', 'Fixed', {
      affixTab: true,
      newTabTitle: 'Custom',
    });
    store.addTab(initial);

    store.addTab(createTab('/fixed', 'Fixed', { affixTab: false }));

    expect(store.tabs[0]?.meta.affixTab).toBe(true);
    expect(store.tabs[0]?.meta.newTabTitle).toBe('Custom');
  });

  it('closes all tabs', async () => {
    const store = useTabbarStore();
    store.addTab(createTab('/home', 'Home'));
    router.replace = vi.fn();

    await store.closeAllTabs(router);

    expect(store.tabs.length).toBe(1);
  });

  it('closes a non-affix tab', () => {
    const store = useTabbarStore();
    const tab = createTab('/closable', 'Closable');
    store.tabs.push(tab);
    store._close(tab);
    expect(store.tabs.length).toBe(0);
  });

  it('does not close an affix tab', () => {
    const store = useTabbarStore();
    const affixTab = createTab('/affix', 'Affix', { affixTab: true });
    store.tabs.push(affixTab);
    store._close(affixTab);
    expect(store.tabs.length).toBe(1);
  });

  it('returns all cache tabs', () => {
    const store = useTabbarStore();
    store.cachedTabs.add('Home');
    store.cachedTabs.add('About');
    expect(store.getCachedTabs).toEqual(['Home', 'About']);
  });

  it('returns all tabs, including affix tabs', () => {
    const store = useTabbarStore();
    const normalTab = createTab('/normal', 'Normal');
    const affixTab = createTab('/affix', 'Affix', { affixTab: true });
    store.tabs.push(normalTab, affixTab);
    expect(store.getTabs).toContainEqual(normalTab);
    expect(store.affixTabs).toContainEqual(affixTab);
  });

  it('navigates to a specific tab', async () => {
    const store = useTabbarStore();
    const tab = createTab('/dashboard', 'Dashboard');

    await store._goToTab(tab, router);

    expect(router.replace).toHaveBeenCalledWith({
      params: {},
      path: '/dashboard',
      query: {},
    });
  });

  it('closes multiple tabs by paths', async () => {
    const store = useTabbarStore();
    store.addTab(createTab('/home', 'Home'));
    store.addTab(createTab('/about', 'About'));
    store.addTab(createTab('/contact', 'Contact'));

    await store._bulkCloseByKeys(['/home', '/contact']);

    expect(store.tabs).toHaveLength(1);
    expect(store.tabs[0]?.name).toBe('About');
  });

  it('preserves affix tabs and supports legacy tabs without an explicit key', async () => {
    const store = useTabbarStore();
    const affixTab = store.addTab(
      createTab('/affix', 'Affix', { affixTab: true }),
    );
    const legacyTab = createTab('/legacy', 'Legacy');
    delete legacyTab.key;
    store.tabs.push(legacyTab);

    await store._bulkCloseByKeys(['/affix', '/legacy']);

    expect(store.tabs).toEqual([affixTab]);
    expect(store.visitHistory.toArray()).toEqual(['/affix']);
  });

  it('closes all tabs to the left of the specified tab', async () => {
    const store = useTabbarStore();
    store.addTab(createTab('/home', 'Home'));
    store.addTab(createTab('/about', 'About'));
    const targetTab = createTab('/contact', 'Contact');
    const addTargetTab = store.addTab(targetTab);
    await store.closeLeftTabs(addTargetTab);

    expect(store.tabs).toHaveLength(1);
    expect(store.tabs[0]?.name).toBe('Contact');
  });

  it('closes all tabs except the specified tab', async () => {
    const store = useTabbarStore();
    store.addTab(createTab('/home', 'Home'));
    const targetTab = createTab('/about', 'About');
    const addTargetTab = store.addTab(targetTab);
    store.addTab(createTab('/contact', 'Contact'));

    await store.closeOtherTabs(addTargetTab);

    expect(store.tabs).toHaveLength(1);
    expect(store.tabs[0]?.name).toBe('About');
  });

  it('closes all tabs to the right of the specified tab', async () => {
    const store = useTabbarStore();
    const targetTab = createTab('/home', 'Home');
    const addTargetTab = store.addTab(targetTab);
    store.addTab(createTab('/about', 'About'));
    store.addTab(createTab('/contact', 'Contact'));

    await store.closeRightTabs(addTargetTab);

    expect(store.tabs).toHaveLength(1);
    expect(store.tabs[0]?.name).toBe('Home');
  });

  it('closes the tab with the specified key', async () => {
    const store = useTabbarStore();
    const keyToClose = '/about';
    store.addTab(createTab('/home', 'Home'));
    store.addTab(createTab(keyToClose, 'About'));
    store.addTab(createTab('/contact', 'Contact'));

    await store.closeTabByKey(keyToClose, router);

    expect(store.tabs).toHaveLength(2);
    expect(
      store.tabs.find((tab) => tab.fullPath === keyToClose),
    ).toBeUndefined();
  });

  it('closes the active tab, navigates to its neighbor, and updates cache', async () => {
    preferences.tabbar.visitHistory = false;
    const store = useTabbarStore();
    const home = store.addTab(createTab('/home', 'Home', { keepAlive: true }));
    const about = store.addTab(
      createTab('/about', 'About', { keepAlive: true }),
    );
    router.currentRoute.value = about;

    await store.closeTab(about, router);

    expect(store.tabs).toEqual([home]);
    expect(store.getCachedTabs).toEqual(['Home']);
    expect(router.replace).toHaveBeenCalledWith({
      params: {},
      path: '/home',
      query: {},
    });
  });

  it('navigates to the previous tab when closing the final active tab', async () => {
    preferences.tabbar.visitHistory = false;
    const store = useTabbarStore();
    store.addTab(createTab('/home', 'Home'));
    const about = store.addTab(createTab('/about', 'About'));
    const contact = store.addTab(createTab('/contact', 'Contact'));
    router.currentRoute.value = contact;

    await store.closeTab(contact, router);

    expect(store.tabs).toHaveLength(2);
    expect(router.replace).toHaveBeenCalledWith({
      params: {},
      path: about.path,
      query: {},
    });
  });

  it('keeps the only active tab open', async () => {
    const store = useTabbarStore();
    const onlyTab = store.addTab(createTab('/only', 'Only'));
    router.currentRoute.value = onlyTab;

    await store.closeTab(onlyTab, router);

    expect(store.tabs).toEqual([onlyTab]);
    expect(router.replace).not.toHaveBeenCalled();
  });

  it('falls back to the default tab when visit history is empty', async () => {
    const store = useTabbarStore();
    const home = store.addTab(createTab('/home', 'Home'));
    const about = store.addTab(createTab('/about', 'About'));
    store.visitHistory.clear();
    router.currentRoute.value = about;

    await store.closeTab(about, router);

    expect(store.tabs).toEqual([home]);
    expect(router.replace).toHaveBeenCalledWith({
      params: {},
      path: '/home',
      query: {},
    });
  });

  it('closes an inactive tab and refreshes its cache entry', async () => {
    const store = useTabbarStore();
    const home = store.addTab(createTab('/home', 'Home', { keepAlive: true }));
    const about = store.addTab(
      createTab('/about', 'About', { keepAlive: true }),
    );
    router.currentRoute.value = home;

    await store.closeTab(about, router);

    expect(store.tabs).toEqual([home]);
    expect(store.getCachedTabs).toEqual(['Home']);
    expect(router.replace).not.toHaveBeenCalled();
  });

  it('uses visit history when closing the active tab', async () => {
    const store = useTabbarStore();
    const home = store.addTab(createTab('/home', 'Home', { keepAlive: true }));
    const about = store.addTab(
      createTab('/about', 'About', { keepAlive: true }),
    );
    router.currentRoute.value = about;

    await store.closeTab(about, router);

    expect(store.tabs).toEqual([home]);
    expect(store.getCachedTabs).toEqual(['Home']);
    expect(router.replace).toHaveBeenCalledWith({
      params: {},
      path: '/home',
      query: {},
    });
  });

  it('treats closing an active affix tab as a no-op', async () => {
    const store = useTabbarStore();
    const affixTab = store.addTab(
      createTab('/affix', 'Affix', { affixTab: true }),
    );
    store.addTab(createTab('/other', 'Other'));
    router.currentRoute.value = affixTab;

    await store.closeTab(affixTab, router);

    expect(store.tabs).toHaveLength(2);
    expect(router.replace).not.toHaveBeenCalled();
  });

  it('returns undefined for a missing tab key', () => {
    const store = useTabbarStore();

    expect(store.getTabByKey('/missing')).toBeUndefined();
  });

  it('pins and unpins without mutating the caller route object', async () => {
    const store = useTabbarStore();
    const routeTab = createTab('/settings', 'Settings');
    store.addTab(routeTab);

    await store.pinTab(routeTab);
    expect(routeTab.meta.affixTab).toBeUndefined();
    expect(store.getTabByKey('/settings')?.meta.affixTab).toBe(true);

    await store.unpinTab(routeTab);
    expect(routeTab.meta.affixTab).toBeUndefined();
    expect(store.getTabByKey('/settings')?.meta.affixTab).toBe(false);
  });

  it('only caches stable string route names', () => {
    const store = useTabbarStore();
    const unnamed = createTab('/unnamed', 'Unnamed', { keepAlive: true });
    const symbolic = createTab('/symbolic', 'Symbolic', { keepAlive: true });
    unnamed.name = undefined;
    symbolic.name = Symbol('Symbolic');
    store.tabs.push(unnamed, symbolic);

    store.updateCacheTabs();

    expect(store.getCachedTabs).toEqual([]);
  });

  it('refreshes the current tab', async () => {
    vi.useFakeTimers();
    const store = useTabbarStore();
    const currentTab = createTab('/dashboard', 'Dashboard', {
      name: 'Dashboard',
    });
    router.currentRoute.value = currentTab;

    const refresh = store.refresh(router);
    expect(store.excludeCachedTabs.has('Dashboard')).toBe(true);
    expect(store.renderRouteView).toBe(false);
    await vi.advanceTimersByTimeAsync(200);
    await refresh;

    expect(store.excludeCachedTabs.has('Dashboard')).toBe(false);
    expect(store.renderRouteView).toBe(true);
  });

  it('refreshes an unnamed route without adding an invalid cache key', async () => {
    vi.useFakeTimers();
    const store = useTabbarStore();
    const currentTab = createTab('/unnamed', 'Unnamed');
    currentTab.name = undefined;
    router.currentRoute.value = currentTab;

    const refresh = store.refresh(router);
    await vi.advanceTimersByTimeAsync(200);
    await refresh;

    expect(store.getExcludeCachedTabs).toEqual([]);
    expect(store.renderRouteView).toBe(true);
  });

  it('refreshes a named tab directly', async () => {
    vi.useFakeTimers();
    const store = useTabbarStore();

    const refresh = store.refresh('Dashboard');
    expect(store.getExcludeCachedTabs).toEqual(['Dashboard']);
    await vi.advanceTimersByTimeAsync(200);
    await refresh;

    expect(store.getExcludeCachedTabs).toEqual([]);
  });

  it('updates tab presentation state and rejects an invalid sort source', async () => {
    const store = useTabbarStore();
    const home = store.addTab(createTab('/home', 'Home'));
    const about = store.addTab(createTab('/about', 'About'));

    await store.setTabTitle(home, 'Dashboard');
    store.setMenuList(['close', 'refresh']);
    store.setUpdateTime();
    const updateTime = store.updateTime;
    await store.sortTabs(9, 0);

    expect(store.getTabByKey('/home')?.meta.newTabTitle).toBe('Dashboard');
    expect(store.getMenuList).toEqual(['close', 'refresh']);
    expect(updateTime).toBeGreaterThan(0);
    expect(store.tabs).toEqual([home, about]);
  });

  it('sorts affix tabs by configured order and toggles pin state', async () => {
    const store = useTabbarStore();
    const later = store.addTab(
      createTab('/later', 'Later', { affixTab: true, affixTabOrder: 2 }),
    );
    const earlier = store.addTab(
      createTab('/earlier', 'Earlier', { affixTab: true, affixTabOrder: 1 }),
    );
    const normal = store.addTab(createTab('/normal', 'Normal'));

    expect(store.affixTabs.map((tab) => tab.name)).toEqual([
      'Earlier',
      'Later',
    ]);
    await store.toggleTabPin(normal);
    expect(store.getTabByKey('/normal')?.meta.affixTab).toBe(true);
    const pinnedNormal = store.getTabByKey('/normal');
    expect(pinnedNormal).toBeDefined();
    if (pinnedNormal) {
      await store.toggleTabPin(pinnedNormal);
    }

    expect(store.getTabByKey('/normal')?.meta.affixTab).toBe(false);
    expect(store.tabs.map((tab) => tab.key)).toEqual(
      expect.arrayContaining([later.key, earlier.key]),
    );
  });

  it('derives stable tab keys from query, path-only, and malformed input', () => {
    const queryTab = createTab(
      '/detail?pageKey=first&pageKey=second',
      'Detail',
    );
    const pathOnly = createTab('/detail?id=1', 'Detail', {
      fullPathKey: false,
    });
    const malformed = createTab('/bad', 'Bad');
    malformed.fullPath = '/bad/%E0%A4%A';
    malformed.path = '/bad/%E0%A4%A';

    expect(getTabKey(queryTab)).toBe('first');
    expect(getTabKey(pathOnly)).toBe('/detail');
    expect(getTabKey(malformed)).toBe('/bad/%E0%A4%A');
  });
});

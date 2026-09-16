import type {
  RouteLocationNormalizedGeneric,
  RouteRecordNormalized,
} from 'vue-router';

import type { TabDefinition } from '@vben/types';

import { nextTick, reactive, ref } from 'vue';

import { beforeEach, describe, expect, it, vi } from 'vitest';

import { useTabbar } from './use-tabbar';

interface MockAccessStore {
  accessMenus: object[];
}

interface MockTabbarStore {
  addTab: ReturnType<typeof vi.fn>;
  getMenuList: string[];
  getTabByKey: ReturnType<typeof vi.fn>;
  getTabs: TabDefinition[];
  setAffixTabs: ReturnType<typeof vi.fn>;
  updateTime: number;
}

const mocks = vi.hoisted(() => ({
  accessStore: undefined as unknown as MockAccessStore,
  contentIsMaximize: undefined as unknown as ReturnType<typeof ref<boolean>>,
  locale: undefined as unknown as ReturnType<typeof ref<string>>,
  route: undefined as unknown as RouteLocationNormalizedGeneric,
  router: undefined as unknown as {
    getRoutes: ReturnType<typeof vi.fn>;
    push: ReturnType<typeof vi.fn>;
  },
  tabbarStore: undefined as unknown as MockTabbarStore,
  tabsApi: {
    closeAllTabs: vi.fn(),
    closeCurrentTab: vi.fn(),
    closeLeftTabs: vi.fn(),
    closeOtherTabs: vi.fn(),
    closeRightTabs: vi.fn(),
    closeTabByKey: vi.fn(),
    getTabDisableState: vi.fn(),
    openTabInNewWindow: vi.fn(),
    refreshTab: vi.fn(),
    toggleTabPin: vi.fn(),
  },
  toggleMaximize: vi.fn(),
  translate: vi.fn((key: string) => `translated:${key}`),
}));

vi.mock('vue-router', () => ({
  useRoute: () => mocks.route,
  useRouter: () => mocks.router,
}));

vi.mock('@vben/hooks', () => ({
  useContentMaximize: () => ({
    contentIsMaximize: mocks.contentIsMaximize,
    toggleMaximize: mocks.toggleMaximize,
  }),
  useTabs: () => mocks.tabsApi,
}));

vi.mock('@vben/icons', () => ({
  ArrowLeftToLine: 'ArrowLeftToLine',
  ArrowRightLeft: 'ArrowRightLeft',
  ArrowRightToLine: 'ArrowRightToLine',
  ExternalLink: 'ExternalLink',
  FoldHorizontal: 'FoldHorizontal',
  Fullscreen: 'Fullscreen',
  Minimize2: 'Minimize2',
  Pin: 'Pin',
  PinOff: 'PinOff',
  RotateCw: 'RotateCw',
  X: 'X',
}));

vi.mock('@vben/locales', () => ({
  $t: mocks.translate,
  useI18n: () => ({ locale: mocks.locale }),
}));

vi.mock('@vben/stores', () => ({
  getTabKey: (tab: TabDefinition) =>
    tab.key ??
    (tab.meta.fullPathKey === false ? tab.path : tab.fullPath || tab.path),
  useAccessStore: () => mocks.accessStore,
  useTabbarStore: () => mocks.tabbarStore,
}));

vi.mock('@vben/utils', () => ({
  filterTree: <T>(items: T[], predicate: (item: T) => boolean) =>
    items.filter((item) => predicate(item)),
}));

function createTab(
  path: string,
  title: string,
  meta?: TabDefinition['meta'],
): TabDefinition {
  return {
    fullPath: path,
    hash: '',
    key: path,
    matched: [],
    meta: meta ?? { title },
    name: title,
    params: {},
    path,
    query: {},
    redirectedFrom: undefined,
  };
}

function createRouteRecord(
  path: string,
  meta: RouteRecordNormalized['meta'],
): RouteRecordNormalized {
  return {
    aliasOf: undefined,
    beforeEnter: undefined,
    children: [],
    components: { default: {} },
    enterCallbacks: {},
    instances: {},
    leaveGuards: new Set(),
    meta,
    mods: {},
    name: path,
    path,
    props: { default: false },
    redirect: undefined,
    updateGuards: new Set(),
  };
}

function menuByKey(
  menus: ReturnType<ReturnType<typeof useTabbar>['createContextMenus']>,
  key: string,
) {
  const menu = menus.find((item) => item.key === key);
  expect(menu).toBeDefined();
  return menu;
}

describe('useTabbar', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    const current = createTab('/current', 'page.current');
    mocks.route = reactive(current);
    mocks.router = {
      getRoutes: vi.fn().mockReturnValue([]),
      push: vi.fn().mockResolvedValue(undefined),
    };
    mocks.accessStore = reactive({ accessMenus: [] });
    mocks.contentIsMaximize = ref(false);
    mocks.locale = ref('zh-CN');
    mocks.tabbarStore = reactive({
      addTab: vi.fn(),
      getMenuList: [
        'close',
        'affix',
        'maximize',
        'restore-maximize',
        'reload',
        'open-in-new-window',
        'close-left',
        'close-right',
        'close-other',
        'close-all',
      ],
      getTabByKey: vi.fn(),
      getTabs: [current],
      setAffixTabs: vi.fn(),
      updateTime: 0,
    });
    mocks.tabsApi.getTabDisableState.mockReturnValue({
      disabledCloseAll: false,
      disabledCloseCurrent: false,
      disabledCloseLeft: false,
      disabledCloseOther: false,
      disabledCloseRight: false,
      disabledRefresh: false,
    });
  });

  it('initializes affix tabs, the current route, and localized tabs immediately', () => {
    const affix = createRouteRecord('/home', {
      affixTab: true,
      title: 'page.home',
    });
    const ordinary = createRouteRecord('/other', { title: 'page.other' });
    const matched = createRouteRecord('/current', {
      keepAlive: true,
      title: 'page.matched',
    });
    mocks.router.getRoutes.mockReturnValue([affix, ordinary]);
    mocks.route.matched = [matched];

    const tabbar = useTabbar();

    expect(mocks.tabbarStore.setAffixTabs).toHaveBeenCalledWith([affix]);
    expect(mocks.tabbarStore.addTab).toHaveBeenCalledWith(
      expect.objectContaining({ meta: matched.meta, path: '/current' }),
    );
    expect(tabbar.currentActive.value).toBe('/current');
    expect(tabbar.currentTabs.value[0]?.meta.title).toBe(
      'translated:page.current',
    );
  });

  it('reacts to access, route, tab, and locale changes', async () => {
    const tabbar = useTabbar();
    const next = createTab('/next', 'page.next');

    mocks.accessStore.accessMenus = [{ path: '/next' }];
    mocks.route.path = '/next';
    mocks.route.fullPath = '/next?source=menu';
    mocks.route.meta = { title: 'page.next' };
    mocks.tabbarStore.getTabs.push(next);
    mocks.locale.value = 'en-US';
    await nextTick();

    expect(mocks.tabbarStore.setAffixTabs).toHaveBeenCalledTimes(2);
    expect(mocks.tabbarStore.addTab).toHaveBeenLastCalledWith(
      expect.objectContaining({ path: '/next' }),
    );
    expect(tabbar.currentTabs.value).toHaveLength(2);
    expect(tabbar.currentTabs.value[1]?.meta.title).toBe(
      'translated:page.next',
    );
  });

  it('navigates only for known tabs and delegates tab closing', async () => {
    const known = createTab('/known', 'Known');
    known.fullPath = '';
    mocks.tabbarStore.getTabByKey.mockImplementation((key: string) =>
      key === '/known' ? known : undefined,
    );
    const tabbar = useTabbar();

    tabbar.handleClick('/missing');
    tabbar.handleClick('/known');
    await tabbar.handleClose('/known');

    expect(mocks.router.push).toHaveBeenCalledOnce();
    expect(mocks.router.push).toHaveBeenCalledWith('/known');
    expect(mocks.tabsApi.closeTabByKey).toHaveBeenCalledWith('/known');
  });

  it('builds and executes the complete context-menu contract', async () => {
    const tab = createTab('/reports', 'Reports');
    tab.fullPath = '';
    const menus = useTabbar().createContextMenus(tab);

    await menuByKey(menus, 'close')?.handler?.(undefined);
    await menuByKey(menus, 'affix')?.handler?.(undefined);
    await menuByKey(menus, 'maximize')?.handler?.(undefined);
    await menuByKey(menus, 'reload')?.handler?.(undefined);
    await menuByKey(menus, 'open-in-new-window')?.handler?.(undefined);
    await menuByKey(menus, 'close-left')?.handler?.(undefined);
    await menuByKey(menus, 'close-right')?.handler?.(undefined);
    await menuByKey(menus, 'close-other')?.handler?.(undefined);
    await menuByKey(menus, 'close-all')?.handler?.(undefined);

    expect(mocks.tabsApi.closeCurrentTab).toHaveBeenCalledWith(tab);
    expect(mocks.tabsApi.toggleTabPin).toHaveBeenCalledWith(tab);
    expect(mocks.router.push).toHaveBeenCalledWith('/reports');
    expect(mocks.toggleMaximize).toHaveBeenCalledOnce();
    expect(mocks.tabsApi.refreshTab).toHaveBeenCalledOnce();
    expect(mocks.tabsApi.openTabInNewWindow).toHaveBeenCalledWith(tab);
    expect(mocks.tabsApi.closeLeftTabs).toHaveBeenCalledWith(tab);
    expect(mocks.tabsApi.closeRightTabs).toHaveBeenCalledWith(tab);
    expect(mocks.tabsApi.closeOtherTabs).toHaveBeenCalledWith(tab);
    expect(mocks.tabsApi.closeAllTabs).toHaveBeenCalledOnce();
  });

  it('uses restore and unpin menu variants for a maximized affix tab', async () => {
    mocks.contentIsMaximize.value = true;
    mocks.tabbarStore.getMenuList = ['affix', 'restore-maximize'];
    const affix = createTab('/home', 'Home', {
      affixTab: true,
      title: 'Home',
    });

    const menus = useTabbar().createContextMenus(affix);
    await menuByKey(menus, 'restore-maximize')?.handler?.(undefined);

    expect(menus.map((item) => item.key)).toEqual([
      'affix',
      'restore-maximize',
    ]);
    expect(mocks.router.push).not.toHaveBeenCalled();
    expect(mocks.toggleMaximize).toHaveBeenCalledOnce();
  });
});

import type { MenuRecordRaw } from '@vben/types';

import { mount } from '@vue/test-utils';

import { preferences, resetPreferences } from '@vben/preferences';

import { beforeEach, describe, expect, it, vi } from 'vitest';

import BasicLayout from '../layout.vue';

const route = vi.hoisted(() => ({ meta: {}, path: '/system/user' }));

const router = vi.hoisted(() => ({
  afterEach: vi.fn(),
  beforeEach: vi.fn(),
  getRoutes: vi.fn(() => []),
  push: vi.fn(),
  resolve: vi.fn((path: string) => ({ href: path })),
}));

const storeState = vi.hoisted(() => ({
  accessMenus: [] as MenuRecordRaw[],
  isLockScreen: false,
}));

const refreshSpy = vi.hoisted(() => vi.fn());

// @iconify/vue 在模块求值时捕获全局 fetch 并异步队列远程拉取图标；
// vi.hoisted 先于本文件依赖图求值执行，确保捕获到本地桩，封死外部 HTTP 边界。
vi.hoisted(() => {
  globalThis.fetch = (() =>
    Promise.resolve({
      json: () => Promise.resolve({}),
      status: 200,
    })) as unknown as typeof fetch;
});

vi.mock('vue-router', async () => {
  const { defineComponent, h } = await import('vue');
  const RouterViewStub = defineComponent({
    name: 'RouterViewStub',
    setup(_, { attrs, slots }) {
      return () =>
        h('div', { id: 'router-view-stub', ...attrs }, [
          slots.default?.({
            Component: h('div', 'view-content'),
            route,
          }),
        ]);
    },
  });
  return {
    RouterView: RouterViewStub,
    useRoute: () => route,
    useRouter: () => router,
  };
});

vi.mock('@vben/hooks', async () => {
  const { ref } = await import('vue');
  return {
    useContentMaximize: () => ({
      contentIsMaximize: ref(false),
      toggleMaximize: vi.fn(),
    }),
    useRefresh: () => ({ refresh: refreshSpy }),
    useTabs: () => ({
      refreshTab: vi.fn(),
      unpinTab: vi.fn(),
    }),
  };
});

vi.mock('@vben/locales', async () => {
  const { ref } = await import('vue');
  return {
    $t: (key: string) => key,
    i18n: {
      global: { locale: ref('zh-CN') },
    },
    useI18n: () => ({ locale: ref('zh-CN'), t: (key: string) => key }),
  };
});
vi.mock('@vben/stores', async () => {
  const { ref } = await import('vue');
  return {
    getTabKey: (r: { name?: string; path?: string }) =>
      String(r?.name ?? r?.path ?? ''),
    storeToRefs: (store: unknown) => store,
    useAccessStore: () => ({
      get accessMenus() {
        return storeState.accessMenus;
      },
      get isLockScreen() {
        return storeState.isLockScreen;
      },
    }),
    useTabbarStore: () => ({
      addTab: vi.fn(),
      cachedTabs: { clear: vi.fn() },
      closeOtherTabs: vi.fn(),
      getTabByKey: vi.fn(),
      getTabs: [],
      setAffixTabs: vi.fn(),
      sortTabs: vi.fn(),
      updateTime: 0,
    }),
    useTimezoneStore: () => ({
      get timezone() {
        return ref('Asia/Shanghai');
      },
    }),
  };
});

const childStubs = {
  // 轻量 stub 命名按组件 __name/name 双写，具体行为基于真实 DOM 断言
  LayoutMenu: { template: '<div class="layout-menu"><slot /></div>' },
  LayoutExtraMenu: {
    template: '<div class="layout-extra-menu"><slot /></div>',
  },
  LayoutMixedMenu: {
    template: '<div class="layout-mixed-menu"><slot /></div>',
  },
  LayoutContentSpinner: { template: '<div class="layout-spinner" />' },
  Breadcrumb: { template: '<nav class="breadcrumb" />' },
  CheckUpdates: { template: '<span class="check-updates" />' },
  Preferences: {
    name: 'PreferencesStub',
    emits: ['clearPreferencesAndLogout'],
    template:
      '<button class="preferences" @click="$emit(\'clearPreferencesAndLogout\')" />',
  },
  preferences: {
    name: 'PreferencesStub',
    emits: ['clearPreferencesAndLogout'],
    template:
      '<button class="preferences" @click="$emit(\'clearPreferencesAndLogout\')" />',
  },
  Copyright: { template: '<span class="copyright" />' },
};

function mountLayout() {
  return mount(BasicLayout, {
    global: { stubs: childStubs },
  });
}

describe('basicLayout (effects)', () => {
  beforeEach(() => {
    resetPreferences();
    storeState.accessMenus = [
      {
        children: [
          { name: 'User', parents: ['/system'], path: '/system/user' },
        ],
        name: 'System',
        path: '/system',
      },
    ];
    route.path = '/system/user';
    route.meta = {};
    router.push.mockClear();
    refreshSpy.mockClear();
  });

  it('uses the dark sidebar theme from the preferences', () => {
    const wrapper = mountLayout();
    const aside = wrapper.get('aside');
    expect(aside.classes().join(' ')).toContain('dark');
  });

  it('keeps the sidebar visible by default', () => {
    mountLayout();
    expect(preferences.sidebar.hidden).toBe(false);
  });

  it('emits clearPreferencesAndLogout from the preferences button', async () => {
    const wrapper = mountLayout();
    await wrapper.get('.preferences').trigger('click');
    expect(wrapper.emitted('clearPreferencesAndLogout')).toBeTruthy();
  });

  it('refreshes the page when the locale changes', async () => {
    mountLayout();
    await import('@vben/locales').then(async (mod) => {
      // i18n locale 由 mock 暴露真实 ref，切换后触发 watch
      (
        mod as unknown as { i18n: { global: { locale: { value: string } } } }
      ).i18n.global.locale.value = 'en-US';
    });
    await new Promise((resolve) => setTimeout(resolve, 0));
    expect(refreshSpy).toHaveBeenCalled();
  });
});

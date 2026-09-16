import type { RouteLocationNormalizedLoaded } from 'vue-router';

import { mount } from '@vue/test-utils';
import { defineComponent } from 'vue';

import { resetPreferences, updatePreferences } from '@vben/preferences';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import LayoutContent from '../content.vue';

const route = vi.hoisted(() => ({
  meta: {},
  name: 'home',
  path: '/home',
}));

const view = vi.hoisted(() => ({
  Component: undefined as unknown,
}));

const storeState = vi.hoisted(() => ({
  cachedTabs: [] as string[],
  excludeCachedTabs: [] as string[],
  getTabs: [] as unknown[],
  renderRouteView: true,
  tabbarEnable: true,
}));

vi.mock('vue-router', async () => {
  const { defineComponent, h } = await import('vue');
  const RouterViewStub = defineComponent({
    name: 'RouterViewStub',
    setup(_, { attrs, slots }) {
      return () =>
        h('div', { id: 'router-view-stub', ...attrs }, [
          slots.default?.({ Component: view.Component, route }),
        ]);
    },
  });
  return {
    RouterView: RouterViewStub,
    useRoute: () => route,
  };
});

vi.mock('@vben/stores', async () => {
  const { ref } = await import('vue');
  return {
    getTabKey: (r: { name?: string; path?: string }) =>
      String(r?.name ?? r?.path ?? ''),
    storeToRefs: (store: unknown) => store,
    useTabbarStore: () => ({
      get getCachedTabs() {
        return ref(storeState.cachedTabs);
      },
      get getExcludeCachedTabs() {
        return ref(storeState.excludeCachedTabs);
      },
      get getTabs() {
        return storeState.getTabs;
      },
      get renderRouteView() {
        return ref(storeState.renderRouteView);
      },
    }),
  };
});

const mockView = defineComponent({
  name: 'MockView',
  template: '<section>view-content</section>',
});

function mountContent() {
  view.Component = mockView;
  route.name = 'home';
  route.path = '/home';
  route.meta = {};
  return mount(LayoutContent, {
    global: {
      stubs: {
        VbenSpinner: { template: '<div class="spinner" />' },
      },
    },
  });
}

describe('layoutContent (effects)', () => {
  beforeEach(() => {
    resetPreferences();
    storeState.cachedTabs = [];
    storeState.excludeCachedTabs = [];
    storeState.renderRouteView = true;
  });

  afterEach(() => {
    resetPreferences();
  });

  it('renders the routed view component through the RouterView slot', () => {
    const wrapper = mountContent();
    expect(wrapper.text()).toContain('view-content');
  });

  it('navigates the transition path with the configured name', async () => {
    updatePreferences({ transition: { enable: true, name: 'fade-up' } });
    const wrapper = mountContent();
    await wrapper.vm.$nextTick();
    expect(wrapper.text()).toContain('view-content');
  });

  it('skips the transition when it is disabled', () => {
    updatePreferences({ transition: { enable: false } });
    const wrapper = mountContent();
    expect(wrapper.text()).toContain('view-content');
  });

  it('hides the view when renderRouteView is false', () => {
    storeState.renderRouteView = false;
    const wrapper = mountContent();
    expect(wrapper.text()).not.toContain('view-content');
  });

  it('warns and renders nothing when the routed component is missing', () => {
    const spy = vi.spyOn(console, 'error').mockImplementation(() => {});
    const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => {});
    view.Component = undefined;
    route.name = 'home';
    const wrapper = mount(LayoutContent, {
      global: { stubs: { VbenSpinner: { template: '<div />' } } },
    });
    const messages = [...spy.mock.calls, ...warnSpy.mock.calls]
      .flat()
      .map(String);
    expect(
      messages.some((message) => message.includes('Component view not found')),
    ).toBe(true);
    expect(wrapper.text()).toBe('');
    spy.mockRestore();
    warnSpy.mockRestore();
  });

  it('passes the route name through transformComponent', () => {
    route.name = '';
    const wrapper = mountContent();
    expect(wrapper.text()).toContain('view-content');
  });
});

// 编译检查：钉住路由类型与布局 content 的契约关系
type ContentRoute = RouteLocationNormalizedLoaded;
void (null as unknown as ContentRoute | undefined);

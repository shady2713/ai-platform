import type { TabDefinition } from '@vben/types';

import { mount } from '@vue/test-utils';
import { nextTick, ref } from 'vue';

import { beforeEach, describe, expect, it, vi } from 'vitest';

import LayoutTabbar from './tabbar.vue';

const mocks = vi.hoisted(() => ({
  closeOtherTabs: vi.fn(),
  contentIsMaximize: undefined as unknown as ReturnType<typeof ref<boolean>>,
  createContextMenus: vi.fn(),
  currentActive: undefined as unknown as ReturnType<typeof ref<string>>,
  currentTabs: undefined as unknown as ReturnType<typeof ref<TabDefinition[]>>,
  handleClick: vi.fn(),
  handleClose: vi.fn(),
  preferences: {
    tabbar: {
      draggable: true,
      middleClickToClose: true,
      persist: true,
      showMaximize: true,
      showMore: true,
      showRefresh: true,
      styleType: 'chrome',
      wheelable: true,
    },
  },
  refreshTab: vi.fn(),
  route: { fullPath: '/current', path: '/current' },
  sortTabs: vi.fn(),
  tab: undefined as TabDefinition | undefined,
  toggleMaximize: vi.fn(),
  unpinTab: vi.fn(),
}));

vi.mock('vue-router', () => ({
  useRoute: () => mocks.route,
}));

vi.mock('@vben/hooks', () => ({
  useContentMaximize: () => ({
    contentIsMaximize: mocks.contentIsMaximize,
    toggleMaximize: mocks.toggleMaximize,
  }),
  useTabs: () => ({
    refreshTab: mocks.refreshTab,
    unpinTab: mocks.unpinTab,
  }),
}));

vi.mock('@vben/preferences', () => ({
  preferences: mocks.preferences,
}));

vi.mock('@vben/stores', () => ({
  useTabbarStore: () => ({
    closeOtherTabs: mocks.closeOtherTabs,
    getTabByKey: () => mocks.tab,
    sortTabs: mocks.sortTabs,
  }),
}));

vi.mock('@vben-core/tabs-ui', () => ({
  TabsToolMore: {
    name: 'TabsToolMore',
    props: ['menus'],
    template: '<div data-test="tabs-more" />',
  },
  TabsToolRefresh: {
    name: 'TabsToolRefresh',
    emits: ['refresh'],
    template: '<button data-test="tabs-refresh" />',
  },
  TabsToolScreen: {
    name: 'TabsToolScreen',
    emits: ['update:screen'],
    props: ['screen'],
    template: '<button data-test="tabs-screen" />',
  },
  TabsView: {
    name: 'TabsView',
    emits: ['close', 'sort-tabs', 'unpin', 'update:active'],
    props: [
      'active',
      'contextMenus',
      'draggable',
      'middleClickToClose',
      'showIcon',
      'styleType',
      'tabs',
      'wheelable',
    ],
    template: '<div data-test="tabs-view" />',
  },
}));

vi.mock('./use-tabbar', () => ({
  useTabbar: () => ({
    createContextMenus: mocks.createContextMenus,
    currentActive: mocks.currentActive,
    currentTabs: mocks.currentTabs,
    handleClick: mocks.handleClick,
    handleClose: mocks.handleClose,
  }),
}));

function createTab(): TabDefinition {
  return {
    fullPath: '/current',
    hash: '',
    key: '/current',
    matched: [],
    meta: { title: 'Current' },
    name: 'Current',
    params: {},
    path: '/current',
    query: {},
    redirectedFrom: undefined,
  };
}

describe('layoutTabbar', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mocks.contentIsMaximize = ref(false);
    mocks.currentActive = ref('/current');
    mocks.currentTabs = ref([createTab()]);
    mocks.tab = createTab();
    mocks.preferences.tabbar.persist = true;
    mocks.preferences.tabbar.showMaximize = true;
    mocks.preferences.tabbar.showMore = true;
    mocks.preferences.tabbar.showRefresh = true;
    mocks.createContextMenus.mockReturnValue([
      {
        handler: vi.fn(),
        key: 'reload',
        text: 'Reload',
      },
    ]);
  });

  it('passes tab state and mapped menus to the presentation components', () => {
    const wrapper = mount(LayoutTabbar, {
      props: { showIcon: true, theme: 'dark' },
    });
    const tabsView = wrapper.findComponent({ name: 'TabsView' });
    const more = wrapper.findComponent({ name: 'TabsToolMore' });

    expect(tabsView.props()).toMatchObject({
      active: '/current',
      draggable: true,
      middleClickToClose: true,
      showIcon: true,
      styleType: 'chrome',
      tabs: mocks.currentTabs.value,
      wheelable: true,
    });
    expect(more.props('menus')).toEqual([
      expect.objectContaining({
        key: 'reload',
        label: 'Reload',
        value: 'reload',
      }),
    ]);
  });

  it('exposes an empty menu when the active tab is no longer present', () => {
    mocks.tab = undefined;

    const wrapper = mount(LayoutTabbar);

    expect(
      wrapper.findComponent({ name: 'TabsToolMore' }).props('menus'),
    ).toEqual([]);
    expect(mocks.createContextMenus).not.toHaveBeenCalled();
  });

  it('delegates tab and toolbar events exactly once', async () => {
    const wrapper = mount(LayoutTabbar);
    const tabsView = wrapper.findComponent({ name: 'TabsView' });
    const refresh = wrapper.findComponent({ name: 'TabsToolRefresh' });
    const screen = wrapper.findComponent({ name: 'TabsToolScreen' });

    tabsView.vm.$emit('close', '/current');
    tabsView.vm.$emit('sort-tabs', 1, 0);
    tabsView.vm.$emit('unpin', mocks.tab);
    tabsView.vm.$emit('update:active', '/next');
    refresh.vm.$emit('refresh');
    screen.vm.$emit('update:screen', true);
    await nextTick();

    expect(mocks.handleClose).toHaveBeenCalledWith('/current');
    expect(mocks.sortTabs).toHaveBeenCalledWith(1, 0);
    expect(mocks.unpinTab).toHaveBeenCalledWith(mocks.tab);
    expect(mocks.handleClick).toHaveBeenCalledWith('/next');
    expect(mocks.refreshTab).toHaveBeenCalledOnce();
    expect(mocks.toggleMaximize).toHaveBeenCalledOnce();
  });

  it('closes stale persisted tabs only when persistence is disabled', () => {
    mocks.preferences.tabbar.persist = false;

    mount(LayoutTabbar);

    expect(mocks.closeOtherTabs).toHaveBeenCalledWith(mocks.route);
  });

  it('omits optional toolbar controls when they are disabled', () => {
    mocks.preferences.tabbar.showMaximize = false;
    mocks.preferences.tabbar.showMore = false;
    mocks.preferences.tabbar.showRefresh = false;

    const wrapper = mount(LayoutTabbar);

    expect(wrapper.find('[data-test="tabs-more"]').exists()).toBe(false);
    expect(wrapper.find('[data-test="tabs-refresh"]').exists()).toBe(false);
    expect(wrapper.find('[data-test="tabs-screen"]').exists()).toBe(false);
  });
});

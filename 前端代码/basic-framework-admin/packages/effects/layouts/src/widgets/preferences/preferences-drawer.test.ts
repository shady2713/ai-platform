import type { VueWrapper } from '@vue/test-utils';

import { shallowMount } from '@vue/test-utils';
import { defineComponent } from 'vue';

import { beforeEach, describe, expect, it, vi } from 'vitest';

import PreferencesDrawer from './preferences-drawer.vue';

const state = vi.hoisted(() => ({
  clearCache: vi.fn(),
  copy: vi.fn(),
  copyPreferencesSuccess: vi.fn(),
  diffPreference: undefined as
    | undefined
    | { value: null | Record<string, unknown> },
  loadLocaleMessages: vi.fn(),
  preferences: {
    app: { locale: 'zh-CN' },
    header: { enable: true },
  },
  resetPreferences: vi.fn(),
}));

vi.mock('@vben/icons', () => {
  const component = (name: string) => defineComponent({ name });
  return {
    Copy: component('Copy'),
    Pin: component('Pin'),
    PinOff: component('PinOff'),
    RotateCw: component('RotateCw'),
  };
});

vi.mock('@vben/locales', () => ({
  $t: (key: string) => key,
  loadLocaleMessages: state.loadLocaleMessages,
}));

vi.mock('@vben/preferences', async () => {
  const { ref } = await import('vue');
  state.diffPreference = ref({ theme: { mode: 'dark' } });
  const flag = () => ref(false);
  return {
    clearCache: state.clearCache,
    preferences: state.preferences,
    resetPreferences: state.resetPreferences,
    usePreferences: () => ({
      diffPreference: state.diffPreference,
      isDark: flag(),
      isFullContent: flag(),
      isHeaderNav: flag(),
      isHeaderSidebarNav: flag(),
      isMixedNav: flag(),
      isSideMixedNav: flag(),
      isSideMode: flag(),
      isSideNav: flag(),
    }),
  };
});

vi.mock('@vben-core/popup-ui', () => ({
  useVbenDrawer: () => [
    defineComponent({
      name: 'DrawerStub',
      template:
        '<div><slot name="extra" /><slot /><slot name="footer" /></div>',
    }),
  ],
}));

vi.mock('@vben-core/shadcn-ui', () => {
  const component = (name: string) => defineComponent({ name });
  return {
    VbenButton: component('VbenButton'),
    VbenIconButton: component('VbenIconButton'),
    VbenSegmented: component('VbenSegmented'),
  };
});

vi.mock('@vben-core/shared/global-state', () => ({
  globalShareState: {
    getMessage: () => ({
      copyPreferencesSuccess: state.copyPreferencesSuccess,
    }),
  },
}));

vi.mock('@vueuse/core', () => ({
  useClipboard: () => ({ copy: state.copy }),
}));

vi.mock('./blocks', () => {
  const component = (name: string) => defineComponent({ name });
  return Object.fromEntries(
    [
      'Animation',
      'Block',
      'Breadcrumb',
      'BuiltinTheme',
      'ColorMode',
      'Content',
      'Copyright',
      'FontSize',
      'Footer',
      'General',
      'GlobalShortcutKeys',
      'Header',
      'Layout',
      'Navigation',
      'Radius',
      'Sidebar',
      'Tabbar',
      'Theme',
      'Widget',
    ].map((name) => [name, component(name)]),
  );
});

interface PreferencesDrawerComponent {
  handleClearCache: () => Promise<void>;
  handleCopy: () => Promise<void>;
  handleReset: () => Promise<void>;
}

function component(wrapper: VueWrapper) {
  return wrapper.vm as unknown as PreferencesDrawerComponent;
}

function diffPreference() {
  if (!state.diffPreference) {
    throw new Error('Preference test state is not initialized');
  }
  return state.diffPreference;
}

describe('preferences drawer actions', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    if (state.diffPreference) {
      state.diffPreference.value = { theme: { mode: 'dark' } };
    }
    state.copy.mockResolvedValue(undefined);
    state.loadLocaleMessages.mockResolvedValue(undefined);
  });

  it('copies only the preference difference and reports success', async () => {
    const wrapper = shallowMount(PreferencesDrawer);

    await component(wrapper).handleCopy();

    expect(state.copy).toHaveBeenCalledWith(
      JSON.stringify({ theme: { mode: 'dark' } }, null, 2),
    );
    expect(state.copyPreferencesSuccess).toHaveBeenCalledWith(
      'preferences.copyPreferencesSuccessTitle',
      'preferences.copyPreferencesSuccess',
    );
  });

  it('clears persisted preferences and requests a logout', async () => {
    const wrapper = shallowMount(PreferencesDrawer);

    await component(wrapper).handleClearCache();

    expect(state.resetPreferences).toHaveBeenCalledOnce();
    expect(state.clearCache).toHaveBeenCalledOnce();
    expect(wrapper.emitted('clearPreferencesAndLogout')).toHaveLength(1);
  });

  it('skips a no-op reset when no preference differs', async () => {
    const wrapper = shallowMount(PreferencesDrawer);
    diffPreference().value = null;

    await component(wrapper).handleReset();

    expect(state.resetPreferences).not.toHaveBeenCalled();
    expect(state.loadLocaleMessages).not.toHaveBeenCalled();
  });

  it('resets changed preferences and reloads the effective locale', async () => {
    const wrapper = shallowMount(PreferencesDrawer);

    await component(wrapper).handleReset();

    expect(state.resetPreferences).toHaveBeenCalledOnce();
    expect(state.loadLocaleMessages).toHaveBeenCalledWith('zh-CN');
  });
});

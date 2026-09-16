import { mount } from '@vue/test-utils';
import { nextTick } from 'vue';

import { beforeEach, describe, expect, it, vi } from 'vitest';

import Preferences from './preferences.vue';

const mocks = vi.hoisted(() => ({
  drawerOpen: vi.fn(),
  loadLocaleMessages: vi.fn(),
  preferences: {
    app: {
      locale: 'zh-CN',
      name: 'Framework Admin',
    },
    widget: {
      fullscreen: true,
    },
  },
  updatePreferences: vi.fn(),
}));

vi.mock('@vben/preferences', () => ({
  preferences: mocks.preferences,
  updatePreferences: mocks.updatePreferences,
}));

vi.mock('@vben/locales', () => ({
  $t: (key: string) => key,
  loadLocaleMessages: mocks.loadLocaleMessages,
}));

vi.mock('@vben/icons', () => ({
  Settings: {
    template: '<span data-test="settings-icon" />',
  },
}));

vi.mock('@vben-core/popup-ui', () => ({
  useVbenDrawer: () => [
    {
      name: 'PreferencesDrawerBridgeStub',
      emits: ['update:appLocale', 'update:widgetFullscreen'],
      inheritAttrs: false,
      props: {
        appLocale: String,
        appName: String,
        widgetFullscreen: Boolean,
      },
      template: '<div v-bind="$attrs" data-test="drawer" />',
    },
    { open: mocks.drawerOpen },
  ],
}));

vi.mock('@vben-core/shadcn-ui', () => ({
  VbenButton: {
    props: ['title'],
    template: '<button :title="title"><slot /></button>',
  },
}));

vi.mock('./preferences-drawer.vue', () => ({
  default: {
    name: 'PreferencesDrawerStub',
  },
}));

describe('preferences bridge', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('flattens preference groups and forwards host attributes', () => {
    const wrapper = mount(Preferences, {
      attrs: {
        'data-source': 'host',
      },
    });
    const drawer = wrapper.findComponent({
      name: 'PreferencesDrawerBridgeStub',
    });

    expect(drawer.props()).toMatchObject({
      appLocale: 'zh-CN',
      appName: 'Framework Admin',
      widgetFullscreen: true,
    });
    expect(drawer.attributes('data-source')).toBe('host');
    expect(drawer.attributes('class')).toBeUndefined();
  });

  it('writes drawer updates back to the matching preference group', async () => {
    const wrapper = mount(Preferences);
    const drawer = wrapper.findComponent({
      name: 'PreferencesDrawerBridgeStub',
    });

    drawer.vm.$emit('update:widgetFullscreen', false);
    drawer.vm.$emit('update:appLocale', 'en-US');
    await nextTick();

    expect(mocks.updatePreferences).toHaveBeenNthCalledWith(1, {
      widget: { fullscreen: false },
    });
    expect(mocks.updatePreferences).toHaveBeenNthCalledWith(2, {
      app: { locale: 'en-US' },
    });
    expect(mocks.loadLocaleMessages).toHaveBeenCalledOnce();
    expect(mocks.loadLocaleMessages).toHaveBeenCalledWith('en-US');
  });

  it('does not load an unsupported locale emitted by the drawer', async () => {
    const wrapper = mount(Preferences);
    const drawer = wrapper.findComponent({
      name: 'PreferencesDrawerBridgeStub',
    });

    drawer.vm.$emit('update:appLocale', 'fr-FR');
    await nextTick();

    expect(mocks.updatePreferences).toHaveBeenCalledWith({
      app: { locale: 'fr-FR' },
    });
    expect(mocks.loadLocaleMessages).not.toHaveBeenCalled();
  });

  it('opens the drawer from the default trigger', async () => {
    const wrapper = mount(Preferences);

    expect(wrapper.get('button').attributes('title')).toBe('preferences.title');
    await wrapper.get('button').trigger('click');

    expect(mocks.drawerOpen).toHaveBeenCalledOnce();
  });
});

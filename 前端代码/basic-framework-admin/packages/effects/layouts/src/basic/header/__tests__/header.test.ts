import { mount } from '@vue/test-utils';

import { describe, expect, it, vi } from 'vitest';

import LayoutHeader from '../header.vue';

vi.mock('@vben/hooks', () => ({
  useRefresh: () => ({
    refresh: () => undefined,
  }),
}));

vi.mock('@vben/preferences', () => ({
  preferences: {
    header: {
      menuAlign: 'start',
    },
    widget: {
      fullscreen: true,
      globalSearch: true,
      languageToggle: true,
      notification: false,
      refresh: true,
      themeToggle: true,
      timezone: true,
    },
  },
  usePreferences: () => ({
    globalSearchShortcutKey: { value: false },
    preferencesButtonPosition: { value: { header: true } },
  }),
}));

vi.mock('@vben/stores', () => ({
  useAccessStore: () => ({
    accessMenus: [],
  }),
}));

vi.mock('../../../widgets', () => ({
  GlobalSearch: {
    template: '<div data-test="global-search" />',
  },
  LanguageToggle: {
    template: '<div data-test="language-toggle" />',
  },
  PreferencesButton: {
    template: '<div data-test="preferences" />',
  },
  ThemeToggle: {
    template: '<div data-test="theme-toggle" />',
  },
  TimezoneButton: {
    template: '<div data-test="timezone" />',
  },
}));

describe('layout header', () => {
  it('does not render language or timezone controls', () => {
    const wrapper = mount(LayoutHeader, {
      slots: {
        'user-dropdown': '<div data-test="user-dropdown" />',
      },
      global: {
        stubs: {
          RotateCw: {
            template: '<span data-test="refresh-icon" />',
          },
          VbenFullScreen: {
            template: '<div data-test="fullscreen" />',
          },
          VbenIconButton: {
            template: '<button><slot /></button>',
          },
        },
      },
    });

    expect(wrapper.find('[data-test="global-search"]').exists()).toBe(true);
    expect(wrapper.find('[data-test="theme-toggle"]').exists()).toBe(true);
    expect(wrapper.find('[data-test="language-toggle"]').exists()).toBe(false);
    expect(wrapper.find('[data-test="timezone"]').exists()).toBe(false);
  });
});

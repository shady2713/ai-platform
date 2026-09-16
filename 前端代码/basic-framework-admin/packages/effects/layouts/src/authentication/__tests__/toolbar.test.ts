import { mount } from '@vue/test-utils';

import { describe, expect, it, vi } from 'vitest';

import AuthenticationToolbar from '../toolbar.vue';

vi.mock('@vben/preferences', () => ({
  preferences: {
    widget: {
      languageToggle: true,
      themeToggle: true,
    },
  },
}));

vi.mock('../../widgets', () => ({
  AuthenticationColorToggle: {
    template: '<div data-test="color-toggle" />',
  },
  AuthenticationLayoutToggle: {
    template: '<div data-test="layout-toggle" />',
  },
  LanguageToggle: {
    template: '<div data-test="language-toggle" />',
  },
  ThemeToggle: {
    template: '<div data-test="theme-toggle" />',
  },
}));

describe('authentication toolbar', () => {
  it('does not render the language toggle control', () => {
    const wrapper = mount(AuthenticationToolbar);

    expect(wrapper.find('[data-test="language-toggle"]').exists()).toBe(false);
    expect(wrapper.find('[data-test="theme-toggle"]').exists()).toBe(true);
  });
});

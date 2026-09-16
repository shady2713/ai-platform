import { mount } from '@vue/test-utils';
import { defineComponent } from 'vue';

import { beforeEach, describe, expect, it, vi } from 'vitest';

import App from './app.vue';

const mocks = vi.hoisted(() => ({
  useElementPlusDesignTokens: vi.fn(),
}));

vi.mock('@vben/hooks', () => ({
  useElementPlusDesignTokens: mocks.useElementPlusDesignTokens,
}));
vi.mock('element-plus', () => ({
  ElConfigProvider: defineComponent({
    name: 'ElConfigProvider',
    props: { locale: { default: () => ({}), type: Object } },
    template: '<main><slot /></main>',
  }),
}));
vi.mock('#/locales', () => ({ elementLocale: { name: 'zh-cn' } }));

describe('application root', () => {
  beforeEach(() => vi.clearAllMocks());

  it('installs design tokens and keeps routing mounted', () => {
    const wrapper = mount(App, {
      global: {
        stubs: {
          RouterView: defineComponent({
            name: 'RouterView',
            template: '<div />',
          }),
        },
      },
    });

    expect(mocks.useElementPlusDesignTokens).toHaveBeenCalledOnce();
    expect(
      wrapper.findComponent({ name: 'ElConfigProvider' }).props('locale'),
    ).toEqual({
      name: 'zh-cn',
    });
    expect(wrapper.findComponent({ name: 'RouterView' }).exists()).toBe(true);
  });
});

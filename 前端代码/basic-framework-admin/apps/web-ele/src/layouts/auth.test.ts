import { mount } from '@vue/test-utils';
import { defineComponent } from 'vue';

import { describe, expect, it, vi } from 'vitest';

import AuthLayout from './auth.vue';

vi.mock('@vben/layouts', () => ({
  AuthPageLayout: defineComponent({
    name: 'AuthPageLayout',
    props: {
      appName: { default: '', type: String },
      logo: { default: '', type: String },
      logoDark: { default: '', type: String },
      pageDescription: { default: '', type: String },
      pageTitle: { default: '', type: String },
    },
    template: '<main><slot /></main>',
  }),
}));
vi.mock('@vben/preferences', () => ({
  preferences: {
    app: { name: 'Admin Console' },
    logo: { source: '/logo.svg', sourceDark: '/logo-dark.svg' },
  },
}));
vi.mock('#/locales', () => ({ $t: (key: string) => `translated:${key}` }));

describe('authentication layout', () => {
  it('forwards branding and localized copy to the shared layout', () => {
    const wrapper = mount(AuthLayout);
    const layout = wrapper.getComponent({ name: 'AuthPageLayout' });

    expect(layout.props()).toMatchObject({
      appName: 'Admin Console',
      logo: '/logo.svg',
      logoDark: '/logo-dark.svg',
      pageDescription: 'translated:authentication.pageDesc',
      pageTitle: 'translated:authentication.pageTitle',
    });
  });
});

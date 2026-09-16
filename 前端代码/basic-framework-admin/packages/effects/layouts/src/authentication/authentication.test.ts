import { mount } from '@vue/test-utils';
import { defineComponent } from 'vue';

import { beforeEach, describe, expect, it, vi } from 'vitest';

import Authentication from './authentication.vue';

const state = vi.hoisted(() => ({
  authPanelCenter: undefined as undefined | { value: boolean },
  authPanelLeft: undefined as undefined | { value: boolean },
  authPanelRight: undefined as undefined | { value: boolean },
  isDark: undefined as undefined | { value: boolean },
  preferences: {
    copyright: { companyName: 'Framework', enable: true },
  },
}));

vi.mock('@vben/preferences', async () => {
  const { ref } = await import('vue');
  state.authPanelCenter = ref(false);
  state.authPanelLeft = ref(true);
  state.authPanelRight = ref(false);
  state.isDark = ref(false);
  return {
    preferences: state.preferences,
    usePreferences: () => ({
      authPanelCenter: state.authPanelCenter,
      authPanelLeft: state.authPanelLeft,
      authPanelRight: state.authPanelRight,
      isDark: state.isDark,
    }),
  };
});

vi.mock('../basic/copyright', () => ({
  Copyright: defineComponent({ name: 'CopyrightStub', template: '<footer />' }),
}));

vi.mock('./form.vue', () => ({
  default: defineComponent({
    name: 'AuthenticationFormViewStub',
    props: {
      dataSide: {
        default: undefined,
        type: String,
      },
    },
    template:
      '<main :data-side="dataSide"><slot /><slot name="copyright" /></main>',
  }),
}));

vi.mock('./icons/slogan.vue', () => ({
  default: defineComponent({ name: 'SloganIconStub', template: '<svg />' }),
}));

vi.mock('./toolbar.vue', () => ({
  default: defineComponent({ name: 'ToolbarStub', template: '<nav />' }),
}));

function preference(name: keyof typeof state) {
  const value = state[name];
  if (!value || typeof value !== 'object' || !('value' in value)) {
    throw new Error(`Preference ${name} is not initialized`);
  }
  return value as { value: boolean };
}

describe('authentication layout', () => {
  beforeEach(() => {
    state.preferences.copyright.enable = true;
    preference('authPanelCenter').value = false;
    preference('authPanelLeft').value = true;
    preference('authPanelRight').value = false;
    preference('isDark').value = false;
  });

  it('uses a semantic button only when the logo is actionable', async () => {
    const clickLogo = vi.fn();
    const actionable = mount(Authentication, {
      props: { appName: 'Framework', clickLogo, logo: '/logo.svg' },
    });

    const button = actionable.get('button');
    expect(button.attributes('type')).toBe('button');
    await button.trigger('click');
    expect(clickLogo).toHaveBeenCalledOnce();

    const decorative = mount(Authentication, {
      props: { appName: 'Framework', logo: '/logo.svg' },
    });
    expect(decorative.find('button').exists()).toBe(false);
    expect(decorative.get('img').attributes('src')).toBe('/logo.svg');
    await decorative.get('div.absolute').trigger('click');
  });

  it('selects the dark logo only when dark mode and a dark asset are present', async () => {
    const wrapper = mount(Authentication, {
      props: { logo: '/logo.svg', logoDark: '/logo-dark.svg' },
    });

    expect(wrapper.get('img').attributes('src')).toBe('/logo.svg');
    preference('isDark').value = true;
    await wrapper.vm.$nextTick();
    expect(wrapper.get('img').attributes('src')).toBe('/logo-dark.svg');

    await wrapper.setProps({ logoDark: '' });
    expect(wrapper.get('img').attributes('src')).toBe('/logo.svg');
  });

  it('honors optional chrome and slogan settings', () => {
    const wrapper = mount(Authentication, {
      props: {
        copyright: false,
        sloganImage: '/slogan.svg',
        toolbar: false,
      },
    });

    expect(wrapper.find('nav').exists()).toBe(false);
    expect(wrapper.find('footer').exists()).toBe(false);
    expect(wrapper.find('button').exists()).toBe(false);
    expect(wrapper.get('img').attributes('src')).toBe('/slogan.svg');
  });

  it('respects the global copyright switch and dark mode', () => {
    state.preferences.copyright.enable = false;
    preference('isDark').value = true;
    const wrapper = mount(Authentication);

    expect(wrapper.classes()).toContain('dark');
    expect(wrapper.find('footer').exists()).toBe(false);
  });

  it.each([
    ['left', true, false, false],
    ['bottom', false, true, false],
    ['right', false, false, true],
  ] as const)(
    'renders the %s authentication panel from preferences',
    async (side, left, center, right) => {
      preference('authPanelLeft').value = left;
      preference('authPanelCenter').value = center;
      preference('authPanelRight').value = right;
      const wrapper = mount(Authentication);
      await wrapper.vm.$nextTick();

      const panels = wrapper.findAllComponents({
        name: 'AuthenticationFormViewStub',
      });
      expect(panels).toHaveLength(1);
      expect(panels[0]?.attributes('data-side')).toBe(side);
    },
  );
});

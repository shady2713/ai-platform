import { mount } from '@vue/test-utils';

import { describe, expect, it } from 'vitest';

import Logo from './logo.vue';

function mountLogo(props: Record<string, unknown> = {}) {
  return mount(Logo, {
    props: { text: '基础框架', ...props },
    global: {
      stubs: {
        AvatarImage: {
          props: ['src', 'alt'],
          template: '<img class="avatar-image-stub" :src="src" :alt="alt" />',
        },
        AvatarFallback: { template: '<span class="fallback-stub" />' },
      },
    },
  });
}

describe('vbenLogo', () => {
  it('renders the light logo source with text', () => {
    const wrapper = mountLogo({ src: '/light.png' });

    expect(wrapper.find('.avatar-image-stub').attributes('src')).toBe(
      '/light.png',
    );
    expect(wrapper.get('a').attributes('href')).toBe('javascript:void 0');
    expect(wrapper.text()).toContain('基础框架');
  });

  it('prefers the dark logo source in dark theme', () => {
    const wrapper = mountLogo({ theme: 'dark', srcDark: '/dark.png' });

    expect(wrapper.find('.avatar-image-stub').attributes('src')).toBe(
      '/dark.png',
    );
  });

  it('collapses the text and honors a custom href', () => {
    const wrapper = mountLogo({ collapsed: true, href: '/console' });

    expect(wrapper.text()).not.toContain('基础框架');
    expect(wrapper.get('a').attributes('href')).toBe('/console');
  });
});

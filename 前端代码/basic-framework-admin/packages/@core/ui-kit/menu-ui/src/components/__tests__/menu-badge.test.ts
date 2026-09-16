import { mount } from '@vue/test-utils';

import { describe, expect, it } from 'vitest';

import MenuBadgeDot from '../menu-badge-dot.vue';
import MenuBadge from '../menu-badge.vue';

describe('menuBadge', () => {
  it('renders nothing when neither badge nor dot type is set', () => {
    const wrapper = mount(MenuBadge, { props: {} });
    expect(wrapper.find('span').exists()).toBe(false);
  });

  it('renders the badge text with the default class', () => {
    const wrapper = mount(MenuBadge, { props: { badge: '3' } });
    const text = wrapper.text();
    expect(text).toBe('3');
    expect(wrapper.find('.bg-green-500').exists()).toBe(true);
  });

  it('maps known badge variants to tailwind classes', () => {
    const wrapper = mount(MenuBadge, {
      props: { badge: 'x', badgeVariants: 'primary' },
    });
    expect(wrapper.find('.bg-primary').exists()).toBe(true);
  });

  it('uses a custom color class and applies it as a background style', () => {
    const wrapper = mount(MenuBadge, {
      props: { badge: 'x', badgeVariants: '#ff0000' },
    });
    const box = wrapper.get('.rounded-xl');
    expect(box.classes()).toContain('#ff0000');
    expect(box.attributes('style')).toContain('background-color');
  });

  it('renders a dot badge for the dot type', () => {
    const wrapper = mount(MenuBadge, { props: { badgeType: 'dot' } });
    expect(wrapper.find('.absolute').exists()).toBe(true);
    expect(wrapper.find('.animate-ping').exists()).toBe(true);
  });
});

describe('menuBadgeDot', () => {
  it('applies dotClass and dotStyle to both rings', () => {
    const wrapper = mount(MenuBadgeDot, {
      props: {
        dotClass: 'bg-red-500',
        dotStyle: { color: 'red' },
      },
    });
    const rings = wrapper.findAll('span');
    expect(rings.length).toBeGreaterThanOrEqual(3);
    for (const ring of rings) {
      if (ring.classes().includes('animate-ping')) {
        expect(ring.classes()).toContain('bg-red-500');
        expect(ring.attributes('style')).toContain('color: red');
      }
    }
  });

  it('uses the empty defaults for class and style', () => {
    const wrapper = mount(MenuBadgeDot);
    expect(wrapper.find('.animate-ping').exists()).toBe(true);
  });
});

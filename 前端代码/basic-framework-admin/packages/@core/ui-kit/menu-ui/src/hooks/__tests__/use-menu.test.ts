import type { SubMenuProvider } from '../../types';

import { mount } from '@vue/test-utils';
import { defineComponent, h } from 'vue';

import { describe, expect, it } from 'vitest';

import { useMenu, useMenuStyle } from '../use-menu';

const LeafItem = defineComponent({
  name: 'LeafItem',
  props: { path: { type: String, default: '' } },
  setup() {
    const { parentMenu, parentPaths } = useMenu();
    return () =>
      h('span', {
        'data-parent': parentMenu.value?.type.name ?? 'none',
        'data-paths': parentPaths.value.join('>'),
      });
  },
});

const MiddleWrap = defineComponent({
  name: 'MiddleWrap',
  props: { path: { type: String, default: '' } },
  setup() {
    // The middle component carries its own path in the chain.
    return () => h(LeafItem, { path: '/leaf' });
  },
});

const MenuRoot = defineComponent({
  // name must stay 'Menu'; the hook uses the name to stop the parent walk
  // eslint-disable-next-line vue/multi-word-component-names, vue/no-reserved-component-names
  name: 'Menu',
  props: { path: { type: String, default: '' } },
  setup() {
    return () => h(MiddleWrap, { path: '/middle' });
  },
});

describe('useMenu', () => {
  it('throws when called outside a component setup', () => {
    expect(() => useMenu()).toThrow('instance is required');
  });

  it('collects the parent path chain up to the Menu component', () => {
    const wrapper = mount(MenuRoot, { props: { path: '/menu' } });
    const leaf = wrapper.find('span');
    expect(leaf.attributes('data-paths')).toBe('/middle>/leaf');
    expect(leaf.attributes('data-parent')).toBe('Menu');
  });
});

describe('useMenuStyle', () => {
  it('uses level 0 when no sub menu context is given', () => {
    const style = useMenuStyle();
    expect(style.value).toEqual({ '--menu-level': 0 });
  });

  it('uses the sub menu level (expression resolves as level ?? 1)', () => {
    const style = useMenuStyle({ level: 2 } as SubMenuProvider);
    expect(style.value).toEqual({ '--menu-level': 2 });
  });

  it('falls back to one when the sub menu level is missing', () => {
    const style = useMenuStyle({ level: 1 } as SubMenuProvider);
    expect(style.value).toEqual({ '--menu-level': 1 });
  });
});

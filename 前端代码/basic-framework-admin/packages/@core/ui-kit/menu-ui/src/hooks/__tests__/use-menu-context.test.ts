import type { MenuProvider, SubMenuProvider } from '../../types';

import { mount } from '@vue/test-utils';
import { defineComponent, h } from 'vue';

import { describe, expect, it } from 'vitest';

import {
  createMenuContext,
  createSubMenuContext,
  useMenuContext,
  useSubMenuContext,
} from '../use-menu-context';

const ChildHarness = defineComponent({
  name: 'ChildHarness',
  setup() {
    const rootMenu = useMenuContext();
    const subMenu = useSubMenuContext();
    return () =>
      h('span', {
        'data-menu-level': String(
          (rootMenu as unknown as { level: number }).level,
        ),
        'data-sub-level': String(
          (subMenu as unknown as { level: number }).level,
        ),
      });
  },
});

const MenuWrapper = defineComponent({
  // name must stay 'Menu'; useSubMenuContext walks up to it by name
  // eslint-disable-next-line vue/multi-word-component-names, vue/no-reserved-component-names
  name: 'Menu',
  setup() {
    createMenuContext({ level: 1 } as unknown as MenuProvider);
    createSubMenuContext({ level: 7 } as unknown as SubMenuProvider);
    return () => h(ChildHarness);
  },
});

describe('menu context hooks', () => {
  it('injects the root menu and sub menu contexts provided by the ancestor', () => {
    const wrapper = mount(MenuWrapper);
    const span = wrapper.find('span');
    expect(span.attributes('data-menu-level')).toBe('1');
    expect(span.attributes('data-sub-level')).toBe('7');
  });

  it('throws when context hooks run outside a component setup', () => {
    expect(() => useMenuContext()).toThrow('instance is required');
    expect(() => useSubMenuContext()).toThrow('instance is required');
  });
});

import type { MenuRecordRaw } from '@vben-core/typings';

import { mount } from '@vue/test-utils';

import { describe, expect, it } from 'vitest';

import Menu from '../menu.vue';

// Side-effect import executes the barrel entries (index, components, hooks,
// utils, normal-menu contract) so their wiring is covered.
import '../components/normal-menu/normal-menu';
import '../hooks';
import '../utils';
import '../index';

const menus: MenuRecordRaw[] = [
  { icon: 'home', name: 'Dash', path: '/dash' },
  {
    children: [{ name: 'User', path: '/system/user' }],
    icon: 'gear',
    name: 'System',
    path: '/system',
  },
];

function mountMenu(props: Record<string, unknown> = {}) {
  return mount(Menu, {
    global: {
      stubs: {
        VbenHoverCard: { template: '<div><slot/><slot name="content"/></div>' },
        VbenIcon: { template: '<i class="stub-icon" />' },
        VbenTooltip: { template: '<div><slot/><slot name="trigger"/></div>' },
      },
    },
    props: { menus, ...props },
  });
}

describe('menu-ui public entry', () => {
  it('renders leaf and sub menu items from the menu records', () => {
    const wrapper = mountMenu();
    const leaf = wrapper.findAll('li').find((li) => li.text().includes('Dash'));
    const group = wrapper
      .findAll('li')
      .find((li) => li.text().includes('System'));
    expect(leaf).toBeDefined();
    expect(group).toBeDefined();
    expect(wrapper.find('ul').exists()).toBe(true);
  });

  it('marks the default active leaf item', () => {
    const wrapper = mountMenu({ defaultActive: '/dash' });
    const active = wrapper.find('li.is-active');
    expect(active.exists()).toBe(true);
    expect(active.text()).toContain('Dash');
  });

  it('opens a sub menu when its content is clicked', async () => {
    const wrapper = mountMenu();
    const group = wrapper
      .findAll('li')
      .find((li) => li.text().includes('System'));
    expect(group?.classes()).not.toContain('is-opened');
    await group?.find('.vben-sub-menu-content__title').trigger('click');
    expect(group?.classes()).toContain('is-opened');
    expect(wrapper.find('li.vben-sub-menu .vben-menu').isVisible()).toBe(true);
  });

  it('closes a sub menu when clicking an opened one again', async () => {
    const wrapper = mountMenu();
    const group = wrapper
      .findAll('li')
      .find((li) => li.text().includes('System'));
    await group?.find('.vben-sub-menu-content__title').trigger('click');
    expect(group?.classes()).toContain('is-opened');
    await group?.find('.vben-sub-menu-content__title').trigger('click');
    expect(group?.classes()).not.toContain('is-opened');
  });

  it('renders in horizontal mode without breaking', () => {
    const wrapper = mountMenu({ mode: 'horizontal' });
    expect(wrapper.find('ul').exists()).toBe(true);
    expect(wrapper.findAll('li').length).toBeGreaterThanOrEqual(2);
  });

  it('renders nested sub menus recursively', () => {
    const wrapper = mountMenu({
      menus: [
        {
          children: [
            {
              children: [{ name: 'Deep', path: '/a/b/c' }],
              name: 'Level2',
              path: '/a/b',
            },
          ],
          name: 'Level1',
          path: '/a',
        },
      ],
    });
    expect(wrapper.text()).toContain('Level2');
    expect(wrapper.text()).toContain('Level1');
  });
});

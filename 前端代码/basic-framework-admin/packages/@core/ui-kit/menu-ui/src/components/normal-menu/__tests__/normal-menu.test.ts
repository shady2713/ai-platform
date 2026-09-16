import type { MenuRecordRaw } from '@vben-core/typings';

import type { NormalMenuProps } from '../normal-menu';

import { mount } from '@vue/test-utils';

import { describe, expect, it, vi } from 'vitest';

import NormalMenu from '../normal-menu.vue';

// Side-effect import executes the type-only props contract module.
import '../normal-menu';

// 菜单图标经 @iconify/vue 模块求值时捕获的全局 fetch 异步队列远程拉取；
// vi.hoisted 先于本文件依赖图求值执行，确保捕获到本地桩，封死外部 HTTP 边界。
vi.hoisted(() => {
  globalThis.fetch = (() =>
    Promise.resolve({
      json: () => Promise.resolve({}),
      status: 200,
    })) as unknown as typeof fetch;
});

const menus: MenuRecordRaw[] = [
  { activeIcon: 'home-active', icon: 'home', name: 'Dash', path: '/dash' },
  { icon: 'gear', name: 'System', path: '/system' },
];

function mountMenu(props: Partial<NormalMenuProps> = {}) {
  return mount(NormalMenu, { props: { menus, ...props } });
}

describe('normalMenu', () => {
  it('renders one item per menu with the menu name', () => {
    const wrapper = mountMenu();
    const items = wrapper.findAll('li');
    expect(items).toHaveLength(2);
    expect(items[0]?.text()).toContain('Dash');
    expect(items[1]?.text()).toContain('System');
  });

  it('marks the active menu with the is-active class', () => {
    const wrapper = mountMenu({ activePath: '/dash' });
    const items = wrapper.findAll('li');
    expect(items[0]?.classes()).toContain('is-active');
    expect(items[1]?.classes()).not.toContain('is-active');
  });

  it('marks a different menu active when activePath changes', () => {
    const wrapper = mountMenu({ activePath: '/system' });
    const items = wrapper.findAll('li');
    expect(items[1]?.classes()).toContain('is-active');
    expect(items[0]?.classes()).not.toContain('is-active');
  });

  it('applies the collapse and rounded classes', () => {
    const wrapper = mountMenu({ collapse: true, rounded: true });
    const ul = wrapper.get('ul');
    expect(ul.classes()).toContain('is-collapse');
    expect(ul.classes()).toContain('is-rounded');
  });

  it('emits select and enter events with the menu record', async () => {
    const wrapper = mountMenu();
    const items = wrapper.findAll('li');
    await items[1]?.trigger('click');
    expect(wrapper.emitted('select')?.[0]).toEqual([menus[1]]);
    await items[0]?.trigger('mouseenter');
    expect(wrapper.emitted('enter')?.[0]).toEqual([menus[0]]);
  });
});

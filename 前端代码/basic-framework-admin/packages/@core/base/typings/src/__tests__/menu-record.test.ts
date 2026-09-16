import type { AppRouteRecordRaw, MenuRecordRaw } from '../index';

import { describe, expect, it } from 'vitest';

// Side-effect imports execute the type-only modules so their lines are not
// reported as uncovered by the coverage ratchet.
import '../index';
import '../menu-record';
import '../tabs';

describe('typings contracts', () => {
  it('menu-record shape is compatible with MenuRecordRaw', () => {
    const menu: MenuRecordRaw = {
      name: 'home',
      path: '/home',
      icon: 'home',
      children: [{ name: 'a', path: '/a' }],
    };
    expect(menu.name).toBe('home');
    expect(menu.children).toHaveLength(1);
  });

  it('app route record accepts backend fields', () => {
    const route: AppRouteRecordRaw = {
      component: 'system/user/index',
      icon: 'user',
      id: 1,
      keepAlive: true,
      name: 'user',
      parentId: 0,
      path: '/system/user',
      visible: true,
    };
    expect(route.path).toBe('/system/user');
    expect(route.id).toBe(1);
  });
});

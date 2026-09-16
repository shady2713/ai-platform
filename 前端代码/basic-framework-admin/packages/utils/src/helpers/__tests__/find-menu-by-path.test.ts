import type { MenuRecordRaw } from '@vben-core/typings';

import { describe, expect, it } from 'vitest';

import { findMenuByPath, findRootMenuByPath } from '../find-menu-by-path';

// 示例菜单数据
const menus: MenuRecordRaw[] = [
  { name: 'Home', path: '/', children: [] },
  { name: 'About', path: '/about', children: [] },
  {
    name: 'Contact',
    path: '/contact',
    children: [
      { name: 'Email', path: '/contact/email', children: [] },
      { name: 'Phone', path: '/contact/phone', children: [] },
    ],
  },
  {
    name: 'Services',
    path: '/services',
    children: [
      { name: 'Design', path: '/services/design', children: [] },
      {
        name: 'Development',
        path: '/services/development',
        children: [
          {
            name: 'Web',
            path: '/services/development/web',
            children: [],
          },
        ],
      },
    ],
  },
];

describe('menu Finder Tests', () => {
  it('finds a top-level menu', () => {
    const menu = findMenuByPath(menus, '/about');
    expect(menu).toBeDefined();
    expect(menu?.path).toBe('/about');
  });

  it('finds a nested menu', () => {
    const menu = findMenuByPath(menus, '/services/development/web');
    expect(menu).toBeDefined();
    expect(menu?.path).toBe('/services/development/web');
  });

  it('returns null for a non-existent path', () => {
    const menu = findMenuByPath(menus, '/non-existent');
    expect(menu).toBeNull();
  });

  it('handles empty menus list', () => {
    const menu = findMenuByPath([], '/about');
    expect(menu).toBeNull();
  });

  it('handles menu items without children', () => {
    const menu = findMenuByPath(
      [{ name: 'Only', path: '/only', children: undefined }],
      '/only',
    );
    expect(menu).toBeDefined();
    expect(menu?.path).toBe('/only');
  });

  it('finds root menu by path', () => {
    const { findMenu, rootMenu, rootMenuPath } = findRootMenuByPath(
      menus,
      '/services/development/web',
    );

    expect(findMenu).toBeDefined();
    expect(rootMenu).toBeUndefined();
    expect(rootMenuPath).toBeUndefined();
    expect(findMenu?.path).toBe('/services/development/web');
  });

  it('returns null for undefined or empty path', () => {
    const menuUndefinedPath = findMenuByPath(menus);
    const menuEmptyPath = findMenuByPath(menus, '');
    expect(menuUndefinedPath).toBeNull();
    expect(menuEmptyPath).toBeNull();
  });

  it('checks for root menu when path does not exist', () => {
    const { findMenu, rootMenu, rootMenuPath } = findRootMenuByPath(
      menus,
      '/non-existent',
    );
    expect(findMenu).toBeNull();
    expect(rootMenu).toBeUndefined();
    expect(rootMenuPath).toBeUndefined();
  });
});

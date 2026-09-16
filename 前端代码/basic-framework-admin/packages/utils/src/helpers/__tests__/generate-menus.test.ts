import type { Router, RouteRecordRaw } from 'vue-router';

import type { AppRouteRecordRaw } from '@vben-core/typings';

import { createRouter, createWebHistory } from 'vue-router';

import { describe, expect, it, vi } from 'vitest';

import {
  convertServerMenuToRouteRecordStringComponent,
  generateMenus,
} from '../generate-menus';

// Nested route setup to test child inclusion and hideChildrenInMenu functionality

describe('generateMenus', () => {
  // 模拟路由数据
  const mockRoutes = [
    {
      meta: { icon: 'home-icon', title: '首页' },
      name: 'home',
      path: '/home',
    },
    {
      meta: { hideChildrenInMenu: true, icon: 'about-icon', title: '关于' },
      name: 'about',
      path: '/about',
      children: [
        {
          path: 'team',
          name: 'team',
          meta: { icon: 'team-icon', title: '团队' },
        },
      ],
    },
  ] as RouteRecordRaw[];

  // 模拟 Vue 路由器实例
  const mockRouter = {
    getRoutes: vi.fn(() => [
      { name: 'home', path: '/home' },
      { name: 'about', path: '/about' },
      { name: 'team', path: '/about/team' },
    ]),
    resolve: vi.fn((target: unknown) => {
      if (
        typeof target === 'object' &&
        target !== null &&
        'path' in target &&
        typeof target.path === 'string'
      ) {
        return { path: target.path };
      }
      throw new TypeError('Expected a route location with a path');
    }),
  };

  it('the correct menu list should be generated according to the route', async () => {
    const expectedMenus = [
      {
        badge: undefined,
        badgeType: undefined,
        badgeVariants: undefined,
        icon: 'home-icon',
        name: '首页',
        order: undefined,
        parent: undefined,
        parents: undefined,
        path: '/home',
        show: true,
        children: [],
      },
      {
        badge: undefined,
        badgeType: undefined,
        badgeVariants: undefined,
        icon: 'about-icon',
        name: '关于',
        order: undefined,
        parent: undefined,
        parents: undefined,
        path: '/about',
        show: true,
        children: [],
      },
    ];

    const menus = generateMenus(mockRoutes, mockRouter as unknown as Router);
    expect(menus).toEqual(expectedMenus);
  });

  it('includes additional meta properties in menu items', async () => {
    const mockRoutesWithMeta = [
      {
        meta: { icon: 'user-icon', order: 1, title: 'Profile' },
        name: 'profile',
        path: '/profile',
      },
    ] as RouteRecordRaw[];

    const menus = generateMenus(
      mockRoutesWithMeta,
      mockRouter as unknown as Router,
    );
    expect(menus).toEqual([
      {
        badge: undefined,
        badgeType: undefined,
        badgeVariants: undefined,
        icon: 'user-icon',
        name: 'Profile',
        order: 1,
        parent: undefined,
        parents: undefined,
        path: '/profile',
        show: true,
        children: [],
      },
    ]);
  });

  it('handles dynamic route parameters correctly', async () => {
    const mockRoutesWithParams = [
      {
        meta: { icon: 'details-icon', title: 'User Details' },
        name: 'userDetails',
        path: '/users/:userId',
      },
    ] as RouteRecordRaw[];

    const menus = generateMenus(
      mockRoutesWithParams,
      mockRouter as unknown as Router,
    );
    expect(menus).toEqual([
      {
        badge: undefined,
        badgeType: undefined,
        badgeVariants: undefined,
        icon: 'details-icon',
        name: 'User Details',
        order: undefined,
        parent: undefined,
        parents: undefined,
        path: '/users/:userId',
        show: true,
        children: [],
      },
    ]);
  });

  it('uses static redirects and falls back for dynamic redirects', async () => {
    const mockRoutesWithRedirect = [
      {
        meta: { hideChildrenInMenu: true, title: 'String redirect' },
        name: 'redirectedRoute',
        path: '/old-path',
        redirect: '/new-path',
      },
      {
        meta: { hideChildrenInMenu: true, title: 'Object redirect' },
        name: 'objectRedirect',
        path: '/object-source',
        redirect: { path: '/object-target' },
      },
      {
        meta: { hideChildrenInMenu: true, title: 'Dynamic redirect' },
        name: 'dynamicRedirect',
        path: '/dynamic-source',
        redirect: () => '/dynamic-target',
      },
      {
        meta: { icon: 'path-icon', title: 'New Path' },
        name: 'newPath',
        path: '/new-path',
      },
    ] as RouteRecordRaw[];

    const menus = generateMenus(
      mockRoutesWithRedirect,
      mockRouter as unknown as Router,
    );
    expect(menus).toEqual([
      {
        badge: undefined,
        badgeType: undefined,
        badgeVariants: undefined,
        icon: undefined,
        name: 'String redirect',
        order: undefined,
        parent: undefined,
        parents: undefined,
        path: '/new-path',
        show: true,
        children: [],
      },
      {
        badge: undefined,
        badgeType: undefined,
        badgeVariants: undefined,
        children: [],
        icon: undefined,
        name: 'Object redirect',
        order: undefined,
        parent: undefined,
        parents: undefined,
        path: '/object-target',
        show: true,
      },
      {
        badge: undefined,
        badgeType: undefined,
        badgeVariants: undefined,
        children: [],
        icon: undefined,
        name: 'Dynamic redirect',
        order: undefined,
        parent: undefined,
        parents: undefined,
        path: '/dynamic-source',
        show: true,
      },
      {
        badge: undefined,
        badgeType: undefined,
        badgeVariants: undefined,
        icon: 'path-icon',
        name: 'New Path',
        order: undefined,
        parent: undefined,
        parents: undefined,
        path: '/new-path',
        show: true,
        children: [],
      },
    ]);
  });

  it('adds parent paths to visible child menus', () => {
    const child = {
      meta: { title: 'Child' },
      name: 'child',
      path: 'child',
    };
    const menus = generateMenus(
      [
        {
          children: [child],
          meta: { title: 'Parent' },
          name: 'parent',
          path: '/parent',
        },
      ] as RouteRecordRaw[],
      {
        ...mockRouter,
        getRoutes: vi.fn(() => [
          { name: 'parent', path: '/parent' },
          { name: 'child', path: '/parent/child' },
        ]),
      } as unknown as Router,
    );

    expect(menus[0]?.children?.[0]).toEqual(
      expect.objectContaining({
        parent: '/parent',
        parents: ['/parent'],
      }),
    );
  });

  const routes: RouteRecordRaw[] = [
    {
      component: () => null,
      meta: { order: 2, title: 'Home' },
      name: 'home',
      path: '/',
    },
    {
      component: () => null,
      meta: { order: 1, title: 'About' },
      name: 'about',
      path: '/about',
    },
  ];

  const router: Router = createRouter({
    history: createWebHistory(),
    routes,
  });

  it('should generate menu list with correct order', async () => {
    const menus = generateMenus(routes, router);
    const expectedMenus = [
      {
        badge: undefined,
        badgeType: undefined,
        badgeVariants: undefined,
        icon: undefined,
        name: 'About',
        order: 1,
        parent: undefined,
        parents: undefined,
        path: '/about',
        show: true,
        children: [],
      },
      {
        badge: undefined,
        badgeType: undefined,
        badgeVariants: undefined,
        icon: undefined,
        name: 'Home',
        order: 2,
        parent: undefined,
        parents: undefined,
        path: '/',
        show: true,
        children: [],
      },
    ];

    expect(menus).toEqual(expectedMenus);
  });

  it('should handle empty routes', async () => {
    const emptyRoutes: RouteRecordRaw[] = [];
    const menus = generateMenus(emptyRoutes, router);
    expect(menus).toEqual([]);
  });
});

describe('convertServerMenuToRouteRecordStringComponent', () => {
  it('converts nested menus without mutating the server response', () => {
    const source = [
      {
        children: [
          {
            component: 'system/user/index?tab=active&source=menu',
            icon: 'user',
            id: 2,
            keepAlive: true,
            name: '用户管理',
            parentId: 1,
            path: 'user',
            sort: 2,
            visible: true,
          },
        ],
        component: 'Layout',
        icon: 'system',
        id: 1,
        name: '系统管理',
        parentId: 0,
        path: 'system',
        sort: 1,
        visible: true,
      },
    ] satisfies AppRouteRecordRaw[];
    const snapshot = structuredClone(source);

    const routes = convertServerMenuToRouteRecordStringComponent(source);

    expect(routes).toEqual([
      expect.objectContaining({
        component: 'BasicLayout',
        name: '系统管理',
        path: '/system',
        children: [
          expect.objectContaining({
            component: 'system/user/index',
            meta: expect.objectContaining({
              query: { source: 'menu', tab: 'active' },
            }),
            path: '/system/user',
          }),
        ],
      }),
    ]);
    expect(source).toEqual(snapshot);
  });

  it('converts external links and strips the iframe marker', () => {
    const routes = convertServerMenuToRouteRecordStringComponent([
      {
        icon: 'docs',
        id: 8,
        name: '文档',
        parentId: 0,
        path: 'https://example.com/docs?_iframe=1&lang=zh',
        sort: 3,
        visible: false,
      },
      {
        id: 9,
        name: '官网',
        parentId: 0,
        path: 'https://example.com',
        sort: 4,
        visible: true,
      },
    ] satisfies AppRouteRecordRaw[]);

    expect(routes[0]).toEqual(
      expect.objectContaining({
        component: 'IFrameView',
        meta: expect.objectContaining({
          hideInMenu: true,
          iframeSrc: 'https://example.com/docs?lang=zh',
          link: undefined,
        }),
        path: '8',
      }),
    );
    expect(routes[1]?.meta?.link).toBe('https://example.com');
  });

  it('makes duplicate route names deterministic and reports the conflict', () => {
    const consoleError = vi
      .spyOn(console, 'error')
      .mockImplementation(() => {});
    const routes = convertServerMenuToRouteRecordStringComponent([
      {
        component: 'first',
        id: 10,
        name: '相同名称',
        parentId: 0,
        path: 'first',
        sort: 1,
        visible: true,
      },
      {
        component: 'second',
        id: 11,
        name: '相同名称',
        parentId: 0,
        path: 'second',
        sort: 2,
        visible: true,
      },
    ] satisfies AppRouteRecordRaw[]);

    expect(routes.map(({ name }) => name)).toEqual(['相同名称', '相同名称11']);
    expect(consoleError).toHaveBeenCalledOnce();
    consoleError.mockRestore();
  });

  it('normalizes nested groups and legacy Layout leaf components', () => {
    const routes = convertServerMenuToRouteRecordStringComponent([
      {
        children: [
          {
            children: [
              {
                component: 'Layout',
                id: 23,
                name: '叶节点',
                parentId: 22,
                path: 'leaf',
                visible: true,
              },
            ],
            component: 'ignored',
            id: 22,
            name: '分组',
            parentId: 21,
            path: 'group',
            visible: true,
          },
        ],
        component: 'ignored',
        id: 21,
        name: '根节点',
        parentId: 0,
        path: 'root',
        visible: true,
      },
    ] satisfies AppRouteRecordRaw[]);

    expect(routes[0]?.children?.[0]).toEqual(
      expect.objectContaining({
        component: '',
        children: [expect.objectContaining({ component: 'BasicLayout' })],
      }),
    );
  });
});

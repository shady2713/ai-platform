import type { GenerateMenuAndRoutesOptions, RouteRecordRaw } from '@vben/types';

import { createApp, h } from 'vue';
import {
  createMemoryHistory,
  createRouter as createVueRouter,
} from 'vue-router';

import { beforeEach, describe, expect, it, vi } from 'vitest';

import { generateAccessible, resetAccessibleRoutes } from './accessible';

type Router = GenerateMenuAndRoutesOptions['router'];

const routeMocks = vi.hoisted(() => ({
  generateMenus: vi.fn(),
  generateRoutesByBackend: vi.fn(),
  generateRoutesByFrontend: vi.fn(),
}));

vi.mock('@vben/utils', () => {
  const clone = (value: unknown): unknown => {
    if (Array.isArray(value)) return value.map((item) => clone(item));
    if (value && typeof value === 'object') {
      return Object.fromEntries(
        Object.entries(value).map(([key, child]) => [key, clone(child)]),
      );
    }
    return value;
  };
  const mapTree = (
    routes: Record<string, unknown>[],
    transform: (route: Record<string, unknown>) => unknown,
  ): unknown[] =>
    routes.map((route) => {
      const mapped = { ...route };
      if (Array.isArray(route.children)) {
        mapped.children = mapTree(route.children, transform);
      }
      return transform(mapped);
    });

  return {
    cloneDeep: vi.fn(clone),
    generateMenus: routeMocks.generateMenus,
    generateRoutesByBackend: routeMocks.generateRoutesByBackend,
    generateRoutesByFrontend: routeMocks.generateRoutesByFrontend,
    isFunction: (value: unknown) => typeof value === 'function',
    isString: (value: unknown) => typeof value === 'string',
    mapTree,
  };
});

function createRouter(root?: RouteRecordRaw): Router {
  return {
    addRoute: vi.fn(() => vi.fn()),
    getRoutes: vi.fn(() => (root ? [root] : [])),
    removeRoute: vi.fn(),
  } as unknown as Router;
}

function createOptions(
  router: Router,
  routes: RouteRecordRaw[],
  roles: string[] = [],
): GenerateMenuAndRoutesOptions {
  return { roles, router, routes } as unknown as GenerateMenuAndRoutesOptions;
}

describe('generateAccessible', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    routeMocks.generateMenus.mockReturnValue([{ key: 'menu' }]);
  });

  it('merges frontend routes into the root without duplicating existing names', async () => {
    const originalRoutes: RouteRecordRaw[] = [
      { name: 'source', path: '/source', redirect: '/source-target' },
    ];
    const existingRoute = {
      children: [{ path: '/old-child' }],
      component: 'layout',
      name: 'existing',
      path: '/existing',
    };
    const newRoute = {
      children: [{ path: '/new-child' }],
      component: 'layout',
      name: 'new',
      path: '/new',
    };
    const standaloneRoute = {
      meta: { noBasicLayout: true },
      name: 'standalone',
      path: '/standalone',
    };
    routeMocks.generateRoutesByFrontend.mockResolvedValue([
      existingRoute,
      newRoute,
      standaloneRoute,
    ]);
    const root: RouteRecordRaw = {
      children: [
        { name: 'existing', path: '/stale', redirect: '/stale-target' },
      ],
      component: {},
      name: 'Root',
      path: '/',
    };
    const router = createRouter(root);
    const options = createOptions(router, originalRoutes, ['admin']);

    const result = await generateAccessible('frontend', options);

    expect(routeMocks.generateRoutesByFrontend).toHaveBeenCalledWith(
      options.routes,
      ['admin'],
      undefined,
    );
    expect(options.routes).not.toBe(originalRoutes);
    expect(router.addRoute).toHaveBeenCalledWith(
      'Root',
      expect.objectContaining({
        name: 'existing',
        redirect: '/old-child',
      }),
    );
    expect(router.addRoute).toHaveBeenCalledWith(
      'Root',
      expect.objectContaining({
        name: 'new',
        redirect: '/new-child',
      }),
    );
    expect(result.accessibleRoutes[0]).not.toHaveProperty('component');
    expect(result.accessibleRoutes[1]).not.toHaveProperty('component');
    expect(router.addRoute).toHaveBeenCalledWith(standaloneRoute);
    expect(router.removeRoute).not.toHaveBeenCalled();
    expect(routeMocks.generateMenus).toHaveBeenCalledWith(
      result.accessibleRoutes,
      router,
    );
    expect(result.accessibleMenus).toEqual([{ key: 'menu' }]);
  });

  it('adds backend routes directly when a root route is absent', async () => {
    const backendRoutes = [{ name: 'backend', path: '/backend' }];
    routeMocks.generateRoutesByBackend.mockResolvedValue(backendRoutes);
    const router = createRouter();
    const options = createOptions(router, []);

    const result = await generateAccessible('backend', options);

    expect(routeMocks.generateRoutesByBackend).toHaveBeenCalledWith(options);
    expect(router.addRoute).toHaveBeenCalledOnce();
    expect(router.addRoute).toHaveBeenCalledWith(backendRoutes[0]);
    expect(router.removeRoute).not.toHaveBeenCalled();
    expect(result.accessibleRoutes).toEqual(backendRoutes);
  });

  it('combines mixed routes and wraps keep-alive components with the route name', async () => {
    const view = { template: '<section><slot /></section>' };
    const loadView = vi.fn().mockResolvedValue({ default: view });
    routeMocks.generateRoutesByFrontend.mockResolvedValue([
      {
        component: loadView,
        meta: { keepAlive: true, noBasicLayout: true },
        name: 'CachedView',
        path: '/cached',
      },
    ]);
    routeMocks.generateRoutesByBackend.mockResolvedValue([
      {
        children: [{ path: 'relative-child' }],
        meta: { noBasicLayout: true },
        name: 'relative',
        path: '/relative',
      },
    ]);
    const router = createRouter();

    const result = await generateAccessible('mixed', createOptions(router, []));

    expect(routeMocks.generateRoutesByFrontend).toHaveBeenCalledWith(
      [],
      [],
      undefined,
    );
    expect(result.accessibleRoutes).toHaveLength(2);
    expect(result.accessibleRoutes[1]).not.toHaveProperty('redirect');
    const loaded = await (
      result.accessibleRoutes[0]?.component as () => Promise<{
        name: string;
      }>
    )();
    expect(loadView).toHaveBeenCalledOnce();
    expect(loaded.name).toBe('CachedView');
    const container = document.createElement('div');
    const app = createApp({
      render: () =>
        h(loaded, { title: 'preserved' }, () => 'forwarded content'),
    });
    app.mount(container);
    expect(container.querySelector('section')?.getAttribute('title')).toBe(
      'preserved',
    );
    expect(container.textContent).toBe('forwarded content');
    app.unmount();
  });

  it('preserves async component results that do not expose a default export', async () => {
    const moduleResult = { named: 'component' };
    routeMocks.generateRoutesByFrontend.mockResolvedValue([
      {
        component: vi.fn().mockResolvedValue(moduleResult),
        meta: { keepAlive: true, noBasicLayout: true },
        name: 'NamedOnly',
        path: '/named-only',
      },
    ]);
    const router = createRouter();

    const result = await generateAccessible(
      'frontend',
      createOptions(router, []),
    );

    await expect(
      (
        result.accessibleRoutes[0]?.component as () => Promise<{
          named: string;
        }>
      )(),
    ).resolves.toBe(moduleResult);
  });

  it('removes both nested and standalone routes from the previous user', async () => {
    const router = createVueRouter({
      history: createMemoryHistory(),
      routes: [
        {
          name: 'Root',
          path: '/',
          component: {},
          children: [{ name: 'Static', path: '/static', component: {} }],
        },
      ],
    });
    routeMocks.generateRoutesByBackend.mockResolvedValue([
      { name: 'Admin', path: '/admin', component: {} },
      {
        name: 'Standalone',
        path: '/standalone',
        component: {},
        meta: { noBasicLayout: true },
      },
    ]);
    await generateAccessible('backend', createOptions(router, []));
    expect(router.hasRoute('Admin')).toBe(true);
    expect(router.hasRoute('Standalone')).toBe(true);
    routeMocks.generateRoutesByBackend.mockResolvedValue([
      { name: 'Profile', path: '/profile', component: {} },
    ]);
    await generateAccessible('backend', createOptions(router, []));
    expect(router.hasRoute('Admin')).toBe(false);
    expect(router.hasRoute('Standalone')).toBe(false);
    expect(router.hasRoute('Profile')).toBe(true);
    expect(router.hasRoute('Static')).toBe(true);
    resetAccessibleRoutes(router);
    expect(router.hasRoute('Profile')).toBe(false);
    expect(router.hasRoute('Static')).toBe(true);
  });

  it('does not register routes if logout happens while they are loading', async () => {
    const router = createRouter();
    let finish!: (value: unknown) => void;
    routeMocks.generateRoutesByBackend.mockReturnValue(
      new Promise((resolve) => {
        finish = resolve;
      }),
    );
    const generation = generateAccessible('backend', createOptions(router, []));
    resetAccessibleRoutes(router);
    finish([{ name: 'Admin', path: '/admin', component: {} }]);
    await expect(generation).rejects.toThrow(
      'Authentication changed while generating access routes',
    );
    expect(router.addRoute).not.toHaveBeenCalled();
  });

  it('fails closed on unknown access modes', async () => {
    const router = createRouter();
    await expect(
      generateAccessible('unknown' as never, createOptions(router, [])),
    ).rejects.toThrow('Unsupported access mode');
    expect(router.addRoute).not.toHaveBeenCalled();
  });
});

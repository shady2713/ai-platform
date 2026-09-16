import type { RouteRecordRaw } from 'vue-router';

import { describe, expect, it, vi } from 'vitest';

import { coreRoutes, fallbackNotFoundRoute } from './core';
import { accessRoutes, componentKeys, coreRouteNames, routes } from './index';

vi.mock('#/locales', () => ({ $t: (key: string) => key }));
vi.mock('@vben/preferences', () => ({
  preferences: { app: { defaultHomePath: '/dashboard' } },
}));
vi.mock('#/layouts/auth.vue', () => ({ default: { name: 'AuthLayout' } }));
vi.mock('#/layouts/basic.vue', () => ({ default: { name: 'BasicLayout' } }));
vi.mock('#/views/_core/authentication/forget-password.vue', () => ({
  default: { name: 'ForgetPassword' },
}));
vi.mock('#/views/_core/authentication/login.vue', () => ({
  default: { name: 'Login' },
}));
vi.mock('#/views/_core/fallback/not-found.vue', () => ({
  default: { name: 'NotFound' },
}));
vi.mock('#/views/_core/profile/index.vue', () => ({
  default: { name: 'Profile' },
}));
vi.mock('#/views/dashboard/analytics/index.vue', () => ({
  default: { name: 'Dashboard' },
}));
vi.mock('#/views/infra/job/logger/index.vue', () => ({
  default: { name: 'InfraJobLog' },
}));
vi.mock('#/views/system/notify/my/index.vue', () => ({
  default: { name: 'MyNotifyMessage' },
}));

function routeNames(items: RouteRecordRaw[]): string[] {
  return items.flatMap((route) => [
    String(route.name ?? ''),
    ...routeNames(route.children ?? []),
  ]);
}

function flattenRoutes(items: RouteRecordRaw[]): RouteRecordRaw[] {
  return items.flatMap((route) => [
    route,
    ...flattenRoutes(route.children ?? []),
  ]);
}

describe('application route contracts', () => {
  it('keeps authentication routes public and fallback last', () => {
    expect(coreRouteNames).toEqual(
      expect.arrayContaining([
        'Root',
        'Authentication',
        'Login',
        'ForgetPassword',
      ]),
    );
    expect(routes.at(-1)).toBe(fallbackNotFoundRoute);
    expect(fallbackNotFoundRoute.path).toBe('/:path(.*)*');
    expect(coreRoutes[0]?.redirect).toBe('/dashboard');
  });

  it('keeps hidden framework routes unique and backed by real view components', () => {
    const names = routeNames(accessRoutes).filter(Boolean);

    expect(names).toEqual(
      expect.arrayContaining([
        'Dashboard',
        'Profile',
        'InfraJobLog',
        'MyNotifyMessage',
      ]),
    );
    expect(new Set(names).size).toBe(names.length);
    expect(componentKeys).toEqual(
      expect.arrayContaining([
        '/dashboard/analytics/index',
        '/infra/job/logger/index',
        '/system/notify/my/index',
      ]),
    );
    expect(componentKeys.some((path) => path.includes('/modules/'))).toBe(
      false,
    );
  });

  it('keeps every declared lazy view resolvable', async () => {
    const declaredRoutes = [
      ...flattenRoutes(coreRoutes),
      ...flattenRoutes(accessRoutes),
      fallbackNotFoundRoute,
    ];
    const loaders = declaredRoutes
      .map((route) => route.component)
      .filter(
        (component): component is () => Promise<unknown> =>
          typeof component === 'function',
      );

    await expect(
      Promise.all(loaders.map((load) => load())),
    ).resolves.toHaveLength(loaders.length);
  });
});

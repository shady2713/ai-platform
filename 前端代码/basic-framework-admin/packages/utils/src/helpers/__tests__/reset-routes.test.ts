import type { Router, RouteRecordRaw } from 'vue-router';

import { describe, expect, it, vi } from 'vitest';

import { resetStaticRoutes } from '../reset-routes';

describe('resetStaticRoutes', () => {
  it('保留静态父子路由，只删除仍存在的动态命名路由', () => {
    const removeRoute = vi.fn();
    const hasRoute = vi.fn((name) => name !== 'AlreadyGone');
    const router = {
      getRoutes: () => [
        { name: 'StaticRoot' },
        { name: 'StaticChild' },
        { name: 'Dynamic' },
        { name: 'AlreadyGone' },
        { path: '/anonymous' },
      ],
      hasRoute,
      removeRoute,
    } as unknown as Router;
    const staticRoutes = [
      {
        children: [{ name: 'StaticChild', path: 'child' }],
        name: 'StaticRoot',
        path: '/static',
      },
    ] as RouteRecordRaw[];

    resetStaticRoutes(router, staticRoutes);

    expect(hasRoute).toHaveBeenCalledTimes(2);
    expect(removeRoute).toHaveBeenCalledOnce();
    expect(removeRoute).toHaveBeenCalledWith('Dynamic');
  });

  it('静态路由缺少 name 时告警并继续清理其他路由', () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => undefined);
    const removeRoute = vi.fn();
    const router = {
      getRoutes: () => [{ name: 'Dynamic' }],
      hasRoute: () => true,
      removeRoute,
    } as unknown as Router;

    resetStaticRoutes(router, [{ path: '/anonymous' } as RouteRecordRaw]);

    expect(warn).toHaveBeenCalledWith(
      'The route with the path /anonymous needs to have the field name specified.',
    );
    expect(removeRoute).toHaveBeenCalledWith('Dynamic');
    warn.mockRestore();
  });
});

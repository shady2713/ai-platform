import type { Router } from 'vue-router';

import type {
  ComponentRecordType,
  GenerateMenuAndRoutesOptions,
  RouteRecordStringComponent,
} from '@vben-core/typings';

import { describe, expect, it, vi } from 'vitest';

import { generateRoutesByBackend } from '../generate-routes-backend';

const lazyComponent: ComponentRecordType[string] = async () => ({});
const router = {} as Router;

function options(
  overrides: Partial<GenerateMenuAndRoutesOptions> = {},
): GenerateMenuAndRoutesOptions {
  return {
    router,
    routes: [{ component: () => null, name: 'Root', path: '/' }],
    ...overrides,
  };
}

describe('generateRoutesByBackend', () => {
  it('未配置菜单接口时不生成动态路由', async () => {
    await expect(generateRoutesByBackend(options())).resolves.toEqual([]);
  });

  it('转换布局、页面、嵌套路由与禁止访问组件且不修改接口响应', async () => {
    const layout = vi.fn(lazyComponent);
    const page = vi.fn(lazyComponent);
    const forbidden = vi.fn(lazyComponent);
    const menuRoutes: RouteRecordStringComponent[] = [
      {
        children: [
          {
            component: '../views/system/user/index',
            meta: {
              menuVisibleWithForbidden: true,
              title: '用户',
            },
            name: 'SystemUser',
            path: 'user',
          },
        ],
        component: 'BasicLayout',
        meta: { title: '系统' },
        name: 'System',
        path: '/system',
      },
    ];

    const result = await generateRoutesByBackend(
      options({
        fetchMenuListAsync: async () => menuRoutes,
        forbiddenComponent: forbidden,
        layoutMap: { BasicLayout: layout },
        pageMap: { './views/system/user/index.vue': page },
      }),
    );

    expect(result[0]).toMatchObject({ name: 'Root', path: '/' });
    expect(result[1]?.component).toBe(layout);
    expect(result[1]?.children?.[0]?.component).toBe(forbidden);
    expect(menuRoutes[0]?.component).toBe('BasicLayout');
    expect(menuRoutes[0]?.children?.[0]?.component).toBe(
      '../views/system/user/index',
    );
  });

  it('未知页面使用明确配置的 404 组件', async () => {
    const fallback = vi.fn(lazyComponent);
    const result = await generateRoutesByBackend(
      options({
        fetchMenuListAsync: async () => [
          {
            component: '/views/unknown/page.vue',
            name: 'Unknown',
            path: '/unknown',
          },
        ],
        pageMap: { '/_core/fallback/not-found.vue': fallback },
      }),
    );

    expect(result[1]?.component).toBe(fallback);
  });

  it('路由名缺失或 404 兜底缺失时立即失败', async () => {
    await expect(
      generateRoutesByBackend(
        options({
          fetchMenuListAsync: async () => [
            { component: 'BasicLayout', path: '/missing-name' },
          ],
          layoutMap: { BasicLayout: lazyComponent },
        }),
      ),
    ).rejects.toThrow('Backend route is missing required name: /missing-name');

    await expect(
      generateRoutesByBackend(
        options({
          fetchMenuListAsync: async () => [
            {
              component: '/views/unknown/page',
              name: 'Unknown',
              path: '/unknown',
            },
          ],
        }),
      ),
    ).rejects.toThrow(
      'Backend route component is invalid and no fallback is configured: /unknown/page.vue',
    );
  });

  it('菜单接口异常保持原始错误语义', async () => {
    const failure = new Error('menu unavailable');
    await expect(
      generateRoutesByBackend(
        options({
          fetchMenuListAsync: async () => {
            throw failure;
          },
        }),
      ),
    ).rejects.toBe(failure);
  });
});

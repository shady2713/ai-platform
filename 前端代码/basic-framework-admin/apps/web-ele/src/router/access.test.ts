import type {
  AppRouteRecordRaw,
  GenerateMenuAndRoutesOptions,
} from '@vben/types';

import { beforeEach, describe, expect, it, vi } from 'vitest';

import { generateAccess } from './access';

const mocks = vi.hoisted(() => ({
  accessMenus: [{ component: 'SystemUser', path: '/system/user' }],
  convertServerMenu: vi.fn(),
  generateAccessible: vi.fn(),
}));

vi.mock('@vben/access', () => ({
  generateAccessible: mocks.generateAccessible,
}));
vi.mock('@vben/preferences', () => ({
  preferences: { app: { accessMode: 'backend' } },
}));
vi.mock('@vben/stores', () => ({
  useAccessStore: () => ({ accessMenus: mocks.accessMenus }),
}));
vi.mock('@vben/utils', () => ({
  convertServerMenuToRouteRecordStringComponent: mocks.convertServerMenu,
}));
vi.mock('#/layouts', () => ({
  BasicLayout: { name: 'BasicLayout' },
  IFrameView: { name: 'IFrameView' },
}));

describe('route access generation', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mocks.convertServerMenu.mockReturnValue([{ path: '/converted' }]);
    mocks.generateAccessible.mockImplementation(
      async (_mode: string, options: GenerateMenuAndRoutesOptions) => options,
    );
  });

  it('adapts backend menus and registers only supported layout components', async () => {
    const options = {
      roles: ['admin'],
      router: { addRoute: vi.fn() },
      routes: [],
    } as unknown as GenerateMenuAndRoutesOptions;

    await generateAccess(options);
    const generated = mocks.generateAccessible.mock
      .calls[0]?.[1] as unknown as {
      fetchMenuListAsync: () => Promise<AppRouteRecordRaw[]>;
      layoutMap: Record<string, unknown>;
      pageMap: Record<string, unknown>;
    };

    expect(mocks.generateAccessible).toHaveBeenCalledWith(
      'backend',
      expect.objectContaining({ roles: ['admin'] }),
    );
    await expect(generated.fetchMenuListAsync()).resolves.toEqual([
      { path: '/converted' },
    ]);
    expect(mocks.convertServerMenu).toHaveBeenCalledWith(mocks.accessMenus);
    expect(Object.keys(generated.layoutMap)).toEqual([
      'BasicLayout',
      'IFrameView',
    ]);
    expect(Object.keys(generated.pageMap)).toContain(
      '../views/dashboard/analytics/index.vue',
    );
  });
});

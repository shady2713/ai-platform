import type { Router } from 'vue-router';

import { beforeEach, describe, expect, it, vi } from 'vitest';

import { createRouterGuard } from './guard';

interface TestRoute {
  fullPath: string;
  meta: Record<string, unknown>;
  name: string;
  path: string;
  query: Record<string, unknown>;
}

type BeforeGuard = (
  to: TestRoute,
  from: TestRoute,
) => Promise<unknown> | unknown;
type AfterGuard = (to: TestRoute) => void;

const mocks = vi.hoisted(() => ({
  accessStore: {
    accessToken: null as null | string,
    isAccessChecked: false,
    setAccessMenus: vi.fn(),
    setAccessRoutes: vi.fn(),
    setIsAccessChecked: vi.fn(),
  },
  authStore: {
    fetchUserInfo: vi.fn(),
    restoreSession: vi.fn(),
  },
  closeLoading: vi.fn(),
  dictStore: {
    setDictCacheByApi: vi.fn(),
  },
  generateAccess: vi.fn(),
  logWarn: vi.fn(),
  startProgress: vi.fn(),
  stopProgress: vi.fn(),
  userStore: {
    setUserRoles: vi.fn(),
    userInfo: null as null | { id: number },
    userRoles: [] as string[],
  },
}));

vi.mock('@vben/constants', () => ({ LOGIN_PATH: '/auth/login' }));
vi.mock('@vben/locales', () => ({ $t: (key: string) => key }));
vi.mock('@vben/preferences', () => ({
  preferences: {
    app: { defaultHomePath: '/dashboard' },
    transition: { progress: true },
  },
}));
vi.mock('@vben/stores', () => ({
  useAccessStore: () => mocks.accessStore,
  useDictStore: () => mocks.dictStore,
  useUserStore: () => mocks.userStore,
}));
vi.mock('@vben/utils', () => ({
  logWarn: mocks.logWarn,
  startProgress: mocks.startProgress,
  stopProgress: mocks.stopProgress,
}));
vi.mock('#/api/system/dict/data', () => ({
  getSimpleDictDataList: vi.fn(),
}));
vi.mock('#/router/routes', () => ({
  accessRoutes: [],
  coreRouteNames: ['Login'],
}));
vi.mock('#/store', () => ({ useAuthStore: () => mocks.authStore }));
vi.mock('#/utils/feedback', () => ({
  showLoadingMessage: () => ({ close: mocks.closeLoading }),
}));
vi.mock('./access', () => ({ generateAccess: mocks.generateAccess }));

function route(overrides: Partial<TestRoute> = {}): TestRoute {
  const path = overrides.path ?? '/system/user';
  return {
    fullPath: path,
    meta: {},
    name: 'SystemUser',
    path,
    query: {},
    ...overrides,
  };
}

function createRouterMock() {
  const beforeGuards: BeforeGuard[] = [];
  const afterGuards: AfterGuard[] = [];
  const resolve = vi.fn((path: string) => ({ fullPath: path, path }));
  const router = {
    afterEach: vi.fn((guard: AfterGuard) => afterGuards.push(guard)),
    beforeEach: vi.fn((guard: BeforeGuard) => beforeGuards.push(guard)),
    resolve,
  } as unknown as Router;
  createRouterGuard(router);
  return { afterGuards, beforeGuards, resolve, router };
}

describe('createRouterGuard', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mocks.accessStore.accessToken = null;
    mocks.accessStore.isAccessChecked = false;
    mocks.accessStore.setIsAccessChecked.mockImplementation(
      (checked: boolean) => {
        mocks.accessStore.isAccessChecked = checked;
      },
    );
    mocks.userStore.userInfo = null;
    mocks.userStore.userRoles = [];
    mocks.authStore.restoreSession.mockResolvedValue(false);
    mocks.authStore.fetchUserInfo.mockResolvedValue({ user: { id: 1 } });
    mocks.dictStore.setDictCacheByApi.mockResolvedValue(undefined);
    mocks.generateAccess.mockResolvedValue({
      accessibleMenus: [{ key: 'system' }],
      accessibleRoutes: [{ path: '/system' }],
    });
  });

  it('只为首次访问路径启动进度并在导航结束后标记已加载', () => {
    const { afterGuards, beforeGuards } = createRouterMock();
    const target = route();

    expect(beforeGuards[0]?.(target, route())).toBe(true);
    expect(target.meta.loaded).toBe(false);
    expect(mocks.startProgress).toHaveBeenCalledOnce();

    afterGuards[0]?.(target);
    const secondVisit = route();
    beforeGuards[0]?.(secondVisit, route());

    expect(secondVisit.meta.loaded).toBe(true);
    expect(mocks.startProgress).toHaveBeenCalledOnce();
    expect(mocks.stopProgress).toHaveBeenCalledOnce();
  });

  it('无会话访问受保护页面时仅恢复一次并跳转登录页', async () => {
    const { beforeGuards } = createRouterMock();
    const result = await beforeGuards[1]?.(
      route({ fullPath: '/system/user?page=1' }),
      route({ path: '/' }),
    );

    expect(mocks.authStore.restoreSession).toHaveBeenCalledOnce();
    expect(result).toEqual({
      path: '/auth/login',
      query: { redirect: '%2Fsystem%2Fuser%3Fpage%3D1' },
      replace: true,
    });
  });

  it('登录页已有令牌时拒绝外部 redirect 并返回默认首页', async () => {
    mocks.accessStore.accessToken = 'access-token';
    const { beforeGuards } = createRouterMock();

    await expect(
      beforeGuards[1]?.(
        route({
          name: 'Login',
          path: '/auth/login',
          query: { redirect: '%2F%2Fevil.example' },
        }),
        route(),
      ),
    ).resolves.toBe('/dashboard');
  });

  it('明确公开页面不触发会话恢复', async () => {
    const { beforeGuards } = createRouterMock();

    await expect(
      beforeGuards[1]?.(
        route({ meta: { ignoreAccess: true }, path: '/public' }),
        route(),
      ),
    ).resolves.toBe(true);
    expect(mocks.authStore.restoreSession).not.toHaveBeenCalled();
  });

  it('首次认证访问生成权限路由并显式吸收字典预加载失败', async () => {
    mocks.accessStore.accessToken = 'access-token';
    mocks.userStore.userRoles = ['admin'];
    mocks.dictStore.setDictCacheByApi.mockRejectedValue(
      new Error('dictionary unavailable'),
    );
    const { beforeGuards, resolve } = createRouterMock();

    const result = await beforeGuards[1]?.(
      route(),
      route({ query: { redirect: '%2Fsystem%2Frole' } }),
    );

    expect(mocks.authStore.fetchUserInfo).toHaveBeenCalledOnce();
    expect(mocks.closeLoading).toHaveBeenCalledOnce();
    expect(mocks.generateAccess).toHaveBeenCalledWith(
      expect.objectContaining({ roles: ['admin'] }),
    );
    expect(mocks.accessStore.setAccessMenus).toHaveBeenCalled();
    expect(mocks.accessStore.setAccessRoutes).toHaveBeenCalled();
    expect(mocks.accessStore.setIsAccessChecked).toHaveBeenCalledWith(true);
    expect(resolve).toHaveBeenCalledWith('/system/role');
    expect(result).toEqual({
      fullPath: '/system/role',
      path: '/system/role',
      replace: true,
    });
    await vi.waitFor(() =>
      expect(mocks.logWarn).toHaveBeenCalledWith('Dictionary preload failed'),
    );
  });

  it('权限路由已经生成后不重复请求', async () => {
    mocks.accessStore.accessToken = 'access-token';
    mocks.accessStore.isAccessChecked = true;
    const { beforeGuards } = createRouterMock();

    await expect(beforeGuards[1]?.(route(), route())).resolves.toBe(true);
    expect(mocks.generateAccess).not.toHaveBeenCalled();
  });

  it('恢复会话后的权限加载失败返回登录页并关闭加载提示', async () => {
    mocks.accessStore.accessToken = 'restored';
    mocks.authStore.fetchUserInfo.mockRejectedValue(new Error('unavailable'));
    const { beforeGuards } = createRouterMock();
    await expect(beforeGuards[1]?.(route(), route())).resolves.toEqual({
      path: '/auth/login',
      query: { redirect: '%2Fsystem%2Fuser' },
      replace: true,
    });
    expect(mocks.closeLoading).toHaveBeenCalledOnce();
    expect(mocks.generateAccess).not.toHaveBeenCalled();
  });
});

import { createApp } from 'vue';

import { initStores, useAccessStore, useUserStore } from '@vben/stores';

import { beforeEach, describe, expect, it, vi } from 'vitest';

import {
  getAuthPermissionInfoApi,
  loginApi,
  logoutApi,
  refreshTokenApi,
} from '#/api';

import { useAuthStore } from './auth';

const { router } = vi.hoisted(() => ({
  router: {
    currentRoute: {
      value: {
        fullPath: '/auth/login?redirect=%2Fdashboard',
        path: '/auth/login',
      },
    },
    push: vi.fn(),
    replace: vi.fn(),
  },
}));

vi.mock('vue-router', () => ({ useRouter: () => router }));
vi.mock('@vben/access', () => ({ resetAccessibleRoutes: vi.fn() }));
vi.mock('#/api', () => ({
  getAuthPermissionInfoApi: vi.fn(),
  loginApi: vi.fn(),
  logoutApi: vi.fn(),
  refreshTokenApi: vi.fn(),
}));
vi.mock('#/locales', () => ({ $t: (key: string) => key }));
vi.mock('#/utils/feedback', () => ({ showSuccessNotification: vi.fn() }));

const permissionInfo = {
  menus: [],
  permissions: [],
  roles: [],
  user: { id: 1, nickname: '管理员' },
};

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason: Error) => void;
  const promise = new Promise<T>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, reject, resolve };
}

describe('auth store', () => {
  let accessStore: ReturnType<typeof useAccessStore>;

  beforeEach(async () => {
    vi.resetAllMocks();
    sessionStorage.clear();
    await initStores(createApp({}), { namespace: 'auth-test' });
    accessStore = useAccessStore();
    vi.mocked(getAuthPermissionInfoApi).mockResolvedValue(
      permissionInfo as never,
    );
  });

  it('does not nest the login page in its own redirect query', async () => {
    await useAuthStore().logout();
    expect(accessStore.accessToken).toBeNull();
    expect(router.replace).toHaveBeenCalledWith({
      path: '/auth/login',
      query: {},
    });
  });

  it('accepts an access token and permission information after password authentication', async () => {
    vi.mocked(loginApi).mockResolvedValue({
      accessToken: 'current',
      userId: 1,
    });
    const store = useAuthStore();
    await store.authLogin('username', {
      username: 'admin',
      password: 'valid-password',
    });
    expect(accessStore.accessToken).toBe('current');
    expect(useUserStore().userInfo?.id).toBe(1);
    expect(router.push).toHaveBeenCalledOnce();
  });

  it('rejects a tokenless response without entering authenticated state', async () => {
    vi.mocked(loginApi).mockResolvedValue({ userId: 1 });
    const store = useAuthStore();
    await expect(
      store.authLogin('username', { password: 'password' }),
    ).rejects.toThrow('Login response did not include an access token');
    expect(accessStore.accessToken).toBeNull();
    expect(router.push).not.toHaveBeenCalled();
    expect(store.loginLoading).toBe(false);
  });

  it('rejects a missing password and resets loading', async () => {
    const store = useAuthStore();
    await expect(
      store.authLogin('username', { username: 'admin' }),
    ).rejects.toThrow('password is required');
    expect(store.loginLoading).toBe(false);
    expect(loginApi).not.toHaveBeenCalled();
  });

  it('rebuilds permissions at the current location when the same user reauthenticates', async () => {
    accessStore.loginExpired = true;
    accessStore.isAccessChecked = true;
    useUserStore().setUserInfo({ id: 1 } as never);
    vi.mocked(loginApi).mockResolvedValue({
      accessToken: 'renewed',
      userId: 1,
    });
    await useAuthStore().authLogin('username', { password: 'password' });
    expect(accessStore.loginExpired).toBe(false);
    expect(accessStore.isAccessChecked).toBe(false);
    expect(router.replace).toHaveBeenCalledWith({
      path: router.currentRoute.value.fullPath,
      force: true,
    });
    expect(router.push).not.toHaveBeenCalled();
  });

  it('uses the home page when another user reauthenticates', async () => {
    accessStore.loginExpired = true;
    useUserStore().setUserInfo({ id: 2 } as never);
    vi.mocked(loginApi).mockResolvedValue({
      accessToken: 'renewed',
      userId: 1,
    });
    await useAuthStore().authLogin('username', { password: 'password' });
    expect(router.push).toHaveBeenCalledOnce();
    expect(router.replace).not.toHaveBeenCalled();
  });

  it('treats a missing or failed refresh session as unauthenticated', async () => {
    const store = useAuthStore();
    vi.mocked(refreshTokenApi).mockResolvedValue({
      data: { data: {} },
    } as never);
    await expect(store.restoreSession()).resolves.toBe(false);
    store.$reset();
    vi.mocked(refreshTokenApi).mockRejectedValue(new Error('expired'));
    await expect(store.restoreSession()).resolves.toBe(false);
    await expect(store.restoreSession()).resolves.toBe(false);
  });

  it('restores an access token from the HttpOnly refresh session once', async () => {
    vi.mocked(refreshTokenApi).mockResolvedValue({
      data: { data: { accessToken: 'restored' } },
    } as never);
    const store = useAuthStore();
    await expect(store.restoreSession()).resolves.toBe(true);
    await expect(store.restoreSession()).resolves.toBe(true);
    expect(refreshTokenApi).toHaveBeenCalledOnce();
    expect(accessStore.accessToken).toBe('restored');
  });

  it('clears the partial login when permission loading fails', async () => {
    vi.mocked(loginApi).mockResolvedValue({
      accessToken: 'partial',
      userId: 1,
    });
    vi.mocked(getAuthPermissionInfoApi).mockRejectedValue(
      new Error('unavailable'),
    );
    const store = useAuthStore();
    await expect(
      store.authLogin('username', { password: 'password' }),
    ).rejects.toThrow('unavailable');
    expect(accessStore.accessToken).toBeNull();
    expect(useUserStore().userInfo).toBeNull();
    expect(accessStore.isAccessChecked).toBe(false);
    expect(router.push).not.toHaveBeenCalled();
    expect(store.loginLoading).toBe(false);
  });

  it('keeps the expired-session dialog available when renewed permissions fail', async () => {
    accessStore.setLoginExpired(true);
    vi.mocked(loginApi).mockResolvedValue({
      accessToken: 'partial',
      userId: 1,
    });
    const response = deferred<never>();
    vi.mocked(getAuthPermissionInfoApi).mockReturnValue(response.promise);
    const store = useAuthStore();
    const login = store.authLogin('username', { password: 'password' });
    await vi.waitFor(() =>
      expect(getAuthPermissionInfoApi).toHaveBeenCalledOnce(),
    );
    expect(accessStore.loginExpired).toBe(true);
    response.reject(new Error('unavailable'));
    await expect(login).rejects.toThrow('unavailable');
    expect(accessStore.accessToken).toBeNull();
    expect(accessStore.loginExpired).toBe(true);
  });

  it('shares concurrent restore requests and waits for their result', async () => {
    const response = deferred<never>();
    vi.mocked(refreshTokenApi).mockReturnValue(response.promise);
    const store = useAuthStore();
    const first = store.restoreSession();
    const second = store.restoreSession();
    response.resolve({ data: { data: { accessToken: 'restored' } } } as never);
    await expect(Promise.all([first, second])).resolves.toEqual([true, true]);
    expect(refreshTokenApi).toHaveBeenCalledOnce();
  });

  it('logout 写入的会话标记让页面刷新后的 restoreSession 直接放弃恢复', async () => {
    const store = useAuthStore();
    await store.logout();
    expect(sessionStorage.getItem('basic-framework:logged-out')).toBe('1');

    // 模拟页面刷新：内存态重建，sessionStorage 保留
    await initStores(createApp({}), { namespace: 'auth-test-reloaded' });
    const freshStore = useAuthStore();

    await expect(freshStore.restoreSession()).resolves.toBe(false);
    expect(refreshTokenApi).not.toHaveBeenCalled();
  });

  it('登录成功清除登出标记，允许后续会话恢复', async () => {
    sessionStorage.setItem('basic-framework:logged-out', '1');
    vi.mocked(loginApi).mockResolvedValue({
      accessToken: 'current',
      userId: 1,
    });
    const store = useAuthStore();
    await store.authLogin('username', {
      password: 'password',
      username: 'admin',
    });
    expect(sessionStorage.getItem('basic-framework:logged-out')).toBeNull();
  });

  it('keeps logout final when a restore response arrives late', async () => {
    const response = deferred<never>();
    vi.mocked(refreshTokenApi).mockReturnValue(response.promise);
    const store = useAuthStore();
    const restoration = store.restoreSession();
    await store.logout();
    response.resolve({ data: { data: { accessToken: 'obsolete' } } } as never);
    await expect(restoration).resolves.toBe(false);
    await expect(store.restoreSession()).resolves.toBe(false);
    expect(accessStore.accessToken).toBeNull();
    expect(refreshTokenApi).toHaveBeenCalledOnce();
  });

  it('clears local data before logout finishes and never restores after logout failure', async () => {
    accessStore.setAccessToken('existing');
    useUserStore().setUserInfo({ id: 1 } as never);
    const response = deferred<never>();
    vi.mocked(logoutApi).mockReturnValue(response.promise);
    const store = useAuthStore();
    const logout = store.logout();
    expect(accessStore.accessToken).toBeNull();
    expect(useUserStore().userInfo).toBeNull();
    response.reject(new Error('network unavailable'));
    await logout;
    await expect(store.restoreSession()).resolves.toBe(false);
    expect(refreshTokenApi).not.toHaveBeenCalled();
  });

  it('rejects a late login response after logout', async () => {
    const response = deferred<never>();
    vi.mocked(loginApi).mockReturnValue(response.promise);
    const store = useAuthStore();
    const login = store.authLogin('username', { password: 'password' });
    await store.logout();
    response.resolve({ accessToken: 'obsolete', userId: 1 } as never);
    await expect(login).rejects.toThrow(
      'Authentication changed while logging in',
    );
    expect(accessStore.accessToken).toBeNull();
    expect(getAuthPermissionInfoApi).not.toHaveBeenCalled();
  });

  it('does not apply obsolete permissions after logout', async () => {
    const response = deferred<never>();
    accessStore.setAccessToken('existing');
    vi.mocked(getAuthPermissionInfoApi).mockReturnValue(response.promise);
    const store = useAuthStore();
    const permissions = store.fetchUserInfo();
    await store.logout();
    response.resolve(permissionInfo as never);
    await expect(permissions).rejects.toThrow(
      'Authentication changed while loading permissions',
    );
    expect(useUserStore().userInfo).toBeNull();
    expect(accessStore.accessCodes).toEqual([]);
  });

  it('does not redirect a new login when an earlier logout response arrives', async () => {
    const response = deferred<never>();
    vi.mocked(logoutApi).mockReturnValue(response.promise);
    const store = useAuthStore();
    const logout = store.logout();
    vi.mocked(loginApi).mockResolvedValue({
      accessToken: 'new-session',
      userId: 1,
    });
    await store.authLogin('username', { password: 'password' });
    response.resolve(undefined as never);
    await logout;
    expect(accessStore.accessToken).toBe('new-session');
    expect(router.replace).not.toHaveBeenCalled();
  });

  it('runs a successful login callback after the new permissions are available', async () => {
    vi.mocked(loginApi).mockResolvedValue({
      accessToken: 'new-session',
      userId: 1,
    });
    const callback = vi.fn(() => {
      expect(useUserStore().userInfo?.id).toBe(1);
    });
    await useAuthStore().authLogin(
      'username',
      { password: 'password' },
      callback,
    );
    expect(callback).toHaveBeenCalledOnce();
    expect(router.push).not.toHaveBeenCalled();
  });
});

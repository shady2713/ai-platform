import { createPinia, setActivePinia } from 'pinia';
import { beforeEach, describe, expect, it } from 'vitest';

import { useAccessStore } from './access';

describe('useAccessStore', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
  });

  it('starts from a fail-closed access state', () => {
    const store = useAccessStore();
    expect(store.accessMenus).toEqual([]);
    expect(store.accessRoutes).toEqual([]);
    expect(store.accessCodes).toEqual([]);
    expect(store.accessToken).toBeNull();
    expect(store.isAccessChecked).toBe(false);
    expect(store.isLockScreen).toBe(false);
    expect(store.loginExpired).toBe(false);
  });

  it('updates access data through explicit actions', () => {
    const store = useAccessStore();
    const menus = [{ name: 'Dashboard', path: '/dashboard' }];
    const routes = [
      {
        component: { template: '<div />' },
        name: 'Dashboard',
        path: '/dashboard',
      },
    ];

    store.setAccessCodes(['system:user:list']);
    store.setAccessMenus(menus);
    store.setAccessRoutes(routes);
    store.setAccessToken('xyz789');
    store.setIsAccessChecked(true);
    store.setLoginExpired(true);

    expect(store.accessCodes).toEqual(['system:user:list']);
    expect(store.accessMenus).toEqual(menus);
    expect(store.accessRoutes).toEqual(routes);
    expect(store.accessToken).toBe('xyz789');
    expect(store.isAccessChecked).toBe(true);
    expect(store.loginExpired).toBe(true);
  });

  it('finds both root and nested menus and returns undefined for unknown paths', () => {
    const store = useAccessStore();
    const nested = { name: 'User', path: '/system/user' };
    store.setAccessMenus([
      { name: 'Dashboard', path: '/dashboard' },
      {
        children: [nested],
        name: 'System',
        path: '/system',
      },
    ]);

    expect(store.getMenuByPath('/dashboard')?.name).toBe('Dashboard');
    expect(store.getMenuByPath('/system/user')).toStrictEqual(nested);
    expect(store.getMenuByPath('/missing')).toBeUndefined();
  });

  it('locks and unlocks the screen without retaining the plaintext password', async () => {
    const store = useAccessStore();

    await store.lockScreen('local-secret');
    expect(store.isLockScreen).toBe(true);
    expect(store.lockScreenCredential).toEqual({
      digest: expect.stringMatching(/^[\da-f]{64}$/),
      iterations: 210_000,
      salt: expect.stringMatching(/^[\da-f]{32}$/),
    });
    expect(JSON.stringify(store.$state)).not.toContain('local-secret');
    await expect(store.verifyLockScreenPassword('local-secret')).resolves.toBe(
      true,
    );
    await expect(store.verifyLockScreenPassword('wrong-secret')).resolves.toBe(
      false,
    );

    store.unlockScreen();
    expect(store.isLockScreen).toBe(false);
    expect(store.lockScreenCredential).toBeUndefined();
    await expect(store.verifyLockScreenPassword('local-secret')).resolves.toBe(
      false,
    );
  });

  it('rejects blank and oversized lock-screen passwords before locking', async () => {
    const store = useAccessStore();

    await expect(store.lockScreen('')).rejects.toThrow('1 到 128');
    await expect(store.lockScreen('x'.repeat(129))).rejects.toThrow('1 到 128');
    expect(store.isLockScreen).toBe(false);
    expect(store.lockScreenCredential).toBeUndefined();
  });

  it('uses a fresh random salt each time for the same password', async () => {
    const store = useAccessStore();

    await store.lockScreen('local-secret');
    const firstCredential = { ...store.lockScreenCredential };
    store.unlockScreen();
    await store.lockScreen('local-secret');

    expect(store.lockScreenCredential?.salt).not.toBe(firstCredential.salt);
    expect(store.lockScreenCredential?.digest).not.toBe(firstCredential.digest);
  });

  it('fails closed when the derived credential is malformed or tampered', async () => {
    const store = useAccessStore();

    await store.lockScreen('local-secret');
    store.lockScreenCredential = {
      digest: '00',
      iterations: 210_000,
      salt: '00',
    };

    await expect(store.verifyLockScreenPassword('local-secret')).resolves.toBe(
      false,
    );
    expect(store.isLockScreen).toBe(true);
  });
});

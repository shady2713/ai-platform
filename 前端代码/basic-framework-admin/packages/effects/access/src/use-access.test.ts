import { beforeEach, describe, expect, it, vi } from 'vitest';

import { useAccess } from './use-access';

const state = vi.hoisted(() => ({
  accessCodes: ['system:user:query'],
  accessMode: 'frontend' as 'backend' | 'frontend',
  updatePreferences: vi.fn(),
  userRoles: ['admin'],
}));

vi.mock('@vben/preferences', () => ({
  preferences: {
    app: {
      get accessMode() {
        return state.accessMode;
      },
    },
  },
  updatePreferences: state.updatePreferences,
}));

vi.mock('@vben/stores', () => ({
  useAccessStore: () => ({
    get accessCodes() {
      return state.accessCodes;
    },
  }),
  useUserStore: () => ({
    get userRoles() {
      return state.userRoles;
    },
  }),
}));

describe('useAccess', () => {
  beforeEach(() => {
    state.accessCodes = ['system:user:query'];
    state.accessMode = 'frontend';
    state.userRoles = ['admin'];
    state.updatePreferences.mockReset();
  });

  it('checks role and permission-code intersections', () => {
    const access = useAccess();

    expect(access.hasAccessByRoles(['guest', 'admin'])).toBe(true);
    expect(access.hasAccessByRoles(['guest'])).toBe(false);
    expect(
      access.hasAccessByCodes(['system:user:query', 'system:user:update']),
    ).toBe(true);
    expect(access.hasAccessByCodes(['system:user:update'])).toBe(false);
  });

  it('exposes the current access mode reactively', () => {
    const access = useAccess();

    expect(access.accessMode.value).toBe('frontend');
    state.accessMode = 'backend';
    expect(useAccess().accessMode.value).toBe('backend');
  });

  it('toggles both frontend and backend modes through preferences', async () => {
    const access = useAccess();

    await access.toggleAccessMode();
    expect(state.updatePreferences).toHaveBeenLastCalledWith({
      app: { accessMode: 'backend' },
    });

    state.accessMode = 'backend';
    await access.toggleAccessMode();
    expect(state.updatePreferences).toHaveBeenLastCalledWith({
      app: { accessMode: 'frontend' },
    });
  });
});

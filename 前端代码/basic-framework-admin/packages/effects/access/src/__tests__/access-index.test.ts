import { createApp, defineComponent, h } from 'vue';

import { beforeEach, describe, expect, it, vi } from 'vitest';

// Side-effect import executes the public index barrel.
import { generateAccessible, registerAccessDirective, useAccess } from '..';

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

describe('access index', () => {
  it('exports the documented api surface', () => {
    expect(useAccess).toBeTypeOf('function');
    expect(registerAccessDirective).toBeTypeOf('function');
    expect(generateAccessible).toBeTypeOf('function');
  });

  it('hasAccessByCodes intersects store codes with required codes', () => {
    const { hasAccessByCodes } = useAccess();
    expect(hasAccessByCodes(['system:user:query'])).toBe(true);
    expect(hasAccessByCodes(['system:user:add'])).toBe(false);
    expect(hasAccessByCodes([])).toBe(false);
  });

  it('hasAccessByRoles intersects store roles with required roles', () => {
    const { hasAccessByRoles } = useAccess();
    expect(hasAccessByRoles(['admin'])).toBe(true);
    expect(hasAccessByRoles(['admin', 'user'])).toBe(true);
    expect(hasAccessByRoles(['guest'])).toBe(false);
  });

  it('toggleAccessMode flips the mode through updatePreferences', () => {
    state.accessMode = 'frontend';
    const { toggleAccessMode } = useAccess();
    toggleAccessMode();
    expect(state.updatePreferences).toHaveBeenCalledWith({
      app: { accessMode: 'backend' },
    });
  });

  it('registerAccessDirective installs the v-access directive', () => {
    const app = createApp(defineComponent(() => () => h('div')));
    registerAccessDirective(app);
    expect(app.directive('access')).toBeTruthy();
  });

  beforeEach(() => {
    state.updatePreferences.mockClear();
  });
});

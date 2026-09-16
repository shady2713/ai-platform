import type { VueWrapper } from '@vue/test-utils';

import { shallowMount } from '@vue/test-utils';
import { defineComponent, ref } from 'vue';

import { beforeEach, describe, expect, it, vi } from 'vitest';

import UserDropdown from './user-dropdown.vue';

const state = vi.hoisted(() => ({
  shortcutEnabled: false,
  shortcutCallbacks: [] as Array<() => void>,
  hoverEnable: vi.fn(),
  hoverDisable: vi.fn(),
  lockModalApi: { close: vi.fn(), open: vi.fn() },
  lockScreen: vi.fn(),
  logoutModalApi: { close: vi.fn(), open: vi.fn() },
}));

vi.mock('@vben/hooks', () => ({
  useHoverToggle: () => [
    ref(false),
    { disable: state.hoverDisable, enable: state.hoverEnable },
  ],
}));

vi.mock('@vben/icons', () => ({
  LockKeyhole: defineComponent({ name: 'LockKeyhole' }),
  LogOut: defineComponent({ name: 'LogOut' }),
}));

vi.mock('@vben/locales', () => ({
  $t: (key: string) => key,
}));

vi.mock('@vben/preferences', () => ({
  preferences: {
    shortcutKeys: {
      get enable() {
        return state.shortcutEnabled;
      },
    },
    widget: { lockScreen: true },
  },
  usePreferences: () => ({
    globalLockScreenShortcutKey: ref(true),
    globalLogoutShortcutKey: ref(true),
  }),
}));

vi.mock('@vben/stores', () => ({
  useAccessStore: () => ({ lockScreen: state.lockScreen }),
}));

vi.mock('@vben/utils', () => ({
  isWindowsOs: () => true,
}));

vi.mock('@vben-core/popup-ui', () => ({
  useVbenModal: (options: { connectedComponent?: unknown }) =>
    options.connectedComponent
      ? [defineComponent({ name: 'LockModal' }), state.lockModalApi]
      : [defineComponent({ name: 'LogoutModal' }), state.logoutModalApi],
}));

vi.mock('@vben-core/shadcn-ui', () => {
  const component = (name: string) => defineComponent({ name });
  return {
    Badge: component('Badge'),
    DropdownMenu: component('DropdownMenu'),
    DropdownMenuContent: component('DropdownMenuContent'),
    DropdownMenuItem: component('DropdownMenuItem'),
    DropdownMenuLabel: component('DropdownMenuLabel'),
    DropdownMenuSeparator: component('DropdownMenuSeparator'),
    DropdownMenuShortcut: component('DropdownMenuShortcut'),
    DropdownMenuTrigger: component('DropdownMenuTrigger'),
    VbenAvatar: component('VbenAvatar'),
    VbenIcon: component('VbenIcon'),
  };
});

vi.mock('@vueuse/core', () => ({
  useMagicKeys: () => ({ 'Alt+KeyQ': ref(false), 'Alt+KeyL': ref(false) }),
  whenever: (_source: unknown, callback: () => void) =>
    state.shortcutCallbacks.push(callback),
}));

vi.mock('../lock-screen', () => ({
  LockScreenModal: defineComponent({ name: 'LockScreenModal' }),
}));

interface UserDropdownComponent {
  handleLogout: () => void;
  handleOpenLock: () => void;
  handleSubmitLock: (password: string) => Promise<void>;
  handleSubmitLogout: () => void;
}

function component(wrapper: VueWrapper) {
  return wrapper.vm as unknown as UserDropdownComponent;
}

describe('user dropdown lock screen', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    state.shortcutEnabled = false;
    state.shortcutCallbacks = [];
  });

  it('closes the modal only after the lock credential is derived', async () => {
    let finishLock: (() => void) | undefined;
    state.lockScreen.mockImplementation(
      () =>
        new Promise<void>((resolve) => {
          finishLock = resolve;
        }),
    );
    const wrapper = shallowMount(UserDropdown);

    const submitting = component(wrapper).handleSubmitLock('local-secret');
    expect(state.lockScreen).toHaveBeenCalledWith('local-secret');
    expect(state.lockModalApi.close).not.toHaveBeenCalled();

    finishLock?.();
    await submitting;
    expect(state.lockModalApi.close).toHaveBeenCalledOnce();
  });

  it('keeps the modal open when credential derivation fails', async () => {
    state.lockScreen.mockRejectedValue(new Error('derivation failed'));
    const wrapper = shallowMount(UserDropdown);

    await expect(
      component(wrapper).handleSubmitLock('local-secret'),
    ).rejects.toThrow('derivation failed');
    expect(state.lockModalApi.close).not.toHaveBeenCalled();
  });

  it('opens the lock and logout confirmation modals from explicit actions', () => {
    const wrapper = shallowMount(UserDropdown);

    component(wrapper).handleOpenLock();
    component(wrapper).handleLogout();

    expect(state.lockModalApi.open).toHaveBeenCalledOnce();
    expect(state.logoutModalApi.open).toHaveBeenCalledOnce();
  });

  it('enables hover only for hover-capable triggers', async () => {
    const wrapper = shallowMount(UserDropdown, { props: { trigger: 'hover' } });
    expect(state.hoverEnable).toHaveBeenCalledOnce();
    await wrapper.setProps({ trigger: 'click' });
    expect(state.hoverDisable).toHaveBeenCalledOnce();
    await wrapper.setProps({ trigger: 'both' });
    expect(state.hoverEnable).toHaveBeenCalledTimes(2);
  });

  it('keyboard shortcuts open their confirmation dialogs', () => {
    state.shortcutEnabled = true;
    shallowMount(UserDropdown);
    expect(state.shortcutCallbacks).toHaveLength(2);
    state.shortcutCallbacks[0]?.();
    state.shortcutCallbacks[1]?.();
    expect(state.logoutModalApi.open).toHaveBeenCalledOnce();
    expect(state.lockModalApi.open).toHaveBeenCalledOnce();
  });

  it('emits logout and closes its confirmation modal', () => {
    const wrapper = shallowMount(UserDropdown);

    component(wrapper).handleSubmitLogout();

    expect(wrapper.emitted('logout')).toHaveLength(1);
    expect(state.logoutModalApi.close).toHaveBeenCalledOnce();
  });
});

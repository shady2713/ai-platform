import type { Ref } from 'vue';

import { effectScope, nextTick } from 'vue';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { useContentSpinner } from './use-content-spinner';

interface RouteLike {
  meta: {
    iframeSrc?: string;
    loaded?: boolean;
  };
}

type AfterHook = (to: RouteLike) => void;
type BeforeGuard = (to: RouteLike) => boolean;

const state = vi.hoisted(() => ({
  afterHook: undefined as AfterHook | undefined,
  beforeGuard: undefined as BeforeGuard | undefined,
  preferences: undefined as
    | undefined
    | {
        transition: { loading: boolean };
      },
  removeAfterHook: vi.fn(),
  removeBeforeGuard: vi.fn(),
}));

vi.mock('vue-router', () => ({
  useRouter: () => ({
    afterEach: (hook: AfterHook) => {
      state.afterHook = hook;
      return state.removeAfterHook;
    },
    beforeEach: (guard: BeforeGuard) => {
      state.beforeGuard = guard;
      return state.removeBeforeGuard;
    },
  }),
}));

vi.mock('@vben/preferences', async () => {
  const { reactive } = await import('vue');
  const preferences = reactive({ transition: { loading: true } });
  state.preferences = preferences;
  return { preferences };
});

function createSpinner() {
  const scope = effectScope();
  let spinning: Ref<boolean> | undefined;
  scope.run(() => {
    spinning = useContentSpinner().spinning;
  });
  if (!spinning) {
    throw new Error('Spinner scope did not initialize');
  }
  return { scope, spinning };
}

function getPreferences() {
  if (!state.preferences) {
    throw new Error('Preference mock did not initialize');
  }
  return state.preferences;
}

describe('use content spinner', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    vi.spyOn(performance, 'now').mockReturnValue(0);
    getPreferences().transition.loading = true;
    state.removeAfterHook.mockClear();
    state.removeBeforeGuard.mockClear();
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.restoreAllMocks();
  });

  it('keeps the spinner visible for the minimum display time', () => {
    const { scope, spinning } = createSpinner();
    const route = { meta: {} };

    expect(state.beforeGuard?.(route)).toBe(true);
    expect(spinning.value).toBe(true);
    vi.spyOn(performance, 'now').mockReturnValue(100);
    state.afterHook?.(route);

    vi.advanceTimersByTime(399);
    expect(spinning.value).toBe(true);
    vi.advanceTimersByTime(1);
    expect(spinning.value).toBe(false);
    scope.stop();
  });

  it('does not track cached, iframe, or disabled navigations', async () => {
    const { scope, spinning } = createSpinner();

    expect(state.beforeGuard?.({ meta: { loaded: true } })).toBe(true);
    expect(state.beforeGuard?.({ meta: { iframeSrc: '/embedded' } })).toBe(
      true,
    );
    getPreferences().transition.loading = false;
    await nextTick();
    expect(state.beforeGuard?.({ meta: {} })).toBe(true);
    expect(spinning.value).toBe(false);
    scope.stop();
  });

  it('waits for every overlapping navigation to finish', () => {
    const { scope, spinning } = createSpinner();
    const firstRoute = { meta: {} };
    const secondRoute = { meta: {} };

    state.beforeGuard?.(firstRoute);
    state.beforeGuard?.(secondRoute);
    vi.spyOn(performance, 'now').mockReturnValue(600);
    state.afterHook?.(firstRoute);
    expect(spinning.value).toBe(true);

    state.afterHook?.(secondRoute);
    expect(spinning.value).toBe(false);
    scope.stop();
  });

  it('cancels a stale hide timer when a new navigation starts', () => {
    const { scope, spinning } = createSpinner();
    const firstRoute = { meta: {} };
    const secondRoute = { meta: {} };

    state.beforeGuard?.(firstRoute);
    vi.spyOn(performance, 'now').mockReturnValue(100);
    state.afterHook?.(firstRoute);
    vi.advanceTimersByTime(100);
    state.beforeGuard?.(secondRoute);

    vi.advanceTimersByTime(300);
    expect(spinning.value).toBe(true);
    vi.spyOn(performance, 'now').mockReturnValue(600);
    state.afterHook?.(secondRoute);
    expect(spinning.value).toBe(false);
    scope.stop();
  });

  it('hides immediately when loading is disabled', async () => {
    const { scope, spinning } = createSpinner();
    state.beforeGuard?.({ meta: {} });
    expect(spinning.value).toBe(true);

    getPreferences().transition.loading = false;
    await nextTick();

    expect(spinning.value).toBe(false);
    expect(vi.getTimerCount()).toBe(0);
    scope.stop();
  });

  it('removes router hooks and pending timers with its scope', () => {
    const { scope, spinning } = createSpinner();
    const route = { meta: {} };
    state.beforeGuard?.(route);
    vi.spyOn(performance, 'now').mockReturnValue(100);
    state.afterHook?.(route);
    expect(vi.getTimerCount()).toBe(1);

    scope.stop();

    expect(state.removeBeforeGuard).toHaveBeenCalledOnce();
    expect(state.removeAfterHook).toHaveBeenCalledOnce();
    expect(vi.getTimerCount()).toBe(0);
    expect(spinning.value).toBe(false);
  });
});

import { createPinia, setActivePinia } from 'pinia';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { setTimezoneHandler, useTimezoneStore } from './timezone';

const timezoneMocks = vi.hoisted(() => ({
  current: 'Asia/Shanghai',
  setCurrentTimezone: vi.fn(),
}));

vi.mock('@vben-core/shared/utils', async (importOriginal) => {
  const original =
    await importOriginal<typeof import('@vben-core/shared/utils')>();
  return {
    ...original,
    getCurrentTimezone: () => timezoneMocks.current,
    setCurrentTimezone: timezoneMocks.setCurrentTimezone,
  };
});

describe('useTimezoneStore', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    setTimezoneHandler({});
    timezoneMocks.current = 'Asia/Shanghai';
    timezoneMocks.setCurrentTimezone.mockReset();
  });

  it('初始化并返回默认时区选项', async () => {
    const store = useTimezoneStore();

    await vi.waitFor(() => {
      expect(timezoneMocks.setCurrentTimezone).toHaveBeenCalledWith(
        'Asia/Shanghai',
      );
    });
    const options = await store.getTimezoneOptions();

    expect(options.length).toBeGreaterThan(0);
    expect(options[0]).toEqual(
      expect.objectContaining({
        label: expect.any(String),
        value: expect.any(String),
      }),
    );
  });

  it('自定义处理器负责读取和持久化时区', async () => {
    const setTimezone = vi.fn(async () => undefined);
    setTimezoneHandler({
      getTimezone: async () => 'Europe/London',
      getTimezoneOptions: async () => [
        { label: 'London', value: 'Europe/London' },
      ],
      setTimezone,
    });
    const store = useTimezoneStore();

    await vi.waitFor(() => expect(store.timezone).toBe('Europe/London'));
    await store.setTimezone('America/New_York');

    expect(setTimezone).toHaveBeenCalledWith('America/New_York');
    expect(store.timezone).toBe('America/New_York');
    expect(timezoneMocks.setCurrentTimezone).toHaveBeenLastCalledWith(
      'America/New_York',
    );
    await expect(store.getTimezoneOptions()).resolves.toEqual([
      { label: 'London', value: 'Europe/London' },
    ]);
  });

  it('空的远端时区保留本地值，重置时重新读取当前时区', async () => {
    setTimezoneHandler({
      getTimezone: async () => null,
      getTimezoneOptions: undefined,
    });
    const store = useTimezoneStore();
    await vi.waitFor(() =>
      expect(timezoneMocks.setCurrentTimezone).toHaveBeenCalled(),
    );

    timezoneMocks.current = 'UTC';
    store.$reset();

    expect(store.timezone).toBe('UTC');
    await expect(store.getTimezoneOptions()).resolves.toEqual([]);
  });

  it('初始化失败被捕获并记录稳定错误', async () => {
    const failure = new Error('timezone unavailable');
    const error = vi
      .spyOn(console, 'error')
      .mockImplementation(() => undefined);
    setTimezoneHandler({
      getTimezone: async () => {
        throw failure;
      },
    });

    useTimezoneStore();

    await vi.waitFor(() => {
      expect(error).toHaveBeenCalledWith(
        'Failed to initialize timezone during store setup:',
        failure,
      );
    });
    error.mockRestore();
  });
});

import { beforeEach, describe, expect, it, vi } from 'vitest';

const mocks = vi.hoisted(() => ({
  configure: vi.fn(),
  done: vi.fn(),
  start: vi.fn(),
}));

vi.mock('nprogress', () => mocks);

describe('nprogress', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.resetModules();
  });

  it('loads and configures one shared progress instance', async () => {
    const { startProgress, stopProgress } = await import('../nprogress');

    await startProgress();
    await stopProgress();
    await startProgress();

    expect(mocks.configure).toHaveBeenCalledOnce();
    expect(mocks.configure).toHaveBeenCalledWith({
      showSpinner: true,
      speed: 300,
    });
    expect(mocks.start).toHaveBeenCalledTimes(2);
    expect(mocks.done).toHaveBeenCalledOnce();
  });
});

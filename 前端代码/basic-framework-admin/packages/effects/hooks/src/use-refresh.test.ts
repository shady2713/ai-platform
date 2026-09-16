import { beforeEach, describe, expect, it, vi } from 'vitest';

import { useRefresh } from './use-refresh';

const testState = vi.hoisted(() => ({
  router: {},
  tabbarStore: {
    refresh: vi.fn(),
  },
}));

vi.mock('vue-router', () => ({
  useRouter: () => testState.router,
}));

vi.mock('@vben/stores', () => ({
  useTabbarStore: () => testState.tabbarStore,
}));

describe('useRefresh', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('将刷新请求委托给标签页状态并传递当前路由器', async () => {
    const { refresh } = useRefresh();

    await expect(refresh()).resolves.toBeUndefined();

    expect(testState.tabbarStore.refresh).toHaveBeenCalledWith(
      testState.router,
    );
  });

  it('保留标签页刷新失败的错误语义', async () => {
    const failure = new Error('refresh failed');
    testState.tabbarStore.refresh.mockRejectedValueOnce(failure);
    const { refresh } = useRefresh();

    await expect(refresh()).rejects.toThrow(failure);
  });
});

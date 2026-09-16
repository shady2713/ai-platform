import { beforeEach, describe, expect, it, vi } from 'vitest';

import { useContentMaximize } from './use-content-maximize';

const testState = vi.hoisted(() => ({
  contentIsMaximize: { value: false },
  updatePreferences: vi.fn(),
}));

vi.mock('@vben/preferences', () => ({
  updatePreferences: testState.updatePreferences,
  usePreferences: () => ({
    contentIsMaximize: testState.contentIsMaximize,
  }),
}));

describe('useContentMaximize', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    testState.contentIsMaximize.value = false;
  });

  it('切换最大化时同步隐藏或恢复页头和侧边栏', () => {
    const { contentIsMaximize, toggleMaximize } = useContentMaximize();

    expect(contentIsMaximize).toBe(testState.contentIsMaximize);
    toggleMaximize();
    expect(testState.updatePreferences).toHaveBeenCalledWith({
      header: { hidden: true },
      sidebar: { hidden: true },
    });

    testState.contentIsMaximize.value = true;
    toggleMaximize();
    expect(testState.updatePreferences).toHaveBeenLastCalledWith({
      header: { hidden: false },
      sidebar: { hidden: false },
    });
  });

  it('切换最大化和标签栏时使用当前状态恢复标签栏', () => {
    const { toggleMaximizeAndTabbarHidden } = useContentMaximize();

    toggleMaximizeAndTabbarHidden();
    expect(testState.updatePreferences).toHaveBeenCalledWith({
      header: { hidden: true },
      sidebar: { hidden: true },
      tabbar: { enable: false },
    });

    testState.contentIsMaximize.value = true;
    toggleMaximizeAndTabbarHidden();
    expect(testState.updatePreferences).toHaveBeenLastCalledWith({
      header: { hidden: false },
      sidebar: { hidden: false },
      tabbar: { enable: true },
    });
  });
});

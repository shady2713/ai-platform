import { describe, expect, it, vi } from 'vitest';

import * as authentication from './authentication';
import * as basic from './basic';
import * as layouts from './index';

// @iconify/vue 在模块求值时捕获全局 fetch 并异步队列远程拉取图标；
// vi.hoisted 先于本文件依赖图求值执行，确保捕获到本地桩，封死外部 HTTP 边界。
vi.hoisted(() => {
  globalThis.fetch = (() =>
    Promise.resolve({
      json: () => Promise.resolve({}),
      status: 200,
    })) as unknown as typeof fetch;
});

describe('layouts public barrels', () => {
  it('authentication barrel exposes the auth page layout', () => {
    expect(authentication.AuthPageLayout).toBeDefined();
    expect(layouts.AuthPageLayout).toBe(authentication.AuthPageLayout);
  });

  it('basic barrel exposes the basic layout', () => {
    expect(basic.BasicLayout).toBeDefined();
    expect(layouts.BasicLayout).toBe(basic.BasicLayout);
  });

  it('root barrel exposes the iframe views', () => {
    expect(layouts.IFrameView).toBeDefined();
    expect(layouts.IFrameRouterView).toBeDefined();
  });

  it('root barrel exposes the layout widgets', () => {
    expect(layouts.Breadcrumb).toBeDefined();
    expect(layouts.CheckUpdates).toBeDefined();
    expect(layouts.GlobalSearch).toBeDefined();
    expect(layouts.LanguageToggle).toBeDefined();
    expect(layouts.LockScreen).toBeDefined();
    expect(layouts.Notification).toBeDefined();
    expect(layouts.Preferences).toBeDefined();
    expect(layouts.ThemeToggle).toBeDefined();
    expect(layouts.UserDropdown).toBeDefined();
  });
});

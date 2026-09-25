import { describe, expect, it, vi } from 'vitest';

import {
  applyTheme,
  createVueHostState,
  switchUser,
  THEME_PRESETS,
  VUE_HOST_ROUTES,
} from './host';

describe('vue 宿主示例逻辑（C10）', () => {
  it('只登记白名单路由，未登记路由与未知参数被拒绝', () => {
    const onNavigate = vi.fn();
    const state = createVueHostState({ onNavigate });

    expect(
      state.events.navigate({
        params: { id: 'order-1' },
        route: 'order.detail',
      }).ok,
    ).toBe(true);
    expect(onNavigate).toHaveBeenCalledTimes(1);

    expect(state.events.navigate({ route: 'evil.route' }).ok).toBe(false);
    expect(
      state.events.navigate({
        params: { url: 'https://evil.example.com' },
        route: 'order.detail',
      }).ok,
    ).toBe(false);
    expect(onNavigate).toHaveBeenCalledTimes(1);
    expect(Object.keys(VUE_HOST_ROUTES)).toStrictEqual([
      'order.detail',
      'report.list',
    ]);
  });

  it('上下文只作用于下一次运行（受理后取快照不受后续更新影响）', () => {
    const state = createVueHostState({});

    state.contexts.update({ objectId: 'order-1', page: 'crm/order' });
    const snapshot = state.contexts.snapshot();
    state.contexts.update({ objectId: 'order-2', page: 'crm/order' });

    expect(snapshot?.objectId).toBe('order-1');
    expect(state.contexts.current()?.objectId).toBe('order-2');
    // 身份/范围字段进不来
    expect(() => state.contexts.update({ subjectId: 'alice' })).toThrow();
  });

  it('切主题只改外壳令牌，切用户清空上下文', () => {
    const updateTheme = vi.fn();
    const destroy = vi.fn();
    const mount = { destroy, updateTheme } as never as Parameters<
      typeof applyTheme
    >[0];

    applyTheme(mount, 'dark');
    expect(updateTheme).toHaveBeenCalledWith(
      expect.objectContaining({
        primaryColor: THEME_PRESETS.dark.primaryColor,
      }),
    );

    const contexts = { clear: vi.fn() };
    switchUser(mount, contexts);
    expect(destroy).toHaveBeenCalledTimes(1);
    expect(contexts.clear).toHaveBeenCalledTimes(1);
  });
});

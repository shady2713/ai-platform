import type { HostRouteRegistry } from '../host-events';

import { describe, expect, it, vi } from 'vitest';

import { createBusinessContextStore } from '../../context/business-context-store';
import {
  createHostEventHandlers,
  validateHostNavigation,
} from '../host-events';

describe('业务上下文仓库（C08）', () => {
  it('更新只作用于下一次运行，运行中的快照不受影响', () => {
    const store = createBusinessContextStore();

    store.update({ objectId: 'order-1', page: 'crm/order' });
    const runningSnapshot = store.snapshot();
    expect(runningSnapshot).toStrictEqual({
      objectId: 'order-1',
      page: 'crm/order',
    });

    // 运行期间宿主切了对象：已取快照不变，下一次运行才用新值
    store.update({ objectId: 'order-2', page: 'crm/order' });
    expect(runningSnapshot?.objectId).toBe('order-1');
    expect(store.current()?.objectId).toBe('order-2');
    expect(store.snapshot()?.objectId).toBe('order-2');

    // 快照是副本：改动它不会污染仓库
    const copy = store.snapshot();
    if (copy !== null) {
      copy.objectId = 'tampered';
    }
    expect(store.current()?.objectId).toBe('order-2');
  });

  it('非法上下文被拒绝且保留原值（不出现半更新）', () => {
    const store = createBusinessContextStore({ objectId: 'order-1' });

    expect(() => store.update({ appCode: 'crm-portal' })).toThrow();
    expect(() => store.update({ page: '<script>alert(1)</script>' })).toThrow();
    // 形状非法即拒；"是不是真实存在的时区"由服务端用 tzdb 判定（前端只保证形状）
    expect(() => store.update({ timezone: 'Mars Olympus!' })).toThrow();
    expect(store.current()?.objectId).toBe('order-1');

    store.clear();
    expect(store.current()).toBeNull();
    expect(store.snapshot()).toBeNull();
  });

  it('上下文里没有身份与范围字段（仅登记键可通过）', () => {
    const store = createBusinessContextStore();
    for (const raw of [
      { scope: ['org:10'] },
      { subjectId: 'alice' },
      { externalUserId: 'alice' },
    ]) {
      expect(() => store.update(raw)).toThrow();
    }
  });
});

describe('宿主事件与导航校验（C08）', () => {
  const routes: HostRouteRegistry = {
    'order.detail': { params: { id: 'string', readonly: 'boolean' } },
    'report.list': { params: { page: 'number' } },
  };

  it('只接受登记路由与声明过的参数类型', () => {
    expect(
      validateHostNavigation(
        { params: { id: 'order-1' }, route: 'order.detail' },
        routes,
      ),
    ).toStrictEqual({
      ok: true,
      params: { id: 'order-1' },
      route: 'order.detail',
    });
    expect(
      validateHostNavigation(
        { params: { page: 2 }, route: 'report.list' },
        routes,
      ).ok,
    ).toBe(true);
  });

  it('未登记路由、任意 URL、脚本与未知参数一律拒绝', () => {
    const cases: {
      params?: Record<string, unknown>;
      reason: string;
      route: unknown;
    }[] = [
      { reason: 'ROUTE_NOT_REGISTERED', route: 'evil.route' },
      { reason: 'ROUTE_INVALID', route: 'https://evil.example.com' },
      { reason: 'ROUTE_INVALID', route: 'javascript:alert(1)' },
      {
        reason: 'PARAM_NOT_REGISTERED:url',
        params: { url: 'https://evil.example.com' },
        route: 'order.detail',
      },
      {
        reason: 'PARAM_TYPE_MISMATCH:id',
        params: { id: { nested: true } },
        route: 'order.detail',
      },
      {
        reason: 'PARAM_TYPE_MISMATCH:readonly',
        params: { readonly: 'yes' },
        route: 'order.detail',
      },
    ];
    for (const { reason, ...request } of cases) {
      const result = validateHostNavigation(request, routes);
      expect(result).toStrictEqual({ ok: false, reason });
    }
  });

  it('处理器只在校验通过时调用宿主导航，报表事件按形状转发', () => {
    const onNavigate = vi.fn();
    const onReportCreated = vi.fn();
    const handlers = createHostEventHandlers({
      onNavigate,
      onReportCreated,
      routes,
    });

    expect(
      handlers.navigate({ route: 'order.detail', params: { id: 'order-1' } })
        .ok,
    ).toBe(true);
    expect(onNavigate).toHaveBeenCalledTimes(1);

    expect(handlers.navigate({ route: 'evil.route' }).ok).toBe(false);
    expect(onNavigate).toHaveBeenCalledTimes(1);

    const forwarded = handlers.reportCreated({
      instanceId: 'inst-1',
      protocolVersion: '1.0',
      reportId: 'rpt_sales01',
      title: '销售报表',
      type: 'REPORT_CREATED',
      version: 2,
    });
    expect(forwarded).toStrictEqual({
      reportId: 'rpt_sales01',
      title: '销售报表',
      version: 2,
    });
    expect(onReportCreated).toHaveBeenCalledTimes(1);
  });
});

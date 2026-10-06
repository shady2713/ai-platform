import type { AiCrossSourceApi } from '#/api/ai/cross-source';

import { describe, expect, it } from 'vitest';

import {
  AI_CROSS_SOURCE_PERMISSIONS,
  canFetchAmounts,
  CROSS_SOURCE_CALLER_ROLE_OPTIONS,
  CROSS_SOURCE_REQUIRED_FIELDS,
  CROSS_SOURCE_SUBJECT_TYPE_OPTIONS,
  formatAmount,
  formatConsistencyAsOf,
  formatSkew,
  integrityReasonText,
  integrityStateCode,
  integrityStateText,
  integrityTone,
  shouldRenderAmounts,
  sourceRows,
  toMergeQuery,
  validateQuery,
  withheldNotice,
} from './data';

/** 放行口径（排他联合里 COMPLETE 变体没有 reason 可填） */
const complete: AiCrossSourceApi.Integrity = { state: 'COMPLETE' };
/** 部分出具：能看合计，看不到分来源明细 */
const partial: AiCrossSourceApi.Integrity = {
  reason: '部分来源不在你的授权范围内，仅出合计。',
  state: 'PARTIAL',
};
/** 不可出具：一个数字都不该出现在页面上 */
const withheld: AiCrossSourceApi.Integrity = {
  reason: '部分来源不在你的授权范围内，该结果未出具。',
  state: 'WITHHELD',
};

/** 后端漏发口径时的响应形状：跨源标记在，口径不在 */
const driftedResult: AiCrossSourceApi.MergeResult = {
  complete: false,
  consistencyAsOf: null,
  crossSource: true,
  currency: null,
  executionKey: 'xs-merge-aaa',
  maxSkewMillis: null,
  metricCode: null,
  sourceCount: null,
  sources: [],
  totalAmount: null,
};

/** 构造表单：默认一组合法值，用例只覆盖自己关心的那一个字段 */
function queryForm(
  overrides: Partial<Parameters<typeof validateQuery>[0]> = {},
): Parameters<typeof validateQuery>[0] {
  return {
    applicationId: 42,
    callerRoles: ['ANALYST'],
    executionKey: 'xs-merge-aaa',
    externalUserId: 'user-1',
    previouslySeenRoles: [],
    subjectType: 'USER',
    ...overrides,
  };
}

describe('跨源合并结果 · 展示口径', () => {
  it('权限码与后端两个 @PreAuthorize 逐字一致', () => {
    expect(AI_CROSS_SOURCE_PERMISSIONS).toEqual({
      integrity: 'ai:cross-source:integrity',
      query: 'ai:cross-source:query',
    });
  });

  it('角色与主体类型只给后端词表里的值（拼错的角色名会被服务端静默丢弃）', () => {
    expect(CROSS_SOURCE_CALLER_ROLE_OPTIONS.map((item) => item.value)).toEqual([
      'ANALYST',
      'DATA_STEWARD',
      'AGGREGATE_READER',
    ]);
    expect(CROSS_SOURCE_SUBJECT_TYPE_OPTIONS.map((item) => item.value)).toEqual(
      ['USER', 'APP'],
    );
  });

  it('必填字段就是后端 required=true 的那五个 @RequestParam', () => {
    expect([...CROSS_SOURCE_REQUIRED_FIELDS]).toEqual([
      'executionKey',
      'applicationId',
      'subjectType',
      'externalUserId',
      'callerRoles',
    ]);
  });

  it('渲染闸门：只有显式放行才为真，缺失与未知字面量一律 fail-closed', () => {
    expect(shouldRenderAmounts(complete)).toBe(true);
    expect(shouldRenderAmounts(partial)).toBe(true);
    expect(shouldRenderAmounts(withheld)).toBe(false);
    // 口径缺失绝不能按"看起来完整"处理
    expect(shouldRenderAmounts(undefined)).toBe(false);
    expect(shouldRenderAmounts(null)).toBe(false);
    // 未来新增的状态名在本页一律按不出具处理，而不是落到 else 去放行
    expect(
      shouldRenderAmounts({ reason: 'x', state: 'SOMETHING_NEW' } as never),
    ).toBe(false);
  });

  it('取数闸门只认 COMPLETE（PARTIAL 也不放行）', () => {
    expect(canFetchAmounts(complete)).toBe(true);
    expect(canFetchAmounts(partial)).toBe(false);
    expect(canFetchAmounts(withheld)).toBe(false);
    expect(canFetchAmounts(undefined)).toBe(false);
  });

  it('口径缺失时状态字面量是 MISSING，标签与配色都归到"未出具"', () => {
    expect(integrityStateCode(undefined)).toBe('MISSING');
    expect(integrityStateText(undefined)).toContain('未出具');
    expect(integrityTone(undefined)).toBe('danger');
    expect(integrityTone(withheld)).toBe('danger');
    expect(integrityTone(partial)).toBe('warning');
    expect(integrityTone(complete)).toBe('success');
  });

  it('放行不携带理由；未出具原样透传服务端理由', () => {
    expect(integrityReasonText(complete)).toBe('');
    expect(integrityReasonText(withheld)).toBe(
      '部分来源不在你的授权范围内，该结果未出具。',
    );
    expect(integrityReasonText(undefined)).toContain('未出具');
    expect(integrityStateText(withheld)).toBe('未出具（整份结果不可看）');
  });

  it('不可出具备注整段不含任何数字（不能给出可当规模推测的量）', () => {
    expect(withheldNotice(withheld)).not.toMatch(/\d/);
    expect(withheldNotice(withheld)).toContain('不渲染任何数字');
    // 口径缺失那条路径同样不得出现数字
    expect(withheldNotice(undefined)).not.toMatch(/\d/);
  });

  it('null 一律显示「未出具」，真实 0 仍显示 0', () => {
    expect(formatAmount(null)).toBe('未出具');
    expect(formatAmount(undefined)).toBe('未出具');
    // 真实的零是事实，不能被"未出具"吃掉；反过来 null 也不能被 0 顶替
    expect(formatAmount(0)).toBe('0');
    expect(formatAmount(9812.34)).toBe('9812.34');
    expect(formatSkew(null)).toBe('未出具');
    expect(formatSkew(45_000)).toBe('45000 毫秒');
    expect(formatConsistencyAsOf(null)).toBe('未出具');
    expect(formatConsistencyAsOf('2026-09-30T10:00:00')).toBe(
      '2026-09-30 10:00:00',
    );
  });

  it('分来源明细在未出具时为空（即使响应里混进了明细也不渲染）', () => {
    // 构造一次"契约被违反"的响应：口径未出具却带回了数字
    const leaky: AiCrossSourceApi.MergeResult = {
      integrity: withheld,
      sourceCount: 7,
      sources: [
        { amount: 4567.89, role: 'CRM' },
        { amount: 6543.21, role: 'ERP' },
      ],
      totalAmount: 98_113.1,
    };
    expect(sourceRows(leaky)).toEqual([]);
    expect(sourceRows({ ...leaky, integrity: complete })).toEqual([
      { amount: 4567.89, role: 'CRM' },
      { amount: 6543.21, role: 'ERP' },
    ]);
    // 口径缺失（后端漏发）同样按不出具处理
    expect(sourceRows(driftedResult)).toEqual([]);
    expect(sourceRows(undefined)).toEqual([]);
  });

  it('必填校验：缺角色最要紧——空角色会被服务端 fail-closed 拒绝', () => {
    expect(validateQuery(queryForm())).toBe('');
    expect(validateQuery(queryForm({ callerRoles: [] }))).toContain(
      '至少选择一个调用方角色',
    );
    expect(validateQuery(queryForm({ executionKey: '   ' }))).toBe(
      '请输入跨源执行幂等键',
    );
    expect(validateQuery(queryForm({ applicationId: 0 }))).toContain(
      '应用编号',
    );
    expect(validateQuery(queryForm({ applicationId: undefined }))).toContain(
      '应用编号',
    );
    expect(validateQuery(queryForm({ subjectType: '' }))).toBe(
      '请选择主体类型',
    );
    expect(validateQuery(queryForm({ externalUserId: '' }))).toBe(
      '请输入可信外部用户标识',
    );
  });

  /**
   * 收窄函数是"类型不变量"的可执行版本：不通过就返回 undefined，
   * 通过才产出可直接发请求的条件。页面先前用 `{ ...form }` 直接发，
   * 靠 `as` 强转糊掉 applicationId 可能是 undefined 的缺口——
   * 那样"这里必须已校验"就从代码里消失了。
   */
  describe('toMergeQuery', () => {
    it('校验通过时产出可直接发请求的查询条件，并去掉首尾空白', () => {
      const query = toMergeQuery(
        queryForm({
          applicationId: 42,
          callerRoles: ['ANALYST'],
          executionKey: '  xs-merge-aaa  ',
          externalUserId: '  user-1  ',
          previouslySeenRoles: ['PAYMENT'],
          subjectType: 'APP',
        }),
      );

      expect(query).toEqual({
        applicationId: 42,
        callerRoles: ['ANALYST'],
        executionKey: 'xs-merge-aaa',
        externalUserId: 'user-1',
        previouslySeenRoles: ['PAYMENT'],
        subjectType: 'APP',
      });
    });

    it('previouslySeenRoles 为空时不下发该键（空数组会被 qs 整体省略）', () => {
      const query = toMergeQuery(queryForm({ applicationId: 42 }));

      expect(query?.previouslySeenRoles).toBeUndefined();
      expect(
        query && 'previouslySeenRoles' in query && query.previouslySeenRoles,
      ).toBeFalsy();
    });

    it('应用编号缺失时返回 undefined，不产出 applicationId 为 undefined 的请求', () => {
      expect(
        toMergeQuery(queryForm({ applicationId: undefined })),
      ).toBeUndefined();
    });

    it('其余任一必填项缺失都返回 undefined', () => {
      expect(toMergeQuery(queryForm({ executionKey: '' }))).toBeUndefined();
      expect(toMergeQuery(queryForm({ callerRoles: [] }))).toBeUndefined();
      expect(toMergeQuery(queryForm({ externalUserId: '' }))).toBeUndefined();
      expect(toMergeQuery(queryForm({ subjectType: '' }))).toBeUndefined();
    });

    it('返回的是副本，改动结果不应回写表单', () => {
      const form = queryForm({ applicationId: 42, callerRoles: ['ANALYST'] });
      const query = toMergeQuery(form);
      query?.callerRoles.push('DATA_STEWARD');

      expect(form.callerRoles).toEqual(['ANALYST']);
    });
  });
});

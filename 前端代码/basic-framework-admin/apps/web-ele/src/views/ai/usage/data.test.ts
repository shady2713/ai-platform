import { describe, expect, it } from 'vitest';

import {
  describeUsageSource,
  formatDuration,
  formatTokens,
  isUnknownUsage,
  summaryHeadline,
  summaryRows,
  toLocalDateTime,
  windowRange,
} from './data';

describe('用量页数据口径（Q03）', () => {
  it('未知 token 不显示成 0，真实 0 照实显示', () => {
    expect(formatTokens(undefined)).toBe('未知');
    expect(formatTokens(null)).toBe('未知');
    expect(formatTokens(0)).toBe('0');
    expect(formatTokens(1200)).toBe('1200');
    expect(formatDuration(undefined)).toBe('未记录');
    expect(formatDuration(0)).toBe('0 ms');
  });

  it('来源文本覆盖闭集，未知来源原样展示', () => {
    expect(describeUsageSource('REPORTED')).toContain('上游报告');
    expect(describeUsageSource('ESTIMATED')).toContain('估算');
    expect(describeUsageSource('UNKNOWN')).toContain('未知');
    expect(describeUsageSource('FUTURE')).toBe('FUTURE');
    expect(describeUsageSource(undefined)).toBe('-');
    expect(isUnknownUsage('UNKNOWN')).toBe(true);
    expect(isUnknownUsage('REPORTED')).toBe(false);
  });

  it('时间窗使用本地 ISO（不带时区后缀），止为当前时刻', () => {
    const now = new Date(2026, 8, 26, 10, 30, 5);
    const range = windowRange(1, now);
    expect(range.to).toBe('2026-09-26T10:30:05');
    expect(range.from).toBe('2026-09-25T10:30:05');
    expect(toLocalDateTime(now)).toBe('2026-09-26T10:30:05');
    expect(windowRange(0, now).from).toBe('2026-09-25T10:30:05');
  });

  it('汇总按渠道稳定排序并显式给出未知条数', () => {
    const rows = summaryRows({
      sources: [
        {
          inputTokens: 0,
          invocationCount: 1,
          outputTokens: 0,
          usageSource: 'UNKNOWN',
        },
        {
          inputTokens: 10,
          invocationCount: 2,
          outputTokens: 4,
          usageSource: 'REPORTED',
        },
        {
          inputTokens: 5,
          invocationCount: 3,
          outputTokens: 6,
          usageSource: 'ESTIMATED',
        },
      ],
      unknownInvocations: 1,
    });
    expect(rows.map((row) => row.usageSource)).toStrictEqual([
      'REPORTED',
      'ESTIMATED',
      'UNKNOWN',
    ]);
    expect(
      summaryHeadline({
        sources: rows,
        unknownInvocations: 1,
      }),
    ).toBe('共 6 次调用，其中来源未知 1 次');
    expect(summaryHeadline(undefined)).toBe('尚未读取汇总');
  });
});

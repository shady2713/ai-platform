import { describe, expect, it } from 'vitest';

import {
  changeText,
  describeDataLevel,
  describeFailureCode,
  describeResultStatus,
  describeReviewStatus,
  describeRuleKind,
  describeRunStatus,
  describeSeverity,
  describeSubjectType,
  describeSuiteStatus,
  digestText,
  isReviewPending,
  isSuiteEditable,
  parsePositiveInt,
  resultTagType,
  severityTagType,
  trimmedOrUndefined,
  truncateText,
} from './data';

describe('评测控制面展示口径（Q05）', () => {
  it('状态与级别文案覆盖闭集，未知值原样展示', () => {
    expect(describeSuiteStatus('DRAFT')).toContain('草稿');
    expect(describeSuiteStatus('FROZEN')).toContain('冻结');
    expect(describeSuiteStatus('FUTURE')).toBe('FUTURE');
    expect(describeSuiteStatus(undefined)).toBe('-');
    expect(describeRunStatus('RUNNING')).toContain('执行中');
    expect(describeRunStatus('FUTURE')).toBe('FUTURE');
    expect(describeResultStatus('PASSED')).toContain(
      '仅此状态计入 passedCount',
    );
    expect(describeResultStatus('REVIEW_REQUIRED')).toContain(
      '复核通过前不算通过',
    );
    expect(describeResultStatus('FUTURE')).toBe('FUTURE');
    expect(describeReviewStatus('PENDING')).toBe('等待复核');
    expect(describeReviewStatus('FUTURE')).toBe('FUTURE');
    expect(describeSeverity('BLOCKER')).toContain('阻断级');
    expect(describeSeverity('FUTURE')).toBe('FUTURE');
    expect(describeRuleKind('MONEY')).toBe('金额');
    expect(describeRuleKind('NEW_KIND')).toBe('未登记规则类型 NEW_KIND');
    expect(describeRuleKind(undefined)).toBe('-');
    expect(describeSubjectType('APP')).toContain('应用');
    expect(describeSubjectType('BOT')).toBe('BOT');
    expect(describeDataLevel('L1_PUBLIC')).toContain('L1');
    expect(describeDataLevel('L3')).toBe('L3');
    expect(describeDataLevel(undefined)).toBe('-');
    expect(describeFailureCode('AI_EVAL_EXECUTION_FAILED')).toContain(
      '未产生判定',
    );
    expect(describeFailureCode('999')).toBe('未登记错误码 999');
    expect(describeFailureCode(undefined)).toBe('-');
  });

  it('展示辅助函数：摘要、裁断、三态变化、标签色与可编辑/可复核判定', () => {
    expect(truncateText('abcdef', 3)).toBe('abc…');
    expect(truncateText('abc', 3)).toBe('abc');
    expect(digestText('f'.repeat(64))).toBe('ffffffffffff…');
    expect(digestText(undefined)).toBe('未冻结（无摘要）');
    expect(changeText(true)).toBe('变化');
    expect(changeText(false)).toBe('无变化');
    expect(changeText(null)).toBe('无法比较');
    expect(severityTagType('BLOCKER')).toBe('danger');
    expect(severityTagType('MAJOR')).toBe('warning');
    expect(severityTagType('MINOR')).toBe('info');
    expect(severityTagType('FUTURE')).toBe('info');
    expect(resultTagType('PASSED')).toBe('success');
    expect(resultTagType('FAILED')).toBe('danger');
    expect(resultTagType('REVIEW_REQUIRED')).toBe('warning');
    expect(resultTagType('ERROR')).toBe('info');
    expect(isSuiteEditable('DRAFT')).toBe(true);
    expect(isSuiteEditable('FROZEN')).toBe(false);
    expect(isSuiteEditable(undefined)).toBe(false);
    expect(isReviewPending('PENDING')).toBe(true);
    expect(isReviewPending('APPROVED')).toBe(false);
  });

  it('服务编号解析与空串处理：非法值不静默变成数字', () => {
    expect(parsePositiveInt('4')).toBe(4);
    expect(parsePositiveInt(' 12 ')).toBe(12);
    expect(parsePositiveInt('')).toBeUndefined();
    expect(parsePositiveInt('0')).toBeUndefined();
    expect(parsePositiveInt('-3')).toBeUndefined();
    expect(parsePositiveInt('4.5')).toBeUndefined();
    expect(parsePositiveInt('abc')).toBeUndefined();
    expect(trimmedOrUndefined('  x ')).toBe('x');
    expect(trimmedOrUndefined('   ')).toBeUndefined();
  });
});

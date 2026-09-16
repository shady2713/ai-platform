import { describe, expect, it } from 'vitest';

import {
  baselineFailures,
  countReport,
  countRuleMessages,
  fileRatchetFailures,
  ratchetFailures,
} from './check-explicit-any.mjs';

describe('显式 any 棘轮', () => {
  it('只统计 no-explicit-any 规则', () => {
    expect(
      countRuleMessages({
        messages: [
          { ruleId: '@typescript-eslint/no-explicit-any' },
          { ruleId: '@typescript-eslint/no-unused-vars' },
          { ruleId: '@typescript-eslint/no-explicit-any' },
        ],
      }),
    ).toBe(2);
  });

  it('总量、文件和基线上限都只能持平或下降', () => {
    expect(ratchetFailures(4, 4)).toEqual([]);
    expect(ratchetFailures(5, 4)).toHaveLength(1);
    expect(fileRatchetFailures('source.ts', 1, 1)).toEqual([]);
    expect(fileRatchetFailures('source.ts', 2, 1)).toHaveLength(1);
    expect(baselineFailures(3, 4)).toEqual([]);
    expect(baselineFailures(5, 4)).toHaveLength(1);
  });

  it('明细只保留有债务的文件并稳定排序', () => {
    expect(
      countReport(
        new Map([
          ['a-large.ts', 3],
          ['small.ts', 1],
          ['z-large.ts', 3],
          ['zero.ts', 0],
        ]),
      ),
    ).toEqual([
      { count: 3, sourcePath: 'a-large.ts' },
      { count: 3, sourcePath: 'z-large.ts' },
      { count: 1, sourcePath: 'small.ts' },
    ]);
  });
});

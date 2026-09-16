import assert from 'node:assert/strict';
import { describe, it } from 'node:test';

import {
  addMissingBackendReportCoverage,
  baselineFailures,
  coverageFailures,
  coveragePercent,
} from './check-coverage-ratchet.mjs';

describe('coverage ratchet', () => {
  it('rounds line coverage to two decimals', () => {
    assert.equal(coveragePercent(1, 3), 33.33);
    assert.equal(coveragePercent(0, 0), 100);
  });

  it('rejects report regressions and unregistered files', () => {
    assert.deepEqual(
      coverageFailures(
        { 'covered.ts': 79.9, 'new.ts': 100 },
        { 'covered.ts': 80, 'missing.ts': 50 },
      ),
      [
        'covered.ts: 当前 79.9% 低于基线 80%',
        'missing.ts: 覆盖率报告缺失',
        'new.ts: 尚未登记单文件覆盖率基线',
      ],
    );
  });

  it('rejects floor reductions and low-coverage new files', () => {
    assert.deepEqual(
      baselineFailures(
        { 'existing.java': 89.9, 'new.java': 79.9 },
        { 'existing.java': 90, 'removed.java': 50 },
        80,
      ),
      [
        'existing.java: 基线从 90% 下调为 89.9%',
        'removed.java: 已登记的覆盖率基线被删除',
        'new.java: 新文件覆盖率 79.9% 低于 80%',
      ],
    );
  });

  it('allows historical source onboarding below the new-file minimum', () => {
    assert.deepEqual(
      baselineFailures(
        { 'historical.java': 0 },
        {},
        80,
        () => true,
        (sourcePath) => sourcePath === 'historical.java',
      ),
      [],
    );
  });

  it('allows removal only when the source file no longer exists', () => {
    assert.deepEqual(
      baselineFailures({}, { 'deleted.java': 50 }, 80, () => false),
      [],
    );
  });

  it('turns every source in a module without a report into zero coverage', () => {
    const result = addMissingBackendReportCoverage(
      { 'reported/covered.java': 85 },
      [
        {
          moduleRoot: 'missing-module',
          sourcePaths: ['missing/first.java', 'missing/second.java'],
        },
        {
          moduleRoot: 'reported-module',
          sourcePaths: ['reported/covered.java'],
        },
      ],
      new Set(['reported-module']),
    );

    assert.deepEqual(result.coverage, {
      'reported/covered.java': 85,
      'missing/first.java': 0,
      'missing/second.java': 0,
    });
    assert.deepEqual([...result.unreportedSourcePaths], [
      'missing/first.java',
      'missing/second.java',
    ]);
  });

});

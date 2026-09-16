import assert from 'node:assert/strict';
import test from 'node:test';

import { missingRejectionTests } from './check-gate-rejection-tests.mjs';

test('缺少拒绝测试的门禁被逐个列出', () => {
  const missing = missingRejectionTests(
    ['check-alpha.mjs', 'check-beta.mjs'],
    ['check-alpha.test.mjs'],
  );
  assert.equal(missing.length, 1);
  assert.match(missing[0], /check-beta\.mjs/);
  assert.match(missing[0], /check-beta\.test\.mjs/);
  assert.match(missing[0], /变红/);
});

test('全部门禁都有拒绝测试时不报错', () => {
  assert.deepEqual(missingRejectionTests(['check-alpha.mjs'], ['check-alpha.test.mjs']), []);
});

test('非 check 前缀的脚本不参与本门禁判断', () => {
  assert.deepEqual(
    missingRejectionTests(['check-alpha.mjs'], ['check-alpha.test.mjs', 'run-build.test.mjs']),
    [],
  );
});
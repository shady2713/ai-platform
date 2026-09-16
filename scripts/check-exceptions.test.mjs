import assert from 'node:assert/strict';
import test from 'node:test';

import { catalogFailures } from './check-exceptions.mjs';

const TODAY = new Date(2026, 8, 10);

function entry(overrides = {}) {
  return {
    id: 'sample-exception',
    title: '样例',
    owner: '维护者',
    dueDate: '2026-12-31',
    removalCondition: '条件满足后移除',
    status: 'active',
    ...overrides,
  };
}

function catalog(overrides = {}) {
  return { version: 1, exceptions: [], ...overrides };
}

test('空台账通过（没有例外就没有过期项）', () => {
  assert.deepEqual(catalogFailures(catalog(), TODAY), []);
});

test('version 不为 1 时被拒绝', () => {
  const failures = catalogFailures(catalog({ version: 2 }), TODAY);
  assert.equal(failures.length, 1);
  assert.match(failures[0], /version 必须为 1/);
});

test('缺少必填键时逐个报出', () => {
  const failures = catalogFailures(catalog({ exceptions: [{ id: 'x' }] }), TODAY);
  assert.ok(failures.some((item) => item.includes('缺少必填键 owner')));
  assert.ok(failures.some((item) => item.includes('缺少必填键 dueDate')));
});

test('id 重复时被拒绝', () => {
  const failures = catalogFailures(
    catalog({ exceptions: [entry(), entry({ title: '重复' })] }),
    TODAY,
  );
  assert.ok(failures.some((item) => item.includes('id 重复')));
});

test('status 非法时被拒绝', () => {
  const failures = catalogFailures(catalog({ exceptions: [entry({ status: 'pending' })] }), TODAY);
  assert.equal(failures.length, 1);
  assert.match(failures[0], /status 必须是 active 或 removed/);
});

test('dueDate 非 YYYY-MM-DD 时被拒绝', () => {
  const failures = catalogFailures(catalog({ exceptions: [entry({ dueDate: '2026/12/31' })] }), TODAY);
  assert.ok(failures.some((item) => item.includes('必须是 YYYY-MM-DD')));
});

test('active 且已过期时被拒绝（门禁的核心拒绝语义）', () => {
  const failures = catalogFailures(
    catalog({ exceptions: [entry({ dueDate: '2026-01-01' })] }),
    TODAY,
  );
  assert.equal(failures.length, 1);
  assert.match(failures[0], /例外已过期/);
});

test('removed 条目不检查到期', () => {
  const failures = catalogFailures(
    catalog({ exceptions: [entry({ status: 'removed', dueDate: '2020-01-01' })] }),
    TODAY,
  );
  assert.deepEqual(failures, []);
});

test('条目不是映射时被拒绝', () => {
  const failures = catalogFailures(catalog({ exceptions: ['not-a-map'] }), TODAY);
  assert.equal(failures.length, 1);
  assert.match(failures[0], /不是映射/);
});

test('台账本身不是映射时被拒绝', () => {
  assert.deepEqual(catalogFailures(null, TODAY), ['台账内容不是映射']);
  assert.deepEqual(catalogFailures([], TODAY), ['台账内容不是映射']);
});
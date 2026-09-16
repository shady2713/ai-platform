import assert from 'node:assert/strict';
import test from 'node:test';

import { permissionFailures } from './check-permission-catalog.mjs';

function ledger(overrides = {}) {
  return {
    version: 1,
    catalogCodesRemovedByMigration: [],
    notGrantable: [],
    unusedCatalog: [],
    ...overrides,
  };
}

function declared(...codes) {
  return new Map(codes.map((code) => [code, new Set(['X.java'])]));
}

test('引用了目录外且未登记的权限码时判定端点永不可达', () => {
  const failures = permissionFailures(declared('system:menu:query'), new Set(), ledger());
  assert.equal(failures.length, 1);
  assert.match(failures[0], /system:menu:query/);
  assert.match(failures[0], /永远无法获得/);
});

test('登记为不可授予后同一权限码不再失败', () => {
  const failures = permissionFailures(
    declared('system:menu:query'),
    new Set(),
    ledger({ notGrantable: [{ code: 'system:menu:query', reason: 'r', control: 'c' }] }),
  );
  assert.deepEqual(failures, []);
});

test('目录中存在但无人使用且未登记的权限码被拒绝', () => {
  const failures = permissionFailures(new Map(), new Set(['infra:x:export']), ledger());
  assert.equal(failures.length, 1);
  assert.match(failures[0], /infra:x:export/);
  assert.match(failures[0], /unusedCatalog/);
});

test('过期的不可授予登记被拒绝', () => {
  const failures = permissionFailures(
    new Map(),
    new Set(),
    ledger({ notGrantable: [{ code: 'system:menu:query', reason: 'r', control: 'c' }] }),
  );
  assert.equal(failures.length, 1);
  assert.match(failures[0], /登记已过期/);
});

test('过期的迁移删除登记被拒绝', () => {
  const failures = permissionFailures(
    new Map(),
    new Set(['crm:customer:query']),
    ledger({
      catalogCodesRemovedByMigration: [
        { code: 'crm:customer:create', migration: 'V31__x.sql', reason: 'r' },
      ],
      unusedCatalog: [{ code: 'crm:customer:query', reason: 'r' }],
    }),
  );
  assert.equal(failures.length, 1);
  assert.match(failures[0], /crm:customer:create/);
  assert.match(failures[0], /登记已过期/);
});

test('迁移删除登记缺少 migration 或 reason 时被拒绝', () => {
  const failures = permissionFailures(
    new Map(),
    new Set(['crm:customer:query']),
    ledger({ catalogCodesRemovedByMigration: [{ code: 'crm:customer:query' }] }),
  );
  assert.equal(failures.length, 1);
  assert.match(failures[0], /必须填写 migration 与 reason/);
});

test('保留登记被接口实际使用时判定登记过期', () => {
  const failures = permissionFailures(
    declared('system:user:list'),
    new Set(['system:user:list']),
    ledger({ unusedCatalog: [{ code: 'system:user:list', reason: 'r' }] }),
  );
  assert.equal(failures.length, 1);
  assert.match(failures[0], /已被接口使用/);
});

test('引用、目录与台账一致时通过', () => {
  const failures = permissionFailures(
    declared('system:user:query'),
    new Set(['system:user:query', 'system:user:list']),
    ledger({ unusedCatalog: [{ code: 'system:user:list', reason: 'r' }] }),
  );
  assert.deepEqual(failures, []);
});
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import test from 'node:test';

import { parseYaml } from './gate-utils.mjs';

const REPO_ROOT = resolve(import.meta.dirname, '..');
const CATALOG_PATH = resolve(REPO_ROOT, 'docs/contracts/field-catalog.yaml');

test('真实字段契约可被解析且结构符合门禁预期', () => {
  const catalog = parseYaml(readFileSync(CATALOG_PATH, 'utf8'));
  assert.equal(catalog.version, 1);
  assert.ok(Array.isArray(catalog.fields));
  assert.ok(catalog.fields.length > 0);
  for (const field of catalog.fields) {
    assert.equal(typeof field.id, 'string');
    assert.ok(field.id.length > 0);
  }
});

test('真实字段契约的字段数量与门禁输出一致', () => {
  const catalog = parseYaml(readFileSync(CATALOG_PATH, 'utf8'));
  const statuses = new Set(catalog.fields.map((field) => field.status));
  for (const status of statuses) {
    assert.ok(['aligned', 'drift'].includes(status));
  }
});
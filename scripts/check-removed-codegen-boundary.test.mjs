import assert from 'node:assert/strict';
import { readdir, readFile } from 'node:fs/promises';
import test from 'node:test';

const backendProductRoot = new URL(
  '../后端代码/basic-framework-boot/basic-framework-module-infra/src/main/java/',
  import.meta.url,
);
const frontendProductRoot = new URL(
  '../前端代码/basic-framework-admin/apps/web-ele/src/',
  import.meta.url,
);
const finalSnapshotPath = new URL('../数据库文件/basic_framework.sql', import.meta.url);
const removalMigrationPath = new URL(
  '../后端代码/basic-framework-boot/basic-framework-server/src/main/resources/db/migration/V32__remove_code_generation.sql',
  import.meta.url,
);

async function readSourceTree(directory) {
  const files = [];
  for (const entry of await readdir(directory, { withFileTypes: true })) {
    const path = new URL(`${entry.name}${entry.isDirectory() ? '/' : ''}`, directory);
    if (entry.isDirectory()) {
      files.push(...(await readSourceTree(path)));
    } else {
      files.push({ name: decodeURIComponent(path.pathname), text: await readFile(path, 'utf8') });
    }
  }
  return files;
}

test('removed code generation capability cannot reappear in current product sources', async () => {
  const files = [
    ...(await readSourceTree(backendProductRoot)),
    ...(await readSourceTree(frontendProductRoot)),
  ];
  const forbidden = /infra[_:]codegen|[\\/]codegen[\\/]/i;

  for (const file of files) {
    assert.ok(!forbidden.test(file.name), `removed code generation path reappeared: ${file.name}`);
    assert.ok(!forbidden.test(file.text), `removed code generation reference reappeared: ${file.name}`);
  }
});

test('final database snapshot stays free of removed code generation metadata', async () => {
  const snapshot = await readFile(finalSnapshotPath, 'utf8');

  assert.doesNotMatch(snapshot, /infra_codegen|infra:codegen|代码生成/i);
});

test('upgrade migration removes every persisted code generation surface', async () => {
  const migration = await readFile(removalMigrationPath, 'utf8');

  assert.match(migration, /DELETE FROM `system_menu`/);
  assert.match(migration, /permission` LIKE 'infra:codegen:%'/);
  assert.match(migration, /DELETE FROM `system_dict_data`[\s\S]*dict_type` LIKE 'infra_codegen_%'/);
  assert.match(migration, /DELETE FROM `system_dict_type`[\s\S]*type` LIKE 'infra_codegen_%'/);
  assert.match(migration, /DROP TABLE IF EXISTS `infra_codegen_column`/);
  assert.match(migration, /DROP TABLE IF EXISTS `infra_codegen_table`/);
});

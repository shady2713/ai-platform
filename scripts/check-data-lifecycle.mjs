#!/usr/bin/env node
// 数据生命周期与外键台账漂移门禁：最终 Flyway schema 中的每张表和每条物理 FK
// 都必须在 docs/contracts/data-lifecycle.json 中显式登记。

import { readdirSync, readFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const CATALOG_PATH = join(ROOT, 'docs/contracts/data-lifecycle.json');
const SNAPSHOT_PATH = join(ROOT, '数据库文件/basic_framework.sql');
const MIGRATION_DIR = join(
  ROOT,
  '后端代码/basic-framework-boot/basic-framework-server/src/main/resources/db/migration',
);
const VALID_POLICIES = new Set([
  'append-retention',
  'hard-delete',
  'platform-managed',
  'soft-delete',
]);

function migrationVersion(fileName) {
  const match = /^V(\d+)__.*\.sql$/.exec(fileName);
  return match ? Number.parseInt(match[1], 10) : Number.MAX_SAFE_INTEGER;
}

export function readMigrations(migrationDir = MIGRATION_DIR) {
  return readdirSync(migrationDir)
    .filter((name) => /^V\d+__.*\.sql$/.test(name))
    .sort((left, right) => migrationVersion(left) - migrationVersion(right))
    .map((name) => ({
      name,
      sql: readFileSync(join(migrationDir, name), 'utf8'),
    }));
}

export function finalTables(migrations) {
  const tables = new Set();
  const statementPattern =
    /(CREATE\s+TABLE(?:\s+IF\s+NOT\s+EXISTS)?|DROP\s+TABLE\s+IF\s+EXISTS)\s+`?([A-Za-z0-9_]+)`?/gi;
  for (const { sql } of migrations) {
    for (const match of sql.matchAll(statementPattern)) {
      if (/^CREATE/i.test(match[1])) {
        tables.add(match[2]);
      } else {
        tables.delete(match[2]);
      }
    }
  }
  return tables;
}

export function physicalForeignKeys(migrations) {
  const constraints = new Map();
  const tableDefinitionPattern =
    /CREATE\s+TABLE(?:\s+IF\s+NOT\s+EXISTS)?\s+`?([A-Za-z0-9_]+)`?\s*\(([\s\S]*?)\)\s*ENGINE[^;]*;|ALTER\s+TABLE\s+`?([A-Za-z0-9_]+)`?([\s\S]*?);/gi;
  for (const { sql } of migrations) {
    for (const definition of sql.matchAll(tableDefinitionPattern)) {
      const table = definition[1] ?? definition[3];
      const body = definition[2] ?? definition[4];
      for (const match of body.matchAll(
        /CONSTRAINT\s+`?([A-Za-z0-9_]+)`?\s+FOREIGN\s+KEY/gi,
      )) {
        constraints.set(match[1], table);
      }
      for (const match of body.matchAll(
        /\bDROP\s+FOREIGN\s+KEY\s+`?([A-Za-z0-9_]+)`?/gi,
      )) {
        constraints.delete(match[1]);
      }
    }
  }
  const existingTables = finalTables(migrations);
  return new Set(
    [...constraints.entries()]
      .filter(([, table]) => existingTables.has(table))
      .map(([constraint]) => constraint),
  );
}

export function tablesWithDeletedColumn(migrations) {
  const tables = new Set();
  const schemaEventPattern =
    /CREATE\s+TABLE(?:\s+IF\s+NOT\s+EXISTS)?\s+`?([A-Za-z0-9_]+)`?\s*\(([\s\S]*?)\)\s*ENGINE[^;]*;|ALTER\s+TABLE\s+`?([A-Za-z0-9_]+)`?([\s\S]*?);/gi;
  for (const { sql } of migrations) {
    for (const match of sql.matchAll(schemaEventPattern)) {
      if (match[1]) {
        if (/^\s*`deleted`\s+/im.test(match[2])) {
          tables.add(match[1]);
        } else {
          tables.delete(match[1]);
        }
        continue;
      }
      if (/\bDROP\s+(?:COLUMN\s+)?`?deleted`?\b/i.test(match[4])) {
        tables.delete(match[3]);
      }
      if (/\bADD\s+(?:COLUMN\s+)?`?deleted`?\b/i.test(match[4])) {
        tables.add(match[3]);
      }
    }
  }
  return tables;
}

export function compareSets(actual, expected, label, errors) {
  for (const value of [...actual].sort()) {
    if (!expected.has(value)) {
      errors.push(`${label} 未登记：${value}`);
    }
  }
  for (const value of [...expected].sort()) {
    if (!actual.has(value)) {
      errors.push(`${label} 登记项不存在：${value}`);
    }
  }
}

export function validateSnapshotVersion(snapshotSql, migrations) {
  const declared =
    /-- Snapshot note: aligned with the authoritative Flyway migration chain through V(\d+)\./.exec(
      snapshotSql,
    );
  const latest = Math.max(
    ...migrations.map(({ name }) => migrationVersion(name)),
  );
  if (!declared || Number(declared[1]) !== latest) {
    return [
      `快照声明版本必须与最新 Flyway 迁移 V${latest} 一致，当前：${declared?.[1] ?? '未声明'}`,
    ];
  }
  return [];
}

function main() {
  const errors = [];
  let catalog;
  try {
    catalog = JSON.parse(readFileSync(CATALOG_PATH, 'utf8'));
  } catch (error) {
    console.error(`FAIL 生命周期台账解析失败：${error.message}`);
    process.exitCode = 1;
    return;
  }

  if (catalog.version !== 1) {
    errors.push(`version 必须为 1，当前：${String(catalog.version)}`);
  }
  if (!Array.isArray(catalog.policies)) {
    errors.push('policies 必须是数组');
  }
  if (!Array.isArray(catalog.physicalForeignKeys)) {
    errors.push('physicalForeignKeys 必须是数组');
  }

  const catalogTables = new Set();
  const policyIds = new Set();
  for (const policy of catalog.policies ?? []) {
    if (!VALID_POLICIES.has(policy.id)) {
      errors.push(`未知生命周期策略：${String(policy.id)}`);
    }
    if (policyIds.has(policy.id)) {
      errors.push(`生命周期策略重复：${policy.id}`);
    }
    policyIds.add(policy.id);
    if (!Array.isArray(policy.tables) || policy.tables.length === 0) {
      errors.push(`${String(policy.id)}.tables 必须是非空数组`);
      continue;
    }
    for (const table of policy.tables) {
      if (catalogTables.has(table)) {
        errors.push(`表被重复登记：${table}`);
      }
      catalogTables.add(table);
    }
  }

  const migrations = readMigrations();
  const schemaTables = finalTables(migrations);
  compareSets(schemaTables, catalogTables, '最终表', errors);

  const schemaForeignKeys = physicalForeignKeys(migrations);
  const catalogForeignKeys = new Set(catalog.physicalForeignKeys ?? []);
  if (catalogForeignKeys.size !== (catalog.physicalForeignKeys ?? []).length) {
    errors.push('physicalForeignKeys 存在重复项');
  }
  compareSets(schemaForeignKeys, catalogForeignKeys, '物理外键', errors);

  const schemaDeletedTables = tablesWithDeletedColumn(migrations);
  for (const table of schemaDeletedTables) {
    if (!schemaTables.has(table)) {
      schemaDeletedTables.delete(table);
    }
  }
  const softDeleteTables = new Set(
    (catalog.policies ?? []).find((policy) => policy.id === 'soft-delete')
      ?.tables ?? [],
  );
  compareSets(schemaDeletedTables, softDeleteTables, '逻辑删除列', errors);

  const snapshot = [
    {
      name: '数据库文件/basic_framework.sql',
      sql: readFileSync(SNAPSHOT_PATH, 'utf8'),
    },
  ];
  const snapshotTables = finalTables(snapshot);
  errors.push(...validateSnapshotVersion(snapshot[0].sql, migrations));
  const snapshotForeignKeys = physicalForeignKeys(snapshot);
  const snapshotDeletedTables = tablesWithDeletedColumn(snapshot);
  compareSets(snapshotTables, schemaTables, '快照表', errors);
  compareSets(snapshotForeignKeys, schemaForeignKeys, '快照物理外键', errors);
  compareSets(
    snapshotDeletedTables,
    schemaDeletedTables,
    '快照逻辑删除列',
    errors,
  );

  for (const error of errors) {
    console.error(`FAIL ${error}`);
  }
  console.log(
    `生命周期台账：最终表 ${schemaTables.size} 张，策略登记 ${catalogTables.size} 张，逻辑删除列 ${schemaDeletedTables.size} 张，物理外键 ${schemaForeignKeys.size} 条；快照同步`,
  );
  if (errors.length > 0) {
    console.error(`生命周期/外键漂移检查失败 ${errors.length} 项`);
    process.exitCode = 1;
  } else {
    console.log('生命周期/外键漂移检查通过');
  }
}

if (
  process.argv[1] &&
  resolve(process.argv[1]) === fileURLToPath(import.meta.url)
) {
  main();
}

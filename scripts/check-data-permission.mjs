#!/usr/bin/env node
// 数据权限默认安全门禁：每张应用表必须由运行时规则保护，或由带生产源码证据的显式豁免覆盖。

import { readdirSync, readFileSync, statSync } from 'node:fs';
import { dirname, isAbsolute, join, relative, resolve, sep } from 'node:path';
import { fileURLToPath } from 'node:url';

import { finalTables, readMigrations } from './check-data-lifecycle.mjs';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const BACKEND_ROOT = join(ROOT, '后端代码/basic-framework-boot');
const CATALOG_PATH = join(ROOT, 'docs/contracts/data-permission-exemptions.json');
const LIFECYCLE_PATH = join(ROOT, 'docs/contracts/data-lifecycle.json');
const SNAPSHOT_PATH = join(ROOT, '数据库文件/basic_framework.sql');
const EXEMPTION_CONTROL_KINDS = new Set([
  'credential-bound',
  'function-permission',
  'internal-only',
  'owner-or-permission',
  'subject-bound',
  'target-validated',
]);

export function snapshotTableColumns(sql) {
  const result = new Map();
  const tablePattern =
    /CREATE\s+TABLE(?:\s+IF\s+NOT\s+EXISTS)?\s+`?([A-Za-z0-9_]+)`?\s*\(([\s\S]*?)\)\s*ENGINE[^;]*;/gi;
  for (const match of sql.matchAll(tablePattern)) {
    const columns = new Set();
    for (const column of match[2].matchAll(/(?:^|,)\s*`([A-Za-z0-9_]+)`\s+/gm)) {
      columns.add(column[1]);
    }
    result.set(match[1], columns);
  }
  return result;
}

function readJavaSources(directory, result = []) {
  for (const entry of readdirSync(directory)) {
    if (entry === 'target') {
      continue;
    }
    const path = join(directory, entry);
    if (statSync(path).isDirectory()) {
      readJavaSources(path, result);
    } else if (path.endsWith('.java') && path.includes(`${join('src', 'main', 'java')}`)) {
      result.push({ path: relative(ROOT, path), source: readFileSync(path, 'utf8') });
    }
  }
  return result;
}

export function runtimeRegistrations(sources) {
  const registrations = new Map();
  const errors = [];
  const invocationPattern = /\.\s*add(Dept|User)Column\s*\(([^;]*?)\)\s*;/g;
  const literalPattern = /^\s*"([A-Za-z0-9_]+)"\s*,\s*"([A-Za-z0-9_]+)"\s*$/;

  for (const { path, source } of sources) {
    for (const match of source.matchAll(invocationPattern)) {
      const literal = literalPattern.exec(match[2]);
      if (!literal) {
        errors.push(
          `数据权限登记必须使用显式表名和列名：${path} -> add${match[1]}Column(${match[2].trim()})`,
        );
        continue;
      }
      const [, table, column] = literal;
      const key = match[1] === 'Dept' ? 'deptColumn' : 'userColumn';
      const registration = registrations.get(table) ?? {};
      if (registration[key] && registration[key] !== column) {
        errors.push(
          `数据权限列重复且不一致：${table}.${key}=${registration[key]}/${column}`,
        );
      }
      registration[key] = column;
      registrations.set(table, registration);
    }
  }
  return { errors, registrations };
}

export function validateDataPermissionContract({
  catalog,
  platformTables,
  registrations,
  schemaTables,
  snapshotColumns,
  evidenceSources = new Map(),
}) {
  const errors = [];
  if (catalog.version !== 3) {
    errors.push(`version 必须为 3，当前：${String(catalog.version)}`);
  }
  if (!Array.isArray(catalog.exemptions)) {
    errors.push('exemptions 必须是数组');
  }

  const exemptTables = new Set();
  const exemptionIds = new Set();
  for (const exemption of catalog.exemptions ?? []) {
    if (typeof exemption.id !== 'string' || exemption.id.length === 0) {
      errors.push('数据权限豁免 id 不能为空');
    } else if (exemptionIds.has(exemption.id)) {
      errors.push(`数据权限豁免 id 重复：${exemption.id}`);
    } else {
      exemptionIds.add(exemption.id);
    }
    if (typeof exemption.reason !== 'string' || exemption.reason.trim().length < 10) {
      errors.push(`数据权限豁免必须说明具体理由：${String(exemption.id)}`);
    }
    if (!Array.isArray(exemption.tables) || exemption.tables.length === 0) {
      errors.push(`数据权限豁免 tables 必须是非空数组：${String(exemption.id)}`);
      continue;
    }
    const exemptionTableSet = new Set(exemption.tables);
    for (const table of exemption.tables) {
      if (exemptTables.has(table)) {
        errors.push(`数据权限豁免表重复：${table}`);
      }
      exemptTables.add(table);
    }
    const coveredTables = new Set();
    if (!Array.isArray(exemption.controls) || exemption.controls.length === 0) {
      errors.push(`数据权限豁免 controls 必须是非空数组：${String(exemption.id)}`);
    }
    for (const control of exemption.controls ?? []) {
      if (!EXEMPTION_CONTROL_KINDS.has(control.kind)) {
        errors.push(`数据权限豁免控制类型无效：${String(control.kind)} (${String(exemption.id)})`);
      }
      if (!Array.isArray(control.tables) || control.tables.length === 0) {
        errors.push(`数据权限豁免控制 tables 必须是非空数组：${String(exemption.id)}`);
      }
      for (const table of control.tables ?? []) {
        if (!exemptionTableSet.has(table)) {
          errors.push(`数据权限豁免控制引用组外表：${table} (${String(exemption.id)})`);
        } else {
          coveredTables.add(table);
        }
      }
      if (!Array.isArray(control.evidence) || control.evidence.length === 0) {
        errors.push(`数据权限豁免控制 evidence 必须是非空数组：${String(exemption.id)}`);
      }
      const evidenceCoveredTables = new Set();
      for (const evidence of control.evidence ?? []) {
        const table = evidence?.table;
        if (typeof table !== 'string' || !control.tables?.includes(table)) {
          errors.push(
            `数据权限豁免证据必须绑定当前控制内的表：${String(table)} (${String(exemption.id)})`,
          );
        } else {
          evidenceCoveredTables.add(table);
        }
        const path = evidence?.path;
        const contains = evidence?.contains;
        if (!isProductionEvidencePath(path)) {
          errors.push(`数据权限豁免证据路径必须指向生产 Java 源码：${String(path)} (${String(exemption.id)})`);
          continue;
        }
        if (typeof contains !== 'string' || contains.trim().length < 8) {
          errors.push(`数据权限豁免证据片段过短：${path} (${String(exemption.id)})`);
          continue;
        }
        const source = evidenceSources.get(path);
        if (typeof source !== 'string') {
          errors.push(`数据权限豁免证据文件不存在：${path} (${String(exemption.id)})`);
        } else if (!source.includes(contains)) {
          errors.push(`数据权限豁免证据已漂移：${path} 缺少 ${contains} (${String(exemption.id)})`);
        }
      }
      for (const table of control.tables ?? []) {
        if (!evidenceCoveredTables.has(table)) {
          errors.push(`数据权限豁免表缺少逐表证据：${table} (${String(exemption.id)})`);
        }
      }
    }
    for (const table of exemption.tables) {
      if (!coveredTables.has(table)) {
        errors.push(`数据权限豁免表缺少控制机制：${table} (${String(exemption.id)})`);
      }
    }
  }

  for (const [table, columns] of registrations) {
    if (!schemaTables.has(table)) {
      errors.push(`数据权限运行时登记的表不存在：${table}`);
      continue;
    }
    if (platformTables.has(table)) {
      errors.push(`平台托管表不得登记业务数据权限：${table}`);
    }
    if (exemptTables.has(table)) {
      errors.push(`数据权限表同时被保护和豁免：${table}`);
    }
    const actualColumns = snapshotColumns.get(table) ?? new Set();
    for (const [kind, column] of Object.entries(columns)) {
      if (!actualColumns.has(column)) {
        errors.push(`数据权限运行时列不存在：${table}.${column} (${kind})`);
      }
    }
  }

  for (const table of exemptTables) {
    if (!schemaTables.has(table)) {
      errors.push(`数据权限豁免表不存在：${table}`);
    }
    if (platformTables.has(table)) {
      errors.push(`平台托管表无需重复豁免：${table}`);
    }
  }

  for (const table of schemaTables) {
    if (platformTables.has(table)) {
      continue;
    }
    if (!registrations.has(table) && !exemptTables.has(table)) {
      errors.push(`应用表未登记数据权限或豁免：${table}`);
    }
  }

  for (const table of snapshotColumns.keys()) {
    if (!schemaTables.has(table)) {
      errors.push(`数据权限检查所用快照存在额外表：${table}`);
    }
  }
  for (const table of schemaTables) {
    if (!snapshotColumns.has(table)) {
      errors.push(`数据权限检查所用快照缺少表：${table}`);
    }
  }
  return { errors, exemptTables };
}

function isProductionEvidencePath(path) {
  return (
    typeof path === 'string' &&
    path.length > 0 &&
    !isAbsolute(path) &&
    !path.includes('\\') &&
    !path.split('/').includes('..') &&
    path.startsWith('后端代码/basic-framework-boot/') &&
    path.includes('/src/main/java/') &&
    path.endsWith('.java')
  );
}

function readEvidenceSources(catalog) {
  const sources = new Map();
  const rootPrefix = `${resolve(ROOT)}${sep}`;
  for (const exemption of catalog.exemptions ?? []) {
    for (const control of exemption.controls ?? []) {
      for (const evidence of control.evidence ?? []) {
        if (!isProductionEvidencePath(evidence?.path) || sources.has(evidence.path)) {
          continue;
        }
        const absolutePath = resolve(ROOT, evidence.path);
        if (!absolutePath.startsWith(rootPrefix)) {
          continue;
        }
        try {
          sources.set(evidence.path, readFileSync(absolutePath, 'utf8'));
        } catch {
          // 缺失文件由契约校验生成稳定、可读的错误信息。
        }
      }
    }
  }
  return sources;
}

function main() {
  const catalog = JSON.parse(readFileSync(CATALOG_PATH, 'utf8'));
  const lifecycle = JSON.parse(readFileSync(LIFECYCLE_PATH, 'utf8'));
  const schemaTables = finalTables(readMigrations());
  const snapshotColumns = snapshotTableColumns(readFileSync(SNAPSHOT_PATH, 'utf8'));
  const platformTables = new Set(
    lifecycle.policies.find((policy) => policy.id === 'platform-managed')?.tables ?? [],
  );
  const runtime = runtimeRegistrations(readJavaSources(BACKEND_ROOT));
  const validation = validateDataPermissionContract({
    catalog,
    platformTables,
    registrations: runtime.registrations,
    schemaTables,
    snapshotColumns,
    evidenceSources: readEvidenceSources(catalog),
  });
  const errors = [...runtime.errors, ...validation.errors];

  for (const error of errors) {
    console.error(`FAIL ${error}`);
  }
  console.log(
    `数据权限分类：应用表 ${schemaTables.size - platformTables.size} 张，运行时保护 ${runtime.registrations.size} 张，显式豁免 ${validation.exemptTables.size} 张，平台托管 ${platformTables.size} 张`,
  );
  if (errors.length > 0) {
    console.error(`数据权限分类检查失败 ${errors.length} 项`);
    process.exitCode = 1;
  } else {
    console.log('数据权限分类检查通过');
  }
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main();
}

import { readFileSync, readdirSync } from 'node:fs';
import { dirname, join, relative, resolve, sep } from 'node:path';
import { fileURLToPath } from 'node:url';
import { normalizePath, findFilesBySuffix } from './gate-utils.mjs';

const REPO_ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const BACKEND_ROOT = join(REPO_ROOT, '后端代码/basic-framework-boot');
const MIGRATION_ROOT = join(
  BACKEND_ROOT,
  'basic-framework-server/src/main/resources/db/migration',
);
const LEDGER_PATH = join(REPO_ROOT, 'docs/contracts/permission-catalog.json');

// 权限码形如 <域>:<资源>:<动作>，至少两段冒号，用于与图标值（ep:avatar）区分。
const PERMISSION_PATTERN = /^[a-z][a-z0-9-]*(?::[a-z][a-z0-9-]*){2,}$/;
const BACKTICK = String.fromCharCode(96);

function mainJavaFiles() {
  const marker = `${sep}src${sep}main${sep}java${sep}`;
  return findFilesBySuffix(BACKEND_ROOT, '.java').filter(
    (filePath) => filePath.includes(marker) && !filePath.includes(`${sep}target${sep}`),
  );
}

/** 代码中被引用的权限码：值 -> 引用它的源码路径集合。 */
export function declaredPermissions() {
  const declared = new Map();
  const pattern = /hasPermission\(\s*'([^']+)'\s*\)/g;
  for (const filePath of mainJavaFiles()) {
    const content = readFileSync(filePath, 'utf8');
    let match;
    while ((match = pattern.exec(content)) !== null) {
      const code = match[1];
      if (!PERMISSION_PATTERN.test(code)) {
        continue;
      }
      if (!declared.has(code)) {
        declared.set(code, new Set());
      }
      declared.get(code).add(normalizePath(relative(REPO_ROOT, filePath)));
    }
  }
  return declared;
}

export function migrationFiles() {
  return readdirSync(MIGRATION_ROOT)
    .filter((name) => name.endsWith('.sql'))
    .sort((left, right) => {
      const numberOf = (name) => Number(name.slice(1, name.indexOf('__')));
      return numberOf(left) - numberOf(right) || left.localeCompare(right);
    });
}

/**
 * 有效权限目录：按迁移顺序重放 system_menu 的插入与 permission LIKE 删除。
 * 反引号统一剥离，避免列名书写差异导致漏判。
 * 按 id / parent_id 的删除无法在文本层重放，必须登记到
 * ledger.catalogCodesRemovedByMigration，且登记项必须仍存在于原始扫描结果。
 */
export function catalogPermissions() {
  const catalog = new Set();
  for (const name of migrationFiles()) {
    const content = readFileSync(join(MIGRATION_ROOT, name), 'utf8');
    for (const rawStatement of content.split(';')) {
      const statement = rawStatement.split(BACKTICK).join('');
      if (!/system_menu/i.test(statement)) {
        continue;
      }
      const isInsert = /INSERT\s+INTO/i.test(statement);
      const isDelete = /DELETE\s+FROM/i.test(statement);
      if (!isInsert && !isDelete) {
        continue;
      }
      const likeMatch = /permission\s+LIKE\s+'([^']+)'/i.exec(statement);
      if (isDelete && likeMatch) {
        const prefix = likeMatch[1].replace(/%$/, '');
        for (const code of [...catalog]) {
          if (code.startsWith(prefix)) {
            catalog.delete(code);
          }
        }
        continue;
      }
      if (!isInsert) {
        continue;
      }
      const quoted = /'([a-z][a-z0-9-]*(?::[a-z][a-z0-9-]*)+)'/g;
      let match;
      while ((match = quoted.exec(statement)) !== null) {
        if (PERMISSION_PATTERN.test(match[1])) {
          catalog.add(match[1]);
        }
      }
    }
  }
  return catalog;
}
/** 纯函数：给定代码引用、原始目录与台账，返回契约失败项。便于门禁自测。 */
export function permissionFailures(declared, rawCatalog, ledger) {
  const removedByMigration = new Map(
    ledger.catalogCodesRemovedByMigration.map((entry) => [entry.code, entry]),
  );
  const notGrantable = new Map(ledger.notGrantable.map((entry) => [entry.code, entry]));
  const unusedCatalog = new Map(ledger.unusedCatalog.map((entry) => [entry.code, entry]));

  const effectiveCatalog = new Set(
    [...rawCatalog].filter((code) => !removedByMigration.has(code)),
  );
  const failures = [];

  // 1) 终点可达性：代码引用的权限码必须可授予，或显式登记为不可授予。
  for (const [code, sources] of declared) {
    if (effectiveCatalog.has(code) || notGrantable.has(code)) {
      continue;
    }
    const where = [...sources].join(', ');
    failures.push(
      `${code}: 已由代码引用但既不在权限目录、也未登记为不可授予；` +
        `非超管角色永远无法获得该权限（引用：${where}）`,
    );
  }

  // 2) 目录有效性：目录中的权限码必须被代码使用，或登记为保留。
  for (const code of effectiveCatalog) {
    if (declared.has(code) || unusedCatalog.has(code)) {
      continue;
    }
    failures.push(`${code}: 在权限目录中但没有任何接口使用，且未登记到 unusedCatalog`);
  }

  // 3) 台账不得过期：登记项必须与代码、目录现状一致。
  for (const [code, entry] of removedByMigration) {
    if (!rawCatalog.has(code)) {
      failures.push(
        `${code}: catalogCodesRemovedByMigration 登记已过期，` +
          `迁移原始扫描结果中不存在该权限码（登记于 ${entry.migration ?? '未填写'}）`,
      );
    }
    if (!entry.migration || !entry.reason) {
      failures.push(`${code}: catalogCodesRemovedByMigration 条目必须填写 migration 与 reason`);
    }
  }
  for (const code of notGrantable.keys()) {
    if (!declared.has(code)) {
      failures.push(`${code}: notGrantable 登记已过期，代码中已无任何接口引用该权限码`);
    }
  }
  for (const code of unusedCatalog.keys()) {
    if (!effectiveCatalog.has(code)) {
      failures.push(`${code}: unusedCatalog 登记已过期，权限目录中已无该权限码`);
    } else if (declared.has(code)) {
      failures.push(`${code}: unusedCatalog 登记已过期，该权限码已被接口使用`);
    }
  }
  return failures;
}

export function readLedger(path = LEDGER_PATH) {
  const ledger = JSON.parse(readFileSync(path, 'utf8'));
  if (ledger.version !== 1) {
    throw new Error('permission-catalog.json 版本不受支持');
  }
  for (const field of ['catalogCodesRemovedByMigration', 'notGrantable', 'unusedCatalog']) {
    if (!Array.isArray(ledger[field])) {
      throw new Error(`permission-catalog.json 缺少数组字段 ${field}`);
    }
  }
  return ledger;
}

function verify() {
  const declared = declaredPermissions();
  const rawCatalog = catalogPermissions();
  const ledger = readLedger();
  const failures = permissionFailures(declared, rawCatalog, ledger);
  if (failures.length > 0) {
    const newline = String.fromCharCode(10);
    throw new Error(`权限目录契约失败：${newline}- ${failures.join(`${newline}- `)}`);
  }
  const effectiveCatalog = rawCatalog.size - ledger.catalogCodesRemovedByMigration.length;
  console.log(
    `权限目录契约通过：接口引用 ${declared.size} 个权限码，目录 ${effectiveCatalog} 个，` +
      `不可授予登记 ${ledger.notGrantable.length} 条，保留登记 ${ledger.unusedCatalog.length} 条`,
  );
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try {
    verify();
  } catch (error) {
    console.error(error.message);
    process.exitCode = 1;
  }
}
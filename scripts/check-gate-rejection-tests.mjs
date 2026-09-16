import { readdirSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const REPO_ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const SCRIPTS_ROOT = join(REPO_ROOT, 'scripts');

/**
 * 本门禁落实根 AGENTS.md 的元规则：
 * 「Requirements described as CI-enforced must have a blocking gate and a test
 *   that demonstrates rejection of a violation.」
 *
 * 只判断「有没有拒绝测试」这一件事。测试是否真的被执行由 check-gate-wiring.mjs
 * 负责（它保证每个 *.test.mjs 都在双 provider 中通过 node --test 运行），
 * 本门禁不重复该校验以免两处给出同一失败。
 */
export function missingRejectionTests(checkNames, testNames) {
  const available = new Set(testNames);
  const missing = [];
  for (const name of checkNames) {
    const expected = name.replace(/\.mjs$/, '.test.mjs');
    if (!available.has(expected)) {
      missing.push(
        `${name}: 缺少拒绝测试 ${expected}；门禁必须能对代表性违规变红（.harness/AGENTS.md 变更协议）`,
      );
    }
  }
  return missing;
}

function verify() {
  const scriptNames = readdirSync(SCRIPTS_ROOT).filter((name) => name.endsWith('.mjs'));
  const checkNames = scriptNames
    .filter((name) => name.startsWith('check-') && !name.endsWith('.test.mjs'))
    .sort();
  const checkTestNames = scriptNames
    .filter((name) => name.startsWith('check-') && name.endsWith('.test.mjs'))
    .sort();

  const failures = missingRejectionTests(checkNames, checkTestNames);
  if (failures.length > 0) {
    const newline = String.fromCharCode(10);
    throw new Error(`门禁拒绝测试契约失败：${newline}- ${failures.join(`${newline}- `)}`);
  }
  console.log(`门禁拒绝测试契约通过：${checkNames.length} 个门禁均有同名拒绝测试`);
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try {
    verify();
  } catch (error) {
    console.error(error.message);
    process.exitCode = 1;
  }
}
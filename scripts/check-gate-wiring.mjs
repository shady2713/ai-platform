import { existsSync, readFileSync, readdirSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const REPO_ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const SCRIPTS_ROOT = join(REPO_ROOT, 'scripts');
const PROVIDERS = ['.harness/verify.sh', '.harness/verify.ps1'];
const WIRING_FILES = [
  '.harness/verify.sh',
  '.harness/verify.ps1',
  'lefthook.yml',
  '.github/workflows/verify.yml',
];

function readIfExists(relativePath) {
  const absolutePath = join(REPO_ROOT, relativePath);
  return existsSync(absolutePath) ? readFileSync(absolutePath, 'utf8') : '';
}

function workflowFiles() {
  const directory = join(REPO_ROOT, '.github/workflows');
  if (!existsSync(directory)) {
    return [];
  }
  return readdirSync(directory)
    .filter((name) => name.endsWith('.yml') || name.endsWith('.yaml'))
    .map((name) => `.github/workflows/${name}`);
}

/** scripts/ 下的门禁脚本与它们的测试。 */
export function gateScripts(scriptNames) {
  const isTest = (name) => name.endsWith('.test.mjs');
  return {
    checks: scriptNames.filter((name) => name.startsWith('check-') && !isTest(name)),
    helpers: scriptNames.filter(
      (name) => name.endsWith('.mjs') && !isTest(name) && !name.startsWith('check-'),
    ),
    tests: scriptNames.filter(isTest),
  };
}

/** 只识别 provider 中实际的 node 调用；注释、import 和测试目标不替代门禁执行。 */
export function referencedScripts(providerText, scriptNames) {
  const referenced = new Set();
  const known = new Set(scriptNames);
  for (const rawLine of providerText.split(/\r?\n/)) {
    const line = rawLine.trim();
    if (!/^(?:node\s|Invoke-External\s+['"]node['"]\s)/.test(line)) {
      continue;
    }
    for (const match of line.matchAll(/scripts[\\/]([\w.-]+\.mjs)/g)) {
      const name = match[1];
      if (known.has(name) && name.endsWith('.test.mjs') === line.includes('--test')) {
        referenced.add(name);
      }
    }
  }
  return referenced;
}

export function providerParityFailures(shReferences, ps1References) {
  const failures = [];
  for (const name of shReferences) {
    if (!ps1References.has(name)) {
      failures.push(
        `${name}: 只在 .harness/verify.sh 中接线，verify.ps1 未接线（双 provider 必须对齐）`,
      );
    }
  }
  for (const name of ps1References) {
    if (!shReferences.has(name)) {
      failures.push(
        `${name}: 只在 .harness/verify.ps1 中接线，verify.sh 未接线（双 provider 必须对齐）`,
      );
    }
  }
  return failures;
}

function verify() {
  const scriptNames = readdirSync(SCRIPTS_ROOT).filter((name) => name.endsWith('.mjs'));
  const groups = gateScripts(scriptNames);
  const providerTexts = PROVIDERS.map(readIfExists);
  const shReferences = referencedScripts(providerTexts[0], scriptNames);
  const ps1References = referencedScripts(providerTexts[1], scriptNames);

  const failures = [...providerParityFailures(shReferences, ps1References)];

  // 接线来源：harness 双 provider、CI workflow、提交钩子，以及其他脚本的内部调用。
  const wiringText = [...WIRING_FILES.map(readIfExists), ...workflowFiles().map(readIfExists)].join(
    '',
  );
  for (const name of groups.checks) {
    if (!shReferences.has(name) || !ps1References.has(name)) {
      failures.push(`${name}: 门禁必须在 Harness 双 provider 中直接执行，测试引用和注释不算接线`);
    }
  }

  for (const name of groups.helpers) {
    const internalText = [
      ...groups.checks,
      ...groups.helpers,
      ...readdirSync(SCRIPTS_ROOT).filter((source) => source.endsWith('.sh')),
    ]
      .filter((source) => source !== name)
      .map((source) => readIfExists(`scripts/${source}`))
      .join('');
    const referencedExternally = shReferences.has(name) || ps1References.has(name);
    const referencedElsewhere =
      wiringText.includes(`scripts/${name}`) ||
      internalText.includes(`scripts/${name}`) ||
      internalText.includes(name);
    if (!referencedExternally && !referencedElsewhere) {
      failures.push(
        `${name}: 未接线到任何门禁。孤立脚本要么接入 Harness 双 provider，要么删除（不得保留无效门禁）`,
      );
    }
  }

  for (const name of groups.tests) {
    if (!shReferences.has(name) || !ps1References.has(name)) {
      failures.push(`${name}: 测试未在 Harness 双 provider 中通过 node --test 执行`);
    }
  }

  if (failures.length > 0) {
    const newline = String.fromCharCode(10);
    throw new Error(`门禁接线契约失败：${newline}- ${failures.join(`${newline}- `)}`);
  }
  console.log(
    `门禁接线契约通过：check 脚本 ${groups.checks.length} 个、辅助脚本 ${groups.helpers.length} 个、` +
      `测试 ${groups.tests.length} 个，双 provider 接线对齐`,
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

import { execFileSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import { dirname, join, relative, resolve, sep } from 'node:path';
import { fileURLToPath } from 'node:url';

import { ESLint } from 'eslint';

const WORKSPACE_ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const require = createRequire(
  join(WORKSPACE_ROOT, 'internal/lint-configs/eslint-config/package.json'),
);
const vueEslintParser = require('vue-eslint-parser');

const typescriptEslintPlugin = require('@typescript-eslint/eslint-plugin');
const typescriptEslintParser = require('@typescript-eslint/parser');

const REPO_ROOT = resolve(WORKSPACE_ROOT, '../..');
const FRONTEND_RELATIVE_ROOT = normalizePath(
  relative(REPO_ROOT, WORKSPACE_ROOT),
);
const BASELINE_RELATIVE_PATH = 'docs/contracts/explicit-any-baseline.json';
const BASELINE_PATH = join(REPO_ROOT, BASELINE_RELATIVE_PATH);
const RULE_ID = '@typescript-eslint/no-explicit-any';
const SOURCE_PATTERNS = ['**/*.{cts,mts,ts,tsx,vue}'];
const SOURCE_EXTENSION_PATTERN = /\.(?:cts|mts|ts|tsx|vue)$/;

function normalizePath(value) {
  return value.split(sep).join('/');
}

function baseRef() {
  const candidate = process.env.COVERAGE_BASE_SHA?.trim() || 'HEAD';
  return /^0+$/.test(candidate) ? 'HEAD' : candidate;
}

function readBaseline(content = readFileSync(BASELINE_PATH, 'utf8')) {
  const baseline = JSON.parse(content);
  if (
    baseline.version !== 1 ||
    baseline.rule !== RULE_ID ||
    !Number.isInteger(baseline.maximum) ||
    baseline.maximum < 0
  ) {
    throw new Error('explicit-any-baseline.json 结构无效');
  }
  return baseline;
}

function readBaseBaseline() {
  try {
    return readBaseline(
      execFileSync('git', ['show', `${baseRef()}:${BASELINE_RELATIVE_PATH}`], {
        cwd: REPO_ROOT,
        encoding: 'utf8',
        stdio: ['ignore', 'pipe', 'ignore'],
      }),
    );
  } catch {
    return null;
  }
}

function gitLines(args) {
  const output = execFileSync('git', args, {
    cwd: REPO_ROOT,
    encoding: 'utf8',
  });
  return output
    .split(/\r?\n/)
    .map((line) => normalizePath(line.trim()))
    .filter(Boolean);
}

function changedFrontendSources() {
  const tracked = gitLines([
    'diff',
    '--name-only',
    '--diff-filter=ACMR',
    baseRef(),
    '--',
    FRONTEND_RELATIVE_ROOT,
  ]);
  const untracked = gitLines([
    'ls-files',
    '--others',
    '--exclude-standard',
    '--',
    FRONTEND_RELATIVE_ROOT,
  ]);
  return [...new Set([...tracked, ...untracked])].filter((sourcePath) =>
    SOURCE_EXTENSION_PATTERN.test(sourcePath),
  );
}

function baseContent(sourcePath) {
  try {
    return execFileSync('git', ['show', `${baseRef()}:${sourcePath}`], {
      cwd: REPO_ROOT,
      encoding: 'utf8',
      stdio: ['ignore', 'pipe', 'ignore'],
    });
  } catch {
    return null;
  }
}

/**
 * 独立于项目 eslint 配置构造计数实例：CI 与本地的 pnpm 解析差异会让同一插件键
 * 指向不同实例，合并项目配置时触发 "Cannot redefine plugin" 崩溃。这里的配置
 * 完全自包含（parser/插件/规则都显式声明），不与项目配置发生合并。
 */
function createEslint() {
  return new ESLint({
    overrideConfigFile: true,
    overrideConfig: [
      {
        files: ['**/*.{cts,mts,ts,tsx}'],
        languageOptions: { parser: typescriptEslintParser },
        plugins: {
          '@typescript-eslint': typescriptEslintPlugin,
        },
        rules: {
          [RULE_ID]: 'error',
        },
      },
      {
        files: ['**/*.vue'],
        languageOptions: {
          parser: vueEslintParser,
          parserOptions: {
            parser: typescriptEslintParser,
            ecmaVersion: 'latest',
            sourceType: 'module',
          },
        },
        plugins: {
          '@typescript-eslint': typescriptEslintPlugin,
        },
        rules: {
          [RULE_ID]: 'error',
        },
      },
    ],
    ignorePatterns: [
      '**/node_modules/**',
      '**/dist/**',
      '**/coverage/**',
      '**/.turbo/**',
    ],
    warnIgnored: false,
  });
}

export function countRuleMessages(result) {
  return result.messages.filter((message) => message.ruleId === RULE_ID).length;
}

export function ratchetFailures(current, maximum) {
  return current > maximum
    ? [`显式 any 从基线 ${maximum} 增加到 ${current}`]
    : [];
}

export function baselineFailures(candidate, previous) {
  return candidate > previous
    ? [`显式 any 基线上限从 ${previous} 上调为 ${candidate}`]
    : [];
}

export function fileRatchetFailures(sourcePath, current, previous) {
  return current > previous
    ? [`${sourcePath}: 显式 any 从 ${previous} 增加到 ${current}`]
    : [];
}

export function countReport(counts) {
  return [...counts.entries()]
    .filter(([, count]) => count > 0)
    .map(([sourcePath, count]) => ({ count, sourcePath }))
    .toSorted(
      (left, right) =>
        right.count - left.count ||
        left.sourcePath.localeCompare(right.sourcePath),
    );
}

async function currentCounts(eslint) {
  const results = await eslint.lintFiles(SOURCE_PATTERNS);
  return new Map(
    results.map((result) => [
      normalizePath(relative(REPO_ROOT, result.filePath)),
      countRuleMessages(result),
    ]),
  );
}

async function baseCount(eslint, sourcePath) {
  const content = baseContent(sourcePath);
  if (content === null) {
    return 0;
  }
  const [result] = await eslint.lintText(content, {
    filePath: join(REPO_ROOT, sourcePath),
  });
  return result === undefined ? 0 : countRuleMessages(result);
}

async function verify() {
  const eslint = createEslint();
  const counts = await currentCounts(eslint);
  const count = [...counts.values()].reduce((total, value) => total + value, 0);
  if (process.argv.includes('--measure')) {
    console.log(`显式 any 当前数量：${count}`);
    return;
  }
  if (process.argv.includes('--report')) {
    const report = countReport(counts);
    console.log(`显式 any 当前数量：${count}，涉及 ${report.length} 个文件`);
    for (const { count: fileCount, sourcePath } of report) {
      console.log(`${String(fileCount).padStart(4)}  ${sourcePath}`);
    }
    return;
  }

  const baseline = readBaseline();
  const failures = ratchetFailures(count, baseline.maximum);
  for (const sourcePath of changedFrontendSources()) {
    const current = counts.get(sourcePath) ?? 0;
    if (current > 0) {
      failures.push(
        ...fileRatchetFailures(
          sourcePath,
          current,
          await baseCount(eslint, sourcePath),
        ),
      );
    }
  }
  const previous = readBaseBaseline();
  if (previous !== null) {
    failures.push(...baselineFailures(baseline.maximum, previous.maximum));
  }
  if (failures.length > 0) {
    throw new Error(`显式 any 棘轮失败：\n- ${failures.join('\n- ')}`);
  }
  console.log(`显式 any 棘轮通过：当前 ${count}，上限 ${baseline.maximum}`);
}

if (
  process.argv[1] &&
  resolve(process.argv[1]) === fileURLToPath(import.meta.url)
) {
  await verify();
}

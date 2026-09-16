import { execFileSync } from 'node:child_process';
import { existsSync, readFileSync } from 'node:fs';
import { extname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const REPO_ROOT = resolve(fileURLToPath(new URL('..', import.meta.url)));
const MAX_SOURCE_LINES = 800;
const SOURCE_PREFIXES = [
  '.harness/',
  'scripts/',
  '后端代码/basic-framework-boot/',
  '前端代码/basic-framework-admin/',
];
const SOURCE_EXTENSIONS = new Set([
  '.cjs',
  '.java',
  '.js',
  '.jsx',
  '.mjs',
  '.ps1',
  '.sh',
  '.ts',
  '.tsx',
  '.vue',
]);
const COMMENT_DEBT_MARKER =
  /^\s*(?:\/\/|\/\*+|\*|<!--|#(?!\!)|--)\s*(TODO|FIXME|XXX)\b(.*)/i;
const OWNER_OR_ISSUE_REFERENCE =
  /(?:\bowner\s*[:=]\s*[\p{L}\p{N}._-]+|负责人\s*[:：=]\s*[\p{L}\p{N}._-]+|@[A-Za-z0-9][\w.-]*|#[1-9]\d*|\b[A-Z][A-Z0-9]+-\d+\b|https?:\/\/\S+)/u;

function normalizedPath(value) {
  return value.replaceAll('\\', '/');
}

export function countLines(content) {
  if (content.length === 0) {
    return 0;
  }
  const newlineCount = (content.match(/\n/g) ?? []).length;
  return newlineCount + (content.endsWith('\n') ? 0 : 1);
}

export function isSvgArtworkVue(sourcePath, content) {
  return (
    sourcePath.endsWith('.vue') &&
    !/<script\b/i.test(content) &&
    /<template[\s>][\s\S]*?<svg[\s>]/i.test(content)
  );
}

export function sourceSizeFailures(entries, maximum = MAX_SOURCE_LINES) {
  const failures = [];
  for (const { path, content } of entries) {
    if (isSvgArtworkVue(path, content)) {
      continue;
    }
    const lines = countLines(content);
    if (lines > maximum) {
      failures.push(`${path}: ${lines} 行，超过上限 ${maximum} 行`);
    }
  }
  return failures;
}

export function technicalDebtMarkerFailures(entries) {
  const failures = [];
  for (const { path, content } of entries) {
    const lines = content.split(/\r?\n/);
    for (const [index, line] of lines.entries()) {
      const marker = line.match(COMMENT_DEBT_MARKER);
      if (marker && !OWNER_OR_ISSUE_REFERENCE.test(marker[2])) {
        failures.push(`${path}:${index + 1}: ${marker[1].toUpperCase()} 缺少负责人或问题引用`);
      }
    }
  }
  return failures;
}

function isGovernedSourcePath(sourcePath) {
  const path = normalizedPath(sourcePath);
  return (
    SOURCE_PREFIXES.some((prefix) => path.startsWith(prefix)) &&
    SOURCE_EXTENSIONS.has(extname(path).toLowerCase())
  );
}

function repositorySourceEntries() {
  const output = execFileSync(
    'git',
    ['ls-files', '-z', '--cached', '--others', '--exclude-standard'],
    { cwd: REPO_ROOT, encoding: 'utf8' },
  );
  return output
    .split('\0')
    .filter(Boolean)
    .map(normalizedPath)
    .filter(isGovernedSourcePath)
    .filter((path) => existsSync(resolve(REPO_ROOT, path)))
    .map((path) => ({ path, content: readFileSync(resolve(REPO_ROOT, path), 'utf8') }));
}

function verify() {
  const entries = repositorySourceEntries();
  const artworkCount = entries.filter(({ path, content }) =>
    isSvgArtworkVue(path, content),
  ).length;
  const failures = [
    ...sourceSizeFailures(entries),
    ...technicalDebtMarkerFailures(entries),
  ];
  if (failures.length > 0) {
    throw new Error(`源码质量契约失败：\n- ${failures.join('\n- ')}`);
  }
  console.log(
    `源码质量契约通过：检查 ${entries.length} 个源码文件，` +
      `静态 SVG Vue 图稿 ${artworkCount} 个，逻辑源码上限 ${MAX_SOURCE_LINES} 行`,
  );
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  verify();
}

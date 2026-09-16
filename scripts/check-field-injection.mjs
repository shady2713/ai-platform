import { execFileSync } from 'node:child_process';
import { existsSync, readFileSync, readdirSync } from 'node:fs';
import { dirname, join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { normalizePath, matchingParenthesis } from './gate-utils.mjs';

const REPO_ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const BACKEND_ROOT = join(REPO_ROOT, '后端代码/basic-framework-boot');
const BASELINE_RELATIVE_PATH = 'docs/contracts/field-injection-baseline.json';
const BASELINE_PATH = join(REPO_ROOT, BASELINE_RELATIVE_PATH);
const LEGACY_ANNOTATION_PATTERN = /@(Resource|Autowired|Inject)\b/g;
const JAVA_IDENTIFIER_CHARACTER = /[A-Za-z0-9_$]/;

function javaSources(root) {
  if (!existsSync(root)) {
    return [];
  }
  const result = [];
  for (const entry of readdirSync(root, { withFileTypes: true })) {
    const target = join(root, entry.name);
    if (entry.isDirectory()) {
      result.push(...javaSources(target));
    } else if (
      entry.isFile() &&
      entry.name.endsWith('.java') &&
      normalizePath(relative(BACKEND_ROOT, target)).includes('/src/main/java/')
    ) {
      result.push(target);
    }
  }
  return result;
}

export function stripJavaCommentsAndLiterals(source) {
  let result = '';
  let state = 'code';
  for (let index = 0; index < source.length; index += 1) {
    const current = source[index];
    const pair = source.slice(index, index + 2);
    const triple = source.slice(index, index + 3);
    if (state === 'code') {
      if (pair === '//') {
        state = 'line-comment';
        result += '  ';
        index += 1;
      } else if (pair === '/*') {
        state = 'block-comment';
        result += '  ';
        index += 1;
      } else if (triple === '\"\"\"') {
        state = 'text-block';
        result += '   ';
        index += 2;
      } else if (current === '\"') {
        state = 'string';
        result += ' ';
      } else if (current === "'") {
        state = 'character';
        result += ' ';
      } else {
        result += current;
      }
      continue;
    }
    if (state === 'line-comment') {
      if (current === '\n') {
        state = 'code';
        result += '\n';
      } else {
        result += ' ';
      }
      continue;
    }
    if (state === 'block-comment') {
      if (pair === '*/') {
        state = 'code';
        result += '  ';
        index += 1;
      } else {
        result += current === '\n' ? '\n' : ' ';
      }
      continue;
    }
    if (state === 'text-block') {
      if (triple === '\"\"\"') {
        state = 'code';
        result += '   ';
        index += 2;
      } else {
        result += current === '\n' ? '\n' : ' ';
      }
      continue;
    }
    if (current === '\\') {
      result += ' ';
      if (index + 1 < source.length) {
        result += source[index + 1] === '\n' ? '\n' : ' ';
        index += 1;
      }
    } else if (
      (state === 'string' && current === '\"') ||
      (state === 'character' && current === "'")
    ) {
      state = 'code';
      result += ' ';
    } else {
      result += current === '\n' ? '\n' : ' ';
    }
  }
  return result;
}

function skipWhitespace(source, index) {
  let cursor = index;
  while (cursor < source.length && /\s/.test(source[cursor])) {
    cursor += 1;
  }
  return cursor;
}

function isFieldDeclaration(candidate) {
  if (candidate.includes('{') || candidate.includes('}')) {
    return false;
  }
  const assignmentIndex = candidate.indexOf('=');
  const invocationIndex = candidate.indexOf('(');
  if (invocationIndex >= 0 && (assignmentIndex < 0 || invocationIndex < assignmentIndex)) {
    return false;
  }
  const declaration = assignmentIndex < 0 ? candidate : candidate.slice(0, assignmentIndex);
  return /\b[A-Za-z_$][\w$]*\s*(?:\[\s*\])?\s*$/.test(declaration);
}

export function countDirectValueFieldInjections(source) {
  const sanitized = stripJavaCommentsAndLiterals(source);
  let count = 0;
  let cursor = 0;
  while (cursor < sanitized.length) {
    const annotationIndex = sanitized.indexOf('@Value', cursor);
    if (annotationIndex < 0) {
      break;
    }
    const annotationEnd = annotationIndex + '@Value'.length;
    if (
      JAVA_IDENTIFIER_CHARACTER.test(sanitized[annotationIndex - 1] ?? '') ||
      JAVA_IDENTIFIER_CHARACTER.test(sanitized[annotationEnd] ?? '')
    ) {
      cursor = annotationEnd;
      continue;
    }
    const openingParenthesis = skipWhitespace(sanitized, annotationEnd);
    if (sanitized[openingParenthesis] !== '(') {
      cursor = annotationEnd;
      continue;
    }
    const closingParenthesis = matchingParenthesis(sanitized, openingParenthesis);
    if (closingParenthesis < 0) {
      break;
    }
    const statementEnd = sanitized.indexOf(';', closingParenthesis + 1);
    if (statementEnd >= 0 && isFieldDeclaration(sanitized.slice(closingParenthesis + 1, statementEnd).trim())) {
      count += 1;
    }
    cursor = closingParenthesis + 1;
  }
  return count;
}

export function countFieldInjectionAnnotations(source) {
  const sanitized = stripJavaCommentsAndLiterals(source);
  return [...sanitized.matchAll(LEGACY_ANNOTATION_PATTERN)].length + countDirectValueFieldInjections(source);
}

export function ratchetFailures(current, maximum) {
  return current > maximum
    ? [`字段注入注解从基线 ${maximum} 增加到 ${current}`]
    : [];
}

export function baselineFailures(candidate, previous) {
  return candidate > previous
    ? [`字段注入基线上限从 ${previous} 上调为 ${candidate}`]
    : [];
}

export function fileRatchetFailures(current, previous) {
  const failures = [];
  for (const [sourcePath, count] of Object.entries(current)) {
    const oldCount = previous[sourcePath] ?? 0;
    if (count > oldCount) {
      failures.push(`${sourcePath}: 字段注入注解从 ${oldCount} 增加到 ${count}`);
    }
  }
  return failures;
}

function baseRef() {
  const candidate = process.env.COVERAGE_BASE_SHA?.trim() || 'HEAD';
  return /^0+$/.test(candidate) ? 'HEAD' : candidate;
}

function readBaseline(content = readFileSync(BASELINE_PATH, 'utf8')) {
  const baseline = JSON.parse(content);
  if (
    baseline.version !== 1 ||
    !Array.isArray(baseline.annotations) ||
    !Number.isInteger(baseline.maximum) ||
    baseline.maximum < 0
  ) {
    throw new Error('field-injection-baseline.json 结构无效');
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

function currentCounts() {
  const result = {};
  for (const sourcePath of javaSources(BACKEND_ROOT)) {
    const count = countFieldInjectionAnnotations(readFileSync(sourcePath, 'utf8'));
    if (count > 0) {
      result[normalizePath(relative(REPO_ROOT, sourcePath))] = count;
    }
  }
  return result;
}

function baseCounts(sourcePaths) {
  const result = {};
  for (const sourcePath of sourcePaths) {
    try {
      const content = execFileSync('git', ['show', `${baseRef()}:${sourcePath}`], {
        cwd: REPO_ROOT,
        encoding: 'utf8',
        stdio: ['ignore', 'pipe', 'ignore'],
      });
      result[sourcePath] = countFieldInjectionAnnotations(content);
    } catch {
      result[sourcePath] = 0;
    }
  }
  return result;
}

function verify() {
  const baseline = readBaseline();
  const counts = currentCounts();
  const count = Object.values(counts).reduce((total, value) => total + value, 0);
  const failures = ratchetFailures(count, baseline.maximum);
  failures.push(...fileRatchetFailures(counts, baseCounts(Object.keys(counts))));
  const previous = readBaseBaseline();
  if (previous !== null) {
    failures.push(...baselineFailures(baseline.maximum, previous.maximum));
  }
  if (failures.length > 0) {
    throw new Error(`字段注入棘轮失败：\n- ${failures.join('\n- ')}`);
  }
  console.log(`字段注入棘轮通过：当前 ${count}，上限 ${baseline.maximum}`);
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  verify();
}

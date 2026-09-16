import { readdirSync, readFileSync } from 'node:fs';
import { dirname, join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { normalizePath, matchingParenthesis } from './gate-utils.mjs';

const REPO_ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const BACKEND_ROOT = join(REPO_ROOT, '后端代码/basic-framework-boot');

function maskNonCode(source) {
  const chars = [...source];
  let state = 'code';
  for (let index = 0; index < chars.length; index += 1) {
    const current = chars[index];
    const next = chars[index + 1];
    if (state === 'code' && current === '/' && next === '/') {
      chars[index] = chars[index + 1] = ' ';
      state = 'line-comment';
      index += 1;
    } else if (state === 'code' && current === '/' && next === '*') {
      chars[index] = chars[index + 1] = ' ';
      state = 'block-comment';
      index += 1;
    } else if (state === 'code' && (current === '"' || current === "'")) {
      state = current === '"' ? 'string' : 'character';
      chars[index] = ' ';
    } else if (state === 'line-comment') {
      if (current === '\n') state = 'code';
      else chars[index] = ' ';
    } else if (state === 'block-comment') {
      chars[index] = current === '\n' ? '\n' : ' ';
      if (current === '*' && next === '/') {
        chars[index + 1] = ' ';
        state = 'code';
        index += 1;
      }
    } else if (state === 'string' || state === 'character') {
      chars[index] = current === '\n' ? '\n' : ' ';
      if (current === '\\') {
        if (index + 1 < chars.length) chars[index + 1] = ' ';
        index += 1;
      } else if (
        (state === 'string' && current === '"') ||
        (state === 'character' && current === "'")
      ) {
        state = 'code';
      }
    }
  }
  return chars.join('');
}

function matchingBrace(masked, openIndex) {
  let depth = 0;
  for (let index = openIndex; index < masked.length; index += 1) {
    if (masked[index] === '{') depth += 1;
    if (masked[index] === '}' && --depth === 0) return index;
  }
  return -1;
}

function splitTopLevelArguments(source, masked, start, end) {
  const result = [];
  let argumentStart = start;
  let round = 0;
  let square = 0;
  let curly = 0;
  for (let index = start; index < end; index += 1) {
    const current = masked[index];
    if (current === '(') round += 1;
    else if (current === ')') round -= 1;
    else if (current === '[') square += 1;
    else if (current === ']') square -= 1;
    else if (current === '{') curly += 1;
    else if (current === '}') curly -= 1;
    else if (current === ',' && round === 0 && square === 0 && curly === 0) {
      result.push({ code: source.slice(argumentStart, index).trim(), masked: masked.slice(argumentStart, index).trim() });
      argumentStart = index + 1;
    }
  }
  result.push({ code: source.slice(argumentStart, end).trim(), masked: masked.slice(argumentStart, end).trim() });
  return result;
}

export function unsafeExceptionLogCalls(source) {
  const masked = maskNonCode(source);
  const catchVariables = new Set(
    [...masked.matchAll(/\bcatch\s*\(\s*[\w.$<>?, ]+\s+(\w+)\s*\)/g)].map(
      (match) => match[1],
    ),
  );
  const failures = [];
  const callPattern = /\b(?:log|logger|LOGGER)\.(trace|debug|info|warn|error)\s*\(/g;
  let match;
  while ((match = callPattern.exec(masked)) !== null) {
    const openIndex = masked.indexOf('(', match.index);
    const closeIndex = matchingParenthesis(masked, openIndex);
    if (closeIndex < 0) break;
    const arguments_ = splitTopLevelArguments(source, masked, openIndex + 1, closeIndex);
    for (const argument of arguments_) {
      const directCatchVariable = catchVariables.has(argument.masked);
      if (directCatchVariable) {
        failures.push({
          argument: argument.code,
          level: match[1],
          line: source.slice(0, match.index).split(/\r?\n/).length,
        });
      }
    }
    callPattern.lastIndex = closeIndex + 1;
  }
  return failures;
}

export function unsafeCaughtExceptionMessageAccesses(source) {
  const masked = maskNonCode(source);
  const failures = [];
  const reportedIndexes = new Set();
  const catchPattern = /\bcatch\s*\(\s*[\w.$<>?, |]+\s+(\w+)\s*\)\s*\{/g;
  const accessorPattern = /\.\s*get(?:Localized)?Message\s*\(|\b(?:[\w$]+\s*\.\s*)*getRootCauseMessage\s*\(/g;
  let catchMatch;
  while ((catchMatch = catchPattern.exec(masked)) !== null) {
    const openBrace = masked.lastIndexOf('{', catchPattern.lastIndex - 1);
    const closeBrace = matchingBrace(masked, openBrace);
    if (closeBrace < 0) break;
    const body = masked.slice(openBrace + 1, closeBrace);
    let accessorMatch;
    while ((accessorMatch = accessorPattern.exec(body)) !== null) {
      const absoluteIndex = openBrace + 1 + accessorMatch.index;
      if (reportedIndexes.has(absoluteIndex)) continue;
      reportedIndexes.add(absoluteIndex);
      failures.push({
        accessor: accessorMatch[0].replace(/\s+/g, ''),
        line: source.slice(0, absoluteIndex).split(/\r?\n/).length,
      });
    }
    catchPattern.lastIndex = openBrace + 1;
  }
  return failures;
}

function productionJavaFiles(root, result = []) {
  for (const entry of readdirSync(root, { withFileTypes: true })) {
    if (entry.name === 'target') continue;
    const path = join(root, entry.name);
    if (entry.isDirectory()) productionJavaFiles(path, result);
    else if (entry.isFile() && entry.name.endsWith('.java') && normalizePath(path).includes('/src/main/java/')) {
      result.push(path);
    }
  }
  return result;
}

function verify() {
  const failures = productionJavaFiles(BACKEND_ROOT).flatMap((path) => {
    const source = readFileSync(path, 'utf8');
    const relativePath = normalizePath(relative(REPO_ROOT, path));
    return [
      ...unsafeExceptionLogCalls(source).map(
        (failure) => `${relativePath}:${failure.line} ${failure.level} 日志直接记录 ${failure.argument}`,
      ),
      ...unsafeCaughtExceptionMessageAccesses(source).map(
        (failure) => `${relativePath}:${failure.line} catch 块直接读取异常正文 ${failure.accessor}`,
      ),
    ];
  });
  for (const failure of failures) console.error(`FAIL ${failure}`);
  if (failures.length > 0) {
    throw new Error(`安全异常日志检查失败：${failures.length} 项`);
  }
  console.log('安全异常处理检查通过');
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  verify();
}

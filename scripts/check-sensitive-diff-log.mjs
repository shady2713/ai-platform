import { readdirSync, readFileSync } from 'node:fs';
import { dirname, join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { normalizePath } from './gate-utils.mjs';

const REPO_ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const BACKEND_ROOT = join(REPO_ROOT, '后端代码/basic-framework-boot');
const CONTACT_MASK_FUNCTION_BY_FIELD = new Map([
  ['email', 'EmailDesensitizeParseFunction.NAME'],
  ['emailaddress', 'EmailDesensitizeParseFunction.NAME'],
  ['mobile', 'MobileDesensitizeParseFunction.NAME'],
  ['mobilephone', 'MobileDesensitizeParseFunction.NAME'],
  ['phone', 'MobileDesensitizeParseFunction.NAME'],
  ['telephone', 'MobileDesensitizeParseFunction.NAME'],
]);

function javaSources(root, result = []) {
  for (const entry of readdirSync(root, { withFileTypes: true })) {
    if (entry.name === 'target') {
      continue;
    }
    const path = join(root, entry.name);
    if (entry.isDirectory()) {
      javaSources(path, result);
    } else if (
      entry.isFile() &&
      entry.name.endsWith('.java') &&
      normalizePath(relative(BACKEND_ROOT, path)).includes('/src/main/java/')
    ) {
      result.push({ path: normalizePath(relative(REPO_ROOT, path)), source: readFileSync(path, 'utf8') });
    }
  }
  return result;
}

function expectedMaskFunction(fieldName) {
  return CONTACT_MASK_FUNCTION_BY_FIELD.get(fieldName.toLowerCase());
}

export function sensitiveDiffLogFailures(source) {
  const failures = [];
  const lines = source.split(/\r?\n/);
  let previousFieldLine = -1;

  for (let index = 0; index < lines.length; index += 1) {
    const field = /^\s*private\s+[\w<>,?\[\] ]+\s+(\w+)\s*;/.exec(lines[index]);
    if (!field) {
      continue;
    }
    const fieldName = field[1];
    const expectedFunction = expectedMaskFunction(fieldName);
    const fieldBlock = lines.slice(previousFieldLine + 1, index + 1).join('\n');
    if (
      expectedFunction &&
      fieldBlock.includes('@DiffLogField') &&
      !fieldBlock.includes(`function = ${expectedFunction}`)
    ) {
      failures.push({ fieldName, expectedFunction, line: index + 1 });
    }
    previousFieldLine = index;
  }
  return failures;
}

export function scanSensitiveDiffLog(sources) {
  return sources.flatMap(({ path, source }) =>
    sensitiveDiffLogFailures(source).map(
      ({ fieldName, expectedFunction, line }) =>
        `${path}:${line} 的直接联系方式 ${fieldName} 必须使用 ${expectedFunction} 脱敏`,
    ),
  );
}

function verify() {
  const failures = scanSensitiveDiffLog(javaSources(BACKEND_ROOT));
  for (const failure of failures) {
    console.error(`FAIL ${failure}`);
  }
  if (failures.length > 0) {
    throw new Error(`操作审计联系方式脱敏检查失败：${failures.length} 项`);
  }
  console.log('操作审计联系方式脱敏检查通过');
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  verify();
}

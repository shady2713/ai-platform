import { readdirSync, readFileSync } from 'node:fs';
import { dirname, join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { normalizePath } from './gate-utils.mjs';

const REPO_ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const BACKEND_ROOT = join(REPO_ROOT, '后端代码/basic-framework-boot');

const SENSITIVE_NAME = /(password|secret|token|credential|recoverycode|apikey|accesskey)/i;
const DIRECT_CONTACT_FIELD_NAMES = new Set([
  'email',
  'emailaddress',
  'mobile',
  'mobilephone',
  'phone',
  'telephone',
]);
const SAFE_MATCHING_NAMES = new Set([
  'accessSecretConfigured',
  'apiSecretConfigured',
  'passwordEncoderLength',
  'tokenHeader',
]);
const AUTH_SMS_SCOPES = [
  '/controller/admin/auth/vo/',
  '/service/auth/dto/',
  '/service/sms/dto/',
];
const AUTH_SMS_SENSITIVE_FIELDS = new Set([
  'captchaVerification',
  'code',
  'createIp',
  'mobile',
  'usedIp',
]);
const AUTH_DATA_OBJECT_SCOPE = '/dal/dataobject/auth/';
const AUTH_DATA_OBJECT_SENSITIVE_FIELDS = new Set(['codeHash']);
const SMS_SENSITIVE_FIELDS_BY_FILE = new Map([
  [
    '/dal/dataobject/sms/SmsCodeDO.java',
    new Set(['code', 'createIp', 'mobile', 'usedIp']),
  ],
  [
    '/dal/dataobject/sms/SmsLogDO.java',
    new Set(['apiReceiveMsg', 'apiSendMsg', 'mobile', 'templateContent', 'templateParams']),
  ],
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

export function isSensitiveToStringField(sourcePath, fieldName) {
  if (DIRECT_CONTACT_FIELD_NAMES.has(fieldName.toLowerCase())) {
    return true;
  }
  if (!SAFE_MATCHING_NAMES.has(fieldName) && SENSITIVE_NAME.test(fieldName)) {
    return true;
  }
  if (
    AUTH_SMS_SCOPES.some((scope) => sourcePath.includes(scope)) &&
    AUTH_SMS_SENSITIVE_FIELDS.has(fieldName)
  ) {
    return true;
  }
  if (
    sourcePath.includes(AUTH_DATA_OBJECT_SCOPE) &&
    AUTH_DATA_OBJECT_SENSITIVE_FIELDS.has(fieldName)
  ) {
    return true;
  }
  return [...SMS_SENSITIVE_FIELDS_BY_FILE].some(
    ([filePath, fields]) => sourcePath.endsWith(filePath) && fields.has(fieldName),
  );
}

export function classToStringExclusions(source) {
  const classIndex = source.search(/\bclass\s+\w+/);
  const header = classIndex < 0 ? source : source.slice(0, classIndex);
  const excluded = new Set();
  for (const annotation of header.matchAll(/@ToString\s*\(([\s\S]*?)\)/g)) {
    const values = /exclude\s*=\s*\{([\s\S]*?)\}/.exec(annotation[1])?.[1] ?? '';
    for (const field of values.matchAll(/"([A-Za-z][A-Za-z0-9]*)"/g)) {
      excluded.add(field[1]);
    }
  }
  return excluded;
}

export function sensitiveToStringFailures(sourcePath, source) {
  if (!/^\s*@Data\s*$/m.test(source)) {
    return [];
  }
  const failures = [];
  const classExclusions = classToStringExclusions(source);
  const lines = source.split(/\r?\n/);
  let previousFieldLine = -1;

  for (let index = 0; index < lines.length; index += 1) {
    const field = /^\s*private\s+[\w<>,?\[\] ]+\s+(\w+)\s*;/.exec(lines[index]);
    if (!field) {
      continue;
    }
    const fieldName = field[1];
    const fieldBlock = lines.slice(previousFieldLine + 1, index + 1).join('\n');
    if (
      isSensitiveToStringField(sourcePath, fieldName) &&
      !fieldBlock.includes('@ToString.Exclude') &&
      !classExclusions.has(fieldName)
    ) {
      failures.push({ fieldName, line: index + 1 });
    }
    previousFieldLine = index;
  }
  return failures;
}

export function scanSensitiveToString(sources) {
  return sources.flatMap(({ path, source }) =>
    sensitiveToStringFailures(path, source).map(
      ({ fieldName, line }) => `${path}:${line} 的敏感字段 ${fieldName} 缺少 @ToString.Exclude`,
    ),
  );
}

function verify() {
  const failures = scanSensitiveToString(javaSources(BACKEND_ROOT));
  for (const failure of failures) {
    console.error(`FAIL ${failure}`);
  }
  if (failures.length > 0) {
    throw new Error(`敏感字段 toString 检查失败：${failures.length} 项`);
  }
  console.log('敏感字段 toString 检查通过');
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  verify();
}

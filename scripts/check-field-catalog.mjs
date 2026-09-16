#!/usr/bin/env node
// 字段契约目录漂移检查（契约语义见 docs/contracts/README.md）
// 检查 docs/contracts/field-catalog.yaml：
//   a) 目录自身合法：必填键、minLength <= maxLength、正则可被 JS RegExp 编译、
//      共享测试向量与登记的现状规则自洽；
//   b) 前端实现（field-rules.ts 正则常量、视图内联规则）与目录登记值一致；
//   c) 后端实现（ValidationUtils 正则常量、VO 上的 @Size）与目录登记值一致；
//   d) 列宽交叉校验：databaseType 可解析出 varchar(N) 时，目录 maxLength 与
//      backend_size 实现的 max 均不得超过 N（列宽不小于 API 上限）。
// 目录记录的是代码现状值（含 status: drift 的字段）；本脚本只保证
// “代码 == 目录现状值”，双端迁移到 plan 目标值由后续切片完成。
// 任何不一致输出明细并以非零退出。零依赖，CI hygiene 阶段直接 node 运行。

import { readFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { parseYaml } from './gate-utils.mjs';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const CATALOG_PATH = 'docs/contracts/field-catalog.yaml';

const errors = [];
const passed = [];

function fail(message) {
  errors.push(message);
}

function pass(message) {
  passed.push(message);
}

// ---------- YAML 子集解析 ----------
// 仅支持本目录使用的子集：2 空格缩进、块映射、块序列、单行 flow 序列、
// 单/双引号与 plain 标量、int/boolean/null、整行与行尾 # 注释。
// 不支持多行标量、锚点、flow 映射；新增写法超出子集时请先扩展解析器。

// ---------- 目录自身校验 ----------

const REQUIRED_FIELD_KEYS = [
  'id', 'semanticType', 'status', 'required', 'nullable', 'normalization',
  'lengthUnit', 'minLength', 'maxLength', 'characterPolicy', 'format',
  'examples', 'sensitiveClass', 'masking', 'databaseType', 'indexPolicy',
  'uniquenessScope', 'frontendWidget', 'backendValidator', 'vectorMode',
  'implementations', 'sharedTestVectors',
];
const VALID_STATUS = new Set(['aligned', 'drift']);
const VALID_SENSITIVE = new Set(['public', 'internal', 'pii', 'pii_high', 'credential']);
const VALID_VECTOR_MODES = new Set(['regex', 'length', 'algorithm']);
const VALID_IMPL_KINDS = new Set(['frontend_regex', 'backend_regex', 'backend_size', 'source_contains']);
const IDENTIFIER_REGEX = /^[A-Za-z0-9_]+$/;

function isInt(value) {
  return typeof value === 'number' && Number.isInteger(value);
}

function compileRegex(pattern, flags, label) {
  try {
    return new RegExp(pattern, flags);
  } catch (error) {
    fail(`${label} 正则无法被 JS RegExp 编译：${pattern}（${error.message}）`);
    return null;
  }
}

function validateFieldShape(field, index) {
  const label = field && typeof field.id === 'string' ? field.id : `fields[${index}]`;
  if (!field || typeof field !== 'object' || Array.isArray(field)) {
    fail(`${label} 不是映射`);
    return null;
  }
  for (const key of REQUIRED_FIELD_KEYS) {
    if (!Object.hasOwn(field, key)) {
      fail(`${label} 缺少必填键 ${key}`);
    }
  }
  if (typeof field.id !== 'string' || !IDENTIFIER_REGEX.test(field.id)) {
    fail(`${label} id 必须是非空标识符字符串`);
  }
  for (const key of ['required', 'nullable']) {
    if (typeof field[key] !== 'boolean') {
      fail(`${label} ${key} 必须是 boolean`);
    }
  }
  if (!VALID_STATUS.has(field.status)) {
    fail(`${label} status 必须是 aligned 或 drift，当前：${String(field.status)}`);
  }
  if (field.status === 'drift' && (!field.target || typeof field.target !== 'object')) {
    fail(`${label} status 为 drift 时必须附 target 块`);
  }
  if (!VALID_SENSITIVE.has(field.sensitiveClass)) {
    fail(`${label} sensitiveClass 非法：${String(field.sensitiveClass)}`);
  }
  if (!VALID_VECTOR_MODES.has(field.vectorMode)) {
    fail(`${label} vectorMode 非法：${String(field.vectorMode)}`);
  }
  for (const key of ['minLength', 'maxLength']) {
    if (field[key] !== null && !isInt(field[key])) {
      fail(`${label} ${key} 必须是整数或 null`);
    }
  }
  if (isInt(field.minLength) && isInt(field.maxLength) && field.minLength > field.maxLength) {
    fail(`${label} minLength(${field.minLength}) > maxLength(${field.maxLength})`);
  }
  if (field.format !== null && typeof field.format === 'string') {
    compileRegex(field.format, '', `${label}.format`);
  } else if (field.format !== null) {
    fail(`${label} format 必须是字符串或 null`);
  }
  if (!Array.isArray(field.examples)) {
    fail(`${label} examples 必须是数组`);
  }
  const vectors = field.sharedTestVectors;
  if (!vectors || !Array.isArray(vectors.valid) || !Array.isArray(vectors.invalid)) {
    fail(`${label} sharedTestVectors 必须含 valid/invalid 数组`);
  }
  return label;
}

function validateImplementations(field, label) {
  const impls = field.implementations;
  if (impls === null) {
    fail(`${label} implementations 不允许为 null（无代码约束的字段用 source_contains 登记数据库列等现状依据）`);
    return;
  }
  if (typeof impls !== 'object' || Array.isArray(impls) || Object.keys(impls).length === 0) {
    fail(`${label} implementations 必须是非空映射`);
    return;
  }
  for (const [name, impl] of Object.entries(impls)) {
    const implLabel = `${label}.implementations.${name}`;
    if (!impl || typeof impl !== 'object') {
      fail(`${implLabel} 必须是映射`);
      continue;
    }
    if (!VALID_IMPL_KINDS.has(impl.kind)) {
      fail(`${implLabel} kind 非法：${String(impl.kind)}`);
      continue;
    }
    if (typeof impl.file !== 'string' || !impl.file) {
      fail(`${implLabel} 缺少 file`);
    }
    if ((impl.kind === 'frontend_regex' || impl.kind === 'backend_regex')) {
      if (typeof impl.constant !== 'string' || !IDENTIFIER_REGEX.test(impl.constant)) {
        fail(`${implLabel} constant 必须是标识符字符串`);
      }
      if (typeof impl.pattern !== 'string') {
        fail(`${implLabel} 缺少 pattern`);
      } else {
        compileRegex(impl.pattern, impl.kind === 'frontend_regex' ? String(impl.flags ?? '') : '', implLabel);
      }
    }
    if (impl.kind === 'backend_size' && !isInt(impl.max)) {
      fail(`${implLabel} backend_size 需要整数 max`);
    }
    if (impl.kind === 'source_contains' && typeof impl.text !== 'string') {
      fail(`${implLabel} source_contains 需要 text`);
    }
  }
}

// 非字符串向量（null）属于“传输可空”语义，由 required/nullable 层处理，
// 不参与正则/长度比对；空串是字符串，正常参与。
function checkVectors(field, label) {
  const vectors = field.sharedTestVectors;
  if (!vectors || !Array.isArray(vectors.valid) || !Array.isArray(vectors.invalid)) {
    return;
  }
  if (field.vectorMode === 'algorithm') {
    pass(`${label} 测试向量由双端语义验证器持有（algorithm），仅校验正则编译`);
    return;
  }
  if (field.vectorMode === 'regex') {
    const impls = Object.values(field.implementations ?? {});
    const frontend = impls.find((impl) => impl.kind === 'frontend_regex');
    const pattern = frontend ? frontend.pattern : field.format;
    const flags = frontend ? String(frontend.flags ?? '') : '';
    if (typeof pattern !== 'string') {
      fail(`${label} vectorMode=regex 但找不到可比对的正则`);
      return;
    }
    const regex = compileRegex(pattern, flags, `${label} 向量比对`);
    if (!regex) {
      return;
    }
    for (const value of vectors.valid) {
      if (typeof value === 'string' && !regex.test(value)) {
        fail(`${label} 正例未通过登记正则：${JSON.stringify(value)}`);
      }
    }
    for (const value of vectors.invalid) {
      if (typeof value === 'string' && regex.test(value)) {
        fail(`${label} 反例被登记正则接受：${JSON.stringify(value)}`);
      }
    }
    return;
  }
  if (field.vectorMode === 'length') {
    if (field.lengthUnit !== 'utf16_code_unit') {
      fail(`${label} length 向量比对按 utf16 码元计数，lengthUnit=${String(field.lengthUnit)} 不支持`);
      return;
    }
    if (!isInt(field.minLength) || !isInt(field.maxLength)) {
      fail(`${label} vectorMode=length 需要整数 minLength/maxLength`);
      return;
    }
    for (const value of vectors.valid) {
      if (typeof value !== 'string') {
        continue;
      }
      if (value.length < field.minLength || value.length > field.maxLength) {
        fail(`${label} 正例长度越界（${value.length} 码元，允许 ${field.minLength}-${field.maxLength}）`);
      }
    }
    for (const value of vectors.invalid) {
      if (typeof value !== 'string') {
        continue;
      }
      if (value.length >= field.minLength && value.length <= field.maxLength) {
        fail(`${label} 反例长度在允许区间内（${value.length} 码元）`);
      }
    }
  }
}

// ---------- 列宽交叉校验 ----------

// 数据库列宽必须不小于 API 上限。databaseType 可解析出
// varchar(N) 时，目录 maxLength 与 backend_size 实现的 max 都不得超 N；
// databaseType 为「无」等不可解析值时跳过（无列可校验）。
function checkColumnWidth(field, label) {
  const match = /varchar\((\d+)\)/i.exec(String(field.databaseType ?? ''));
  if (!match) {
    return;
  }
  const columnWidth = Number.parseInt(match[1], 10);
  if (isInt(field.maxLength)) {
    if (field.maxLength > columnWidth) {
      fail(`${label} maxLength(${field.maxLength}) 超过数据库列宽 varchar(${columnWidth})`);
    } else {
      pass(`${label} maxLength(${field.maxLength}) <= 列宽 varchar(${columnWidth})`);
    }
  }
  for (const [name, impl] of Object.entries(field.implementations ?? {})) {
    if (impl && impl.kind === 'backend_size' && isInt(impl.max)) {
      if (impl.max > columnWidth) {
        fail(`${label}.implementations.${name} @Size(max = ${impl.max}) 超过数据库列宽 varchar(${columnWidth})`);
      } else {
        pass(`${label}.implementations.${name} @Size(max = ${impl.max}) <= 列宽 varchar(${columnWidth})`);
      }
    }
  }
}

// ---------- 代码漂移检查 ----------

const fileCache = new Map();

function readSource(relPath, label) {
  if (!fileCache.has(relPath)) {
    try {
      fileCache.set(relPath, readFileSync(join(ROOT, relPath), 'utf8'));
    } catch {
      fileCache.set(relPath, null);
    }
  }
  const source = fileCache.get(relPath);
  if (source === null) {
    fail(`${label} 引用的文件不存在：${relPath}`);
  }
  return source;
}

function extractFrontendRegex(source, constant) {
  const regex = new RegExp(`const\\s+${constant}\\s*=\\s*/((?:\\\\.|[^/\\\\])+)/([a-z]*)\\s*;`);
  const match = regex.exec(source);
  return match ? { pattern: match[1], flags: match[2] } : null;
}

function unescapeJavaLiteral(raw) {
  let out = '';
  for (let i = 0; i < raw.length; i += 1) {
    if (raw[i] !== '\\') {
      out += raw[i];
      continue;
    }
    i += 1;
    const esc = raw[i];
    if (esc === 'n') out += '\n';
    else if (esc === 't') out += '\t';
    else if (esc === 'r') out += '\r';
    else if (esc === '"' || esc === '\\') out += esc;
    else out += '\\' + esc;
  }
  return out;
}

function extractBackendRegex(source, constant) {
  const regex = new RegExp(`${constant}\\s*=\\s*Pattern\\.compile\\("((?:\\\\.|[^"\\\\])*)"\\)`);
  const match = regex.exec(source);
  return match ? { pattern: unescapeJavaLiteral(match[1]) } : null;
}

function checkImplementation(field, name, impl) {
  const label = `${field.id}.implementations.${name}`;
  if (!impl || typeof impl !== 'object' || typeof impl.file !== 'string') {
    return;
  }
  const source = readSource(impl.file, label);
  if (source === null) {
    return;
  }
  if (impl.kind === 'frontend_regex') {
    const actual = extractFrontendRegex(source, impl.constant);
    if (!actual) {
      fail(`${label} 在 ${impl.file} 中找不到常量 ${impl.constant} 的正则定义`);
      return;
    }
    const expectedFlags = String(impl.flags ?? '');
    if (actual.pattern !== impl.pattern || actual.flags !== expectedFlags) {
      fail(
        `${label} 与代码不一致：目录 /${impl.pattern}/${expectedFlags}，`
        + `代码 /${actual.pattern}/${actual.flags}（${impl.file} ${impl.constant}）`,
      );
      return;
    }
    pass(`${label} == ${impl.file} ${impl.constant}`);
    return;
  }
  if (impl.kind === 'backend_regex') {
    const actual = extractBackendRegex(source, impl.constant);
    if (!actual) {
      fail(`${label} 在 ${impl.file} 中找不到常量 ${impl.constant} 的 Pattern.compile 定义`);
      return;
    }
    if (actual.pattern !== impl.pattern) {
      fail(
        `${label} 与代码不一致：目录 ${impl.pattern}，`
        + `代码 ${actual.pattern}（${impl.file} ${impl.constant}）`,
      );
      return;
    }
    pass(`${label} == ${impl.file} ${impl.constant}`);
    return;
  }
  if (impl.kind === 'backend_size') {
    const needle = `@Size(max = ${impl.max}`;
    if (!source.includes(needle)) {
      fail(`${label} 在 ${impl.file} 中找不到 ${needle}…）注解`);
      return;
    }
    pass(`${label} == ${impl.file} @Size(max = ${impl.max})`);
    return;
  }
  if (impl.kind === 'source_contains') {
    if (typeof impl.text !== 'string' || !source.includes(impl.text)) {
      fail(`${label} 在 ${impl.file} 中找不到登记文本：${String(impl.text)}`);
      return;
    }
    pass(`${label} == ${impl.file} 包含登记文本`);
  }
}

// ---------- 主流程 ----------

function main() {
  let catalog;
  try {
    catalog = parseYaml(readFileSync(join(ROOT, CATALOG_PATH), 'utf8'));
  } catch (error) {
    fail(`目录解析失败：${error.message}`);
    report();
    return;
  }
  if (catalog.version !== 1) {
    fail(`version 必须为 1，当前：${String(catalog.version)}`);
  }
  if (!Array.isArray(catalog.fields) || catalog.fields.length === 0) {
    fail('fields 必须是非空数组');
    report();
    return;
  }

  const seenIds = new Set();
  catalog.fields.forEach((field, index) => {
    const label = validateFieldShape(field, index);
    if (!label) {
      return;
    }
    if (seenIds.has(label)) {
      fail(`${label} id 重复`);
    }
    seenIds.add(label);
    validateImplementations(field, label);
    checkVectors(field, label);
    checkColumnWidth(field, label);
    for (const [name, impl] of Object.entries(field.implementations ?? {})) {
      if (impl && typeof impl === 'object' && VALID_IMPL_KINDS.has(impl.kind)) {
        checkImplementation(field, name, impl);
      }
    }
  });

  report(catalog.fields);
}

function report(fields) {
  for (const message of passed) {
    console.log(`OK   ${message}`);
  }
  for (const message of errors) {
    console.error(`FAIL ${message}`);
  }
  const driftCount = fields ? fields.filter((field) => field.status === 'drift').length : 0;
  console.log(
    `字段 ${fields ? fields.length : 0} 个（drift ${driftCount} 个），`
    + `一致性比对通过 ${passed.length} 项，失败 ${errors.length} 项`,
  );
  if (errors.length > 0) {
    console.error('field catalog 漂移检查未通过');
    process.exitCode = 1;
  } else {
    console.log('field catalog 漂移检查通过');
  }
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main();
}

#!/usr/bin/env node
// 工程例外台账检查（例外语义见 docs/exceptions.yaml）
// 检查 docs/exceptions.yaml：
//   a) 台账自身合法：必填键齐全、status 合法、dueDate 为 YYYY-MM-DD；
//   b) status=active 的条目若已过 dueDate 则 FAIL（过期例外必须处理，
//      不允许无到期日或到期未移除）——CI 门禁，任何过期即非零退出。
// 零依赖，hygiene 阶段直接 node 运行，与 check-field-catalog.mjs 同理。

import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { parseYaml } from './gate-utils.mjs';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const CATALOG_PATH = 'docs/exceptions.yaml';
const TODAY = new Date();
TODAY.setHours(0, 0, 0, 0);

const REQUIRED_KEYS = ['id', 'title', 'owner', 'dueDate', 'removalCondition', 'status'];
const VALID_STATUS = new Set(['active', 'removed']);
const DATE_RE = /^\d{4}-\d{2}-\d{2}$/;

// ---------- 极简 YAML 子集解析（仅本台账使用的结构） ----------
// 支持：2 空格缩进、块映射、块序列（- id: ...）、plain/单引号标量、整行与行尾 # 注释。
// ---------- 台账校验 ----------

const errors = [];

function fail(message) {
  errors.push(message);
}

function parseDate(value, label, fail) {
  if (typeof value !== 'string' || !DATE_RE.test(value)) {
    fail(`${label} dueDate 必须是 YYYY-MM-DD 字符串`);
    return null;
  }
  const [y, m, d] = value.split('-').map(Number);
  return new Date(y, m - 1, d);
}

/**
 * 纯函数：给定台账内容与「今天」，返回全部违规项。
 *
 * <p>抽出为纯函数，使门禁自测能对代表性违规变红（见 .harness/AGENTS.md 的变更协议）。
 *
 * @param catalog 解析后的台账对象
 * @param today 用于到期判定的当前日期
 * @returns 违规描述数组；为空表示通过
 */
export function catalogFailures(catalog, today = TODAY) {
  const failures = [];
  const fail = (message) => failures.push(message);
  if (!catalog || typeof catalog !== 'object' || Array.isArray(catalog)) {
    return ['台账内容不是映射'];
  }
  if (catalog.version !== 1) {
    fail(`version 必须为 1，当前：${String(catalog.version)}`);
  }
  // 空台账合法：没有例外条目就没有过期项。
  const exceptions = Array.isArray(catalog.exceptions) ? catalog.exceptions : [];
  const seenIds = new Set();
  for (const [index, entry] of exceptions.entries()) {
    const label = entry && typeof entry.id === 'string' ? entry.id : `exceptions[${index}]`;
    if (!entry || typeof entry !== 'object' || Array.isArray(entry)) {
      fail(`${label} 不是映射`);
      continue;
    }
    for (const key of REQUIRED_KEYS) {
      if (entry[key] === undefined || entry[key] === null || (typeof entry[key] === 'string' && entry[key].trim() === '')) {
        fail(`${label} 缺少必填键 ${key}`);
      }
    }
    if (seenIds.has(entry.id)) {
      fail(`${label} id 重复`);
    }
    seenIds.add(entry.id);
    if (!VALID_STATUS.has(entry.status)) {
      fail(`${label} status 必须是 active 或 removed，当前：${String(entry.status)}`);
      continue;
    }
    if (entry.status !== 'active') {
      continue; // removed 条目不再检查到期
    }
    if (typeof entry.owner !== 'string' || entry.owner.trim() === '') {
      fail(`${label} owner 必填（负责人）`);
    }
    const due = parseDate(entry.dueDate, label, fail);
    if (due !== null && due.getTime() < today.getTime()) {
      fail(`${label} 例外已过期（dueDate=${entry.dueDate} < 今天），请处理并标记 removed`);
    }
  }
  return failures;
}

function main() {
  let catalog;
  try {
    catalog = parseYaml(readFileSync(join(ROOT, CATALOG_PATH), 'utf8'));
  } catch (error) {
    fail(`台账解析失败：${error.message}`);
    report();
    return;
  }
  for (const message of catalogFailures(catalog)) {
    fail(message);
  }
  report();
}

function report() {
  for (const message of errors) {
    console.error(`FAIL ${message}`);
  }
  console.log(`例外条目 ${readCount()} 条，过期/非法 ${errors.length} 项`);
  if (errors.length > 0) {
    console.error('例外台账检查未通过');
    process.exitCode = 1;
  } else {
    console.log('例外台账检查通过');
  }
}

function readCount() {
  try {
    const text = readFileSync(join(ROOT, CATALOG_PATH), 'utf8');
    return text.split(/\r?\n/).filter((line) => /^\s{2}- id:/.test(line)).length;
  } catch {
    return 0;
  }
}

main();

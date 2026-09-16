#!/usr/bin/env node
// Controller 边界参数校验门禁：业务模块 Controller 的 id 类参数必须 @Positive，
// ReqVO 入参必须 @Valid/@Validated，且声明参数约束的类必须带类级 @Validated。

import { readdirSync, readFileSync } from 'node:fs';
import { dirname, join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { normalizePath } from './gate-utils.mjs';

const REPO_ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const MODULE_ROOTS = [
  join(REPO_ROOT, '后端代码/basic-framework-boot/basic-framework-module-system'),
  join(REPO_ROOT, '后端代码/basic-framework-boot/basic-framework-module-infra'),
];

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

function lineOf(source, index) {
  return source.slice(0, index).split(/\r?\n/).length;
}

// 从 start 向前扫描，返回同一参数段的结束位置（顶层逗号或右括号）；
// 圆括号与泛型尖括号都计入深度，避免 Map<String, Long> 这类参数在泛型逗号处被截断
function parameterEnd(masked, start) {
  let depth = 0;
  let genericDepth = 0;
  for (let index = start; index < masked.length; index += 1) {
    const current = masked[index];
    if (current === '(') depth += 1;
    else if (current === ')') {
      if (depth === 0) return index;
      depth -= 1;
    } else if (current === '<') genericDepth += 1;
    else if (current === '>' && genericDepth > 0) genericDepth -= 1;
    else if (current === ',' && depth === 0 && genericDepth === 0) {
      return index;
    }
  }
  return masked.length;
}

// 从 index 向后扫描，返回参数段的起始分隔符位置（顶层左括号或逗号）
function parameterStart(masked, index) {
  let depth = 0;
  for (let position = index - 1; position >= 0; position -= 1) {
    const current = masked[position];
    if (current === ')') depth += 1;
    else if (current === '(') {
      if (depth === 0) return position;
      depth -= 1;
    } else if (current === ',' && depth === 0) {
      return position;
    }
  }
  return -1;
}

function stripAnnotations(segment) {
  let result = segment;
  let previous;
  do {
    previous = result;
    result = result.replace(/@(?:[A-Za-z_$][\w$]*\.)*[A-Za-z_$][\w$]*(\s*\([^()]*\))?\s*/g, '');
  } while (result !== previous);
  return result.trim();
}

const ID_PARAM_NAME = /^(id|[a-z][A-Za-z0-9]*Id|[a-z][a-z0-9]*(_[a-z0-9]+)*_id)$/;

// 剥离参数声明前导修饰符（如 final），使后续"类型+名称"匹配不被静默跳过
function stripModifiers(segment) {
  return segment.replace(/^(?:final\s+)+/, '');
}

// 规则 1a：@RequestParam/@PathVariable 绑定的 Long/long id 类参数必须带 @Positive
export function unguardedAnnotatedIdParams(source) {
  const masked = maskNonCode(source);
  const failures = [];
  for (const match of masked.matchAll(/@(?:[A-Za-z_$][\w$]*\.)*(RequestParam|PathVariable)\b/g)) {
    const end = parameterEnd(masked, match.index);
    // 从参数段起点取完整声明，兼容约束注解写在绑定注解之前的合法写法
    const start = parameterStart(masked, match.index);
    const segment = masked.slice(start < 0 ? match.index : start + 1, end);
    const bare = stripModifiers(stripAnnotations(segment))
      .replace(/\s+/g, ' ')
      .trim();
    const declaration = /^(.+?) +([A-Za-z_$][\w$]*)$/.exec(bare);
    const type = declaration ? declaration[1].replace(/ /g, '') : '';
    if (!declaration || !/^[A-Za-z0-9_.$<>[\],]+$/.test(type)) {
      // fail-closed：解析不出"类型+名称"的参数必须报错，不允许静默跳过校验
      failures.push({
        rule: 'declaration-parse',
        line: lineOf(source, match.index),
        detail: `无法解析 @${match[1]} 参数声明 "${bare}"，请调整为可识别的 类型+名称 形式`,
      });
      continue;
    }
    const name = declaration[2];
    if ((type === 'Long' || type === 'long') && ID_PARAM_NAME.test(name) && !/@Positive\b/.test(segment)) {
      failures.push({
        rule: 'id-positive',
        line: lineOf(source, match.index),
        detail: `@${match[1]} 绑定的 ${type} ${name} 缺少 @Positive`,
      });
    }
  }
  return failures;
}

// 规则 1b：完全无注解的 Long/long id 类参数不得出现在 Controller 方法签名中
// 已知局限：按全文件扫描，Controller 内私有辅助方法的同类参数也会被要求显式绑定
export function unguardedBareIdParams(source) {
  const masked = maskNonCode(source);
  const failures = [];
  for (const match of masked.matchAll(/[(,]\s*(?:final\s+)?[Ll]ong\s+([A-Za-z_$][\w$]*)\s*[,)]/g)) {
    const name = match[1];
    if (!ID_PARAM_NAME.test(name)) {
      continue;
    }
    failures.push({
      rule: 'id-positive',
      line: lineOf(source, match.index),
      detail: `Long ${name} 必须以 @RequestParam/@PathVariable 显式绑定并声明 @Positive`,
    });
  }
  return failures;
}

// 规则 2：ReqVO 入参必须带 @Valid 或 @Validated，否则 VO 上的约束不会触发
export function unguardedReqVoParams(source) {
  const masked = maskNonCode(source);
  const failures = [];
  for (const match of masked.matchAll(/(?<![\w.])[A-Z][\w]*ReqVO\s+[a-z][\w$]*\s*[,)]/g)) {
    const start = parameterStart(masked, match.index);
    const segment = start < 0 ? '' : masked.slice(start + 1, match.index);
    if (!/@(Valid|Validated)\b/.test(segment)) {
      failures.push({
        rule: 'vo-valid',
        line: lineOf(source, match.index),
        detail: `ReqVO 入参 ${match[0].trim()} 缺少 @Valid，约束不会触发`,
      });
    }
  }
  return failures;
}

// 规则 3：声明了方法参数约束（@Positive 等）的 Controller 必须有类级 @Validated 使其生效
export function missingClassValidated(source) {
  const failures = [];
  const masked = maskNonCode(source);
  if (masked.includes('@Positive') && !/^\s*@Validated\s*$/m.test(masked)) {
    failures.push({
      rule: 'class-validated',
      line: 1,
      detail: '声明了 @Positive 参数约束，但类上缺少 @Validated，约束不会生效',
    });
  }
  return failures;
}

export function controllerValidationFailures(source) {
  return [
    ...unguardedAnnotatedIdParams(source),
    ...unguardedBareIdParams(source),
    ...unguardedReqVoParams(source),
    ...missingClassValidated(source),
  ];
}

function controllerFiles(root, result = []) {
  for (const entry of readdirSync(root, { withFileTypes: true })) {
    if (entry.name === 'target') {
      continue;
    }
    const path = join(root, entry.name);
    if (entry.isDirectory()) {
      controllerFiles(path, result);
    } else if (
      entry.isFile() &&
      entry.name.endsWith('Controller.java') &&
      normalizePath(path).includes('/src/main/java/') &&
      normalizePath(path).includes('/controller/')
    ) {
      result.push(path);
    }
  }
  return result;
}

function main() {
  const failures = MODULE_ROOTS.flatMap((moduleRoot) =>
    controllerFiles(moduleRoot).flatMap((path) => {
      const source = readFileSync(path, 'utf8');
      const relativePath = normalizePath(relative(REPO_ROOT, path));
      return controllerValidationFailures(source).map(
        (failure) => `${relativePath}:${failure.line} [${failure.rule}] ${failure.detail}`,
      );
    }),
  );
  for (const failure of failures) {
    console.error(`FAIL ${failure}`);
  }
  const checked =
    MODULE_ROOTS.reduce((count, moduleRoot) => count + controllerFiles(moduleRoot).length, 0);
  console.log(`Controller 边界参数校验：扫描 ${checked} 个 Controller`);
  if (failures.length > 0) {
    console.error(`Controller 边界参数校验失败 ${failures.length} 项`);
    process.exitCode = 1;
  } else {
    console.log('Controller 边界参数校验检查通过');
  }
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main();
}

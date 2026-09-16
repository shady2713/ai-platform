import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { sep } from 'node:path';
import test from 'node:test';

import {
  findFilesBySuffix,
  matchingParenthesis,
  normalizePath,
  parseYaml,
} from './gate-utils.mjs';

test('normalizePath 统一为本平台外的正斜杠路径', () => {
  assert.equal(normalizePath(['a', 'b', 'c'].join(sep)), 'a/b/c');
});

test('findFilesBySuffix 递归收集后缀匹配文件', () => {
  const found = findFilesBySuffix(import.meta.dirname, '.mjs');
  assert.ok(found.some((p) => p.endsWith('gate-utils.mjs')));
  assert.ok(found.every((p) => p.endsWith('.mjs')));
});

test('findFilesBySuffix 对不存在的根目录返回空数组', () => {
  assert.deepEqual(findFilesBySuffix(import.meta.dirname + '/does-not-exist', '.mjs'), []);
});

test('matchingParenthesis 返回配对的右括号下标', () => {
  assert.equal(matchingParenthesis('(a(b)c)', 0), 6);
  assert.equal(matchingParenthesis('f(a, g(b))', 1), 9);
});

test('matchingParenthesis 在未闭合时返回 -1', () => {
  assert.equal(matchingParenthesis('(a(b)', 0), -1);
  assert.equal(matchingParenthesis('abc', 0), -1);
});

test('左括号不会误触发右括号分支', () => {
  const source = '((';
  assert.equal(matchingParenthesis(source, 0), -1);
});

const MALFORMED = [
  ['Tab 缩进', 'version: 1\n\tfields: []\n'],
  ['单引号标量未闭合', "version: 1\ntitle: 'oops\n"],
  ['双引号标量未闭合', 'version: 1\ntitle: "oops\n'],
  ['flow 序列未闭合', 'version: 1\nfields: [a, b\n'],
  ['映射行缺少冒号', 'version: 1\nfields\n'],
];

for (const [label, source] of MALFORMED) {
  test(`parseYaml 拒绝畸形输入：${label}`, () => {
    assert.throws(() => parseYaml(source), Error);
  });
}

test('parseYaml 解析基本映射与嵌套序列', () => {
  const parsed = parseYaml('version: 1\nitems:\n  - a\n  - b\n');
  assert.equal(parsed.version, 1);
  assert.deepEqual(parsed.items, ['a', 'b']);
});

test('parseYaml 解析真实例外台账', () => {
  const catalog = parseYaml(
    readFileSync(new URL('../docs/exceptions.yaml', import.meta.url), 'utf8'),
  );
  assert.equal(catalog.version, 1);
  assert.ok(Array.isArray(catalog.exceptions));
});
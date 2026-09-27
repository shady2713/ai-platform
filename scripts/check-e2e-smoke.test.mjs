/**
 * `check-e2e-smoke.mjs` 的拒绝测试（.harness/AGENTS.md 变更协议）：
 * 门禁必须能对代表性违规变红——尤其是"没跑用例却绿灯"这一类假绿。
 */
import assert from 'node:assert/strict';
import { mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import test from 'node:test';

import { assertSmokeVerdict, specFiles } from './check-e2e-smoke.mjs';

function report(stats) {
  return {
    stats: { expected: 0, flaky: 0, skipped: 0, unexpected: 0, ...stats },
  };
}

test('没有 .pw.ts 用例文件的目录（含不存在的目录）判为空套件', () => {
  const empty = mkdtempSync(join(tmpdir(), 'q06-smoke-empty-'));
  try {
    assert.deepEqual(specFiles(empty), []);
    assert.deepEqual(specFiles(join(empty, 'missing')), []);
    assert.throws(() => assertSmokeVerdict([], null), /拒绝空套件/u);
  } finally {
    rmSync(empty, { force: true, recursive: true });
  }
});

test('用例文件本身存在时才能进入判定（排序稳定、忽略非 .pw.ts）', () => {
  const root = mkdtempSync(join(tmpdir(), 'q06-smoke-files-'));
  try {
    writeFileSync(join(root, 'b.pw.ts'), '');
    writeFileSync(join(root, 'a.pw.ts'), '');
    writeFileSync(join(root, 'notes.md'), '');
    assert.deepEqual(specFiles(root), ['a.pw.ts', 'b.pw.ts']);
  } finally {
    rmSync(root, { force: true, recursive: true });
  }
});

test('报告缺失、stats 缺失或 expected=0 都拒绝绿灯（空运行/全跳过）', () => {
  const files = ['a.pw.ts'];
  assert.throws(() => assertSmokeVerdict(files, null), /缺少 Playwright JSON 报告/u);
  assert.throws(() => assertSmokeVerdict(files, []), /缺少 Playwright JSON 报告/u);
  assert.throws(() => assertSmokeVerdict(files, {}), /缺少 stats/u);
  assert.throws(
    () => assertSmokeVerdict(files, report({ expected: 0 })),
    /拒绝空运行/u,
  );
  assert.throws(
    () => assertSmokeVerdict(files, report({ expected: 0, skipped: 27 })),
    /拒绝空运行/u,
  );
  assert.throws(
    () => assertSmokeVerdict(files, report({ expected: '23' })),
    /stats\.expected 非法/u,
  );
});

test('运行数量覆盖不到全部用例文件时拒绝绿灯', () => {
  const files = ['a.pw.ts', 'b.pw.ts', 'c.pw.ts'];
  assert.throws(
    () => assertSmokeVerdict(files, report({ expected: 2 })),
    /未覆盖全部用例文件/u,
  );
});

test('存在 unexpected 失败用例时拒绝绿灯', () => {
  assert.throws(
    () =>
      assertSmokeVerdict(['a.pw.ts'], report({ expected: 2, unexpected: 1 })),
    /1 个失败用例/u,
  );
});

test('全部通过且每个用例文件都有用例执行时放行', () => {
  assert.doesNotThrow(() =>
    assertSmokeVerdict(
      ['a.pw.ts', 'b.pw.ts'],
      report({ expected: 5, skipped: 2 }),
    ),
  );
  // flaky 不隐藏失败：只要重跑后通过就计入 ran，不阻塞（Playwright 语义）
  assert.doesNotThrow(() =>
    assertSmokeVerdict(['a.pw.ts'], report({ expected: 1, flaky: 1 })),
  );
});

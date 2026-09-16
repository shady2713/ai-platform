import assert from 'node:assert/strict';
import test from 'node:test';
import { copyFileSync, mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { spawnSync } from 'node:child_process';

import { gateScripts, providerParityFailures, referencedScripts } from './check-gate-wiring.mjs';

test('门禁脚本按 check / 辅助 / 测试分类', () => {
  const groups = gateScripts([
    'check-alpha.mjs',
    'check-alpha.test.mjs',
    'check-beta.mjs',
    'run-build.mjs',
    'secret-scan.mjs',
  ]);
  assert.deepEqual(groups.checks, ['check-alpha.mjs', 'check-beta.mjs']);
  assert.deepEqual(groups.helpers, ['run-build.mjs', 'secret-scan.mjs']);
  assert.deepEqual(groups.tests, ['check-alpha.test.mjs']);
});

test('只在一个 provider 中接线时判定双 provider 未对齐', () => {
  const failures = providerParityFailures(new Set(['check-alpha.mjs']), new Set());
  assert.equal(failures.length, 1);
  assert.match(failures[0], /check-alpha.mjs/);
  assert.match(failures[0], /verify\.ps1 未接线/);
});

test('反向单边接线同样被检出', () => {
  const failures = providerParityFailures(new Set(), new Set(['check-beta.mjs']));
  assert.equal(failures.length, 1);
  assert.match(failures[0], /verify\.sh 未接线/);
});

test('双侧一致时不产生失败', () => {
  const both = new Set(['check-alpha.mjs', 'check-alpha.test.mjs']);
  assert.deepEqual(providerParityFailures(both, both), []);
});

test('引用扫描识别 scripts 前缀与反斜杠写法', () => {
  const text = 'node scripts/check-alpha.mjs\nnode scripts\\check-beta.mjs\n';
  const referenced = referencedScripts(text, [
    'check-alpha.mjs',
    'check-beta.mjs',
    'check-gamma.mjs',
  ]);
  assert.deepEqual([...referenced].sort(), ['check-alpha.mjs', 'check-beta.mjs']);
});

test('删除双侧执行调用后，测试 import 和脚本自引用不能充当接线', (t) => {
  const root = mkdtempSync(join(tmpdir(), 'gate-wiring-rejection-'));
  t.after(() => rmSync(root, { recursive: true, force: true }));
  mkdirSync(join(root, 'scripts'));
  mkdirSync(join(root, '.harness'));
  copyFileSync(
    new URL('./check-gate-wiring.mjs', import.meta.url),
    join(root, 'scripts/check-gate-wiring.mjs'),
  );
  writeFileSync(join(root, 'scripts/check-alpha.mjs'), '// usage: node scripts/check-alpha.mjs\n');
  writeFileSync(join(root, 'scripts/check-alpha.test.mjs'), "import './check-alpha.mjs';\n");
  const run = () =>
    spawnSync(process.execPath, [join(root, 'scripts/check-gate-wiring.mjs')], {
      encoding: 'utf8',
    });
  const writeProviders = (includeGate) => {
    writeFileSync(
      join(root, '.harness/verify.sh'),
      'node scripts/check-gate-wiring.mjs\nnode --test scripts/check-alpha.test.mjs\n' +
        (includeGate ? 'node scripts/check-alpha.mjs\n' : '# node scripts/check-alpha.mjs\n'),
    );
    writeFileSync(
      join(root, '.harness/verify.ps1'),
      "Invoke-External 'node' @('scripts/check-gate-wiring.mjs')\nInvoke-External 'node' @('--test', 'scripts/check-alpha.test.mjs')\n" +
        (includeGate
          ? "Invoke-External 'node' @('scripts/check-alpha.mjs')\n"
          : "# Invoke-External 'node' @('scripts/check-alpha.mjs')\n"),
    );
  };
  writeProviders(true);
  assert.equal(run().status, 0);
  writeProviders(false);
  const rejected = run();
  assert.equal(rejected.status, 1);
  assert.match(rejected.stderr, /check-alpha\.mjs/);
});

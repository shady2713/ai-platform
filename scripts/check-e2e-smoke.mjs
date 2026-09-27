/**
 * 真实浏览器冒烟门禁（ADR 0046）：在真实 Chromium、真实生产产物上跑 Q06 跨源套件。
 *
 * <p>门禁语义：
 *  - 只断言结构不比对截图（ADR 0046 决策 2）；
 *  - PR 层不执行（ADR 0046 决策 3，PR 反馈预算 ≤10 分钟），由 nightly/release 层阻塞调用；
 *  - **空套件不得绿灯**（Q06 步骤 3「禁止空脚本绿灯」）：没有用例文件、报告缺失、
 *    expected=0、运行覆盖不到全部用例文件、存在 unexpected 失败，任何一条都判失败。
 *
 * <p>失败判定基于 Playwright 写出的 JSON 报告（`测试目录/../.local-state/q06-browser/report.json`），
 * 而不是子进程退出码单一信号：命令退出码为 0 但实际没跑用例（例如 testMatch 被改坏）同样要拒绝。
 */
import { spawn } from 'node:child_process';
import { existsSync, readFileSync, readdirSync, rmSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

export const REPO_ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
export const FRONTEND_ROOT = join(
  REPO_ROOT,
  '前端代码',
  'basic-framework-admin',
);
export const SPEC_ROOT = join(FRONTEND_ROOT, 'tests', 'playwright', 'specs');
export const PLAYWRIGHT_CONFIG = 'tests/playwright/playwright.config.ts';
/** 与 tests/playwright/fixtures/origins.mjs 的 ARTIFACT_ROOT 是同一处（artifacts 已 gitignore）。 */
export const REPORT_PATH = join(
  REPO_ROOT,
  '.local-state',
  'q06-browser',
  'report.json',
);
const MISSING_BROWSER_PATTERN =
  /Executable doesn't exist|playwright install|browserType\.launch/u;

/** 用例文件清单：没有 `.pw.ts` 就是空套件，不开跑也要失败。 */
export function specFiles(root = SPEC_ROOT) {
  if (!existsSync(root)) {
    return [];
  }
  return readdirSync(root)
    .filter((name) => name.endsWith('.pw.ts'))
    .sort();
}

function readStat(stats, name) {
  const value = stats[name];
  if (!Number.isInteger(value) || value < 0) {
    throw new Error(
      `Playwright 报告的 stats.${name} 非法（${JSON.stringify(value)}）：${REPORT_PATH}`,
    );
  }
  return value;
}

/**
 * 门禁判定：每条不变量都独立失败，便于直接定位是哪一类"假绿"。
 *
 * @param {string[]} files `specFiles()` 的结果
 * @param {unknown} report 解析后的 Playwright JSON 报告
 */
export function assertSmokeVerdict(files, report) {
  if (files.length === 0) {
    throw new Error(
      `拒绝空套件：${SPEC_ROOT} 下没有任何 .pw.ts 用例文件（门禁不得在没跑用例时绿灯）`,
    );
  }
  if (report === null || typeof report !== 'object' || Array.isArray(report)) {
    throw new Error(`缺少 Playwright JSON 报告：${REPORT_PATH}`);
  }
  const stats = report.stats;
  if (stats === null || typeof stats !== 'object' || Array.isArray(stats)) {
    throw new Error(`Playwright JSON 报告缺少 stats：${REPORT_PATH}`);
  }
  const expected = readStat(stats, 'expected');
  const skipped = readStat(stats, 'skipped');
  const unexpected = readStat(stats, 'unexpected');
  const flaky = readStat(stats, 'flaky');
  if (expected <= 0) {
    throw new Error(
      `拒绝空运行：expected=${expected}（全部跳过或用例未执行都不算通过）`,
    );
  }
  const ran = expected + skipped + unexpected + flaky;
  if (ran < files.length) {
    throw new Error(
      `运行未覆盖全部用例文件：${ran} 个用例 < ${files.length} 个 .pw.ts 文件（有文件没被收集）`,
    );
  }
  if (unexpected > 0) {
    throw new Error(
      `浏览器套件存在 ${unexpected} 个失败用例；失败明细见 ${REPORT_PATH}`,
    );
  }
}

function runPlaywright() {
  const windows = process.platform === 'win32';
  const executable = windows ? (process.env.ComSpec ?? 'cmd.exe') : 'pnpm';
  const arguments_ = windows
    ? [
        '/d',
        '/s',
        '/c',
        `pnpm.cmd exec playwright test --config ${PLAYWRIGHT_CONFIG}`,
      ]
    : ['exec', 'playwright', 'test', '--config', PLAYWRIGHT_CONFIG];
  const child = spawn(executable, arguments_, {
    cwd: FRONTEND_ROOT,
    env: process.env,
    stdio: ['inherit', 'pipe', 'pipe'],
  });
  let output = '';
  for (const stream of [child.stdout, child.stderr]) {
    stream.on('data', (chunk) => {
      const text = chunk.toString();
      output += text;
      process.stdout.write(text);
    });
  }
  return new Promise((done, fail) => {
    child.once('error', fail);
    child.once('close', (code) => {
      done({ code: code ?? 1, output });
    });
  });
}

async function main() {
  const files = specFiles();
  if (files.length === 0) {
    // 空套件直接失败，不启动浏览器
    assertSmokeVerdict(files, null);
  }
  // 旧报告必须先删除：不能让上一次的运行结果替本次背书
  rmSync(REPORT_PATH, { force: true });

  const { code, output } = await runPlaywright();
  if (code !== 0) {
    const hint = MISSING_BROWSER_PATTERN.test(output)
      ? '\n提示：先安装浏览器：pnpm exec playwright install chromium（Linux CI 加 --with-deps）'
      : '';
    throw new Error(
      `Playwright 套件退出码 ${code}；报告：${REPORT_PATH}${hint}`,
    );
  }
  if (!existsSync(REPORT_PATH)) {
    throw new Error(`Playwright 未写出 JSON 报告：${REPORT_PATH}`);
  }
  assertSmokeVerdict(files, JSON.parse(readFileSync(REPORT_PATH, 'utf8')));
  const stats = JSON.parse(readFileSync(REPORT_PATH, 'utf8')).stats;
  console.log(
    `浏览器冒烟门禁通过：${files.length} 个用例文件，` +
      `passed=${stats.expected}、skipped=${stats.skipped}、flaky=${stats.flaky}、failed=${stats.unexpected}`,
  );
}

const entryPath = process.argv[1] ? resolve(process.argv[1]) : '';
if (entryPath === fileURLToPath(import.meta.url)) {
  try {
    await main();
  } catch (error) {
    console.error(error instanceof Error ? error.message : error);
    process.exitCode = 1;
  }
}

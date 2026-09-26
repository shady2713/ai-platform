import { fileURLToPath } from 'node:url';

import { defineConfig, devices } from '@playwright/test';

import { ARTIFACT_ROOT, FIXTURES_DIR, HOST } from './fixtures/origins.mjs';

const here = fileURLToPath(new URL('.', import.meta.url));
const artifact = (name: string) =>
  fileURLToPath(new URL(name, `file://${ARTIFACT_ROOT}/`));

/**
 * Q06 真实浏览器门禁（ADR 0046 的落地件之一）。
 *
 * 设计要点：
 *  - **只做结构断言，不做视觉回归**（ADR 0046 决策 2）：断言"页面/外壳渲染出来、状态与
 *    来源判定正确、请求只在本地 Origin"，不比对截图；
 *  - **testMatch 固定为 `.pw.ts` 后缀**：仓库 `vitest.config.ts` 只排除了它的默认忽略项与
 *    e2e 目录，用 `.spec.ts` 命名会被 vitest 抢收，所以这里用独立后缀 + 显式 `testDir`；
 *  - `webServer` 起夹具多 Origin 服务（宿主/壳/攻击页/管理端与 Chat 产物）；
 *  - 产物与录像写在 `.local-state/q06-browser/`（已 gitignore），不提交二进制。
 */
export default defineConfig({
  expect: { timeout: 15_000 },
  forbidOnly: true,
  fullyParallel: false,
  globalSetup: fileURLToPath(
    new URL('support/global-setup.mjs', import.meta.url),
  ),
  outputDir: artifact('test-results'),
  projects: [
    {
      name: 'chromium',
      use: { ...devices['Desktop Chrome'] },
    },
  ],
  reporter: [
    ['list'],
    ['json', { outputFile: artifact('report.json') }],
    ['html', { open: 'never', outputFolder: artifact('html-report') }],
  ],
  retries: 0,
  testDir: `${here}specs`,
  testMatch: '**/*.pw.ts',
  timeout: 120_000,
  use: {
    actionTimeout: 15_000,
    colorScheme: 'light',
    screenshot: 'only-on-failure',
    trace: 'retain-on-failure',
    video: 'on',
  },
  webServer: {
    command: `node ${fileURLToPath(new URL('fixtures/fixture-server.mjs', import.meta.url))}`,
    cwd: FIXTURES_DIR,
    reuseExistingServer: false,
    stderr: 'pipe',
    stdout: 'pipe',
    timeout: 30_000,
    url: `${HOST.origin}/control/health`,
  },
  workers: 1,
});

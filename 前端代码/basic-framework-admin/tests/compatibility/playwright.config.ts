/**
 * AT-065 真实浏览器渲染回归的 Playwright 配置（Q08 前端切片自带，不与 Q06 的套件互相干扰）。
 *
 * <p>与 Q06 的浏览器门禁同一约定：
 *  - 只做结构/像素断言，不做视觉回归（ADR 0046 决策 2）；
 *  - testMatch 固定 `.pw.ts`，testDir 就是本目录，因此 vitest 不会抢收（vitest 只收 `*.test.ts`）；
 *  - 自带 webServer（端口 5399）与 globalSetup（现场构建探针产物），不依赖 Q06 的 5290–5295 夹具。
 */
import { fileURLToPath } from 'node:url';

import { defineConfig, devices } from '@playwright/test';

const here = fileURLToPath(new URL('.', import.meta.url));

export default defineConfig({
  expect: { timeout: 15_000 },
  forbidOnly: true,
  fullyParallel: false,
  globalSetup: fileURLToPath(
    new URL('fixtures/chart-probe/global-setup.mjs', import.meta.url),
  ),
  outputDir: fileURLToPath(
    new URL(
      '../../../../.local-state/q08-compatibility/test-results',
      import.meta.url,
    ),
  ),
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
  reporter: [['list']],
  retries: 0,
  testDir: here,
  testMatch: '**/*.pw.ts',
  timeout: 60_000,
  use: {
    actionTimeout: 15_000,
    colorScheme: 'light',
    screenshot: 'only-on-failure',
    trace: 'retain-on-failure',
  },
  webServer: {
    command: `node ${fileURLToPath(new URL('fixtures/chart-probe/server.mjs', import.meta.url))}`,
    cwd: here,
    reuseExistingServer: false,
    stderr: 'pipe',
    stdout: 'pipe',
    timeout: 30_000,
    url: 'http://127.0.0.1:5399/health',
  },
  workers: 1,
});

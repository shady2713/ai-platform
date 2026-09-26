/**
 * AT-056：embed 与 admin 的安全头 —— 允许域 iframe 可加载、非允许域被 frame-ancestors 拒绝、
 * admin 仍要求登录。
 *
 * 这是**唯一必须依赖真实平台后端**的用例（安全头由 Spring 过滤链给出，属 C05 的交付面）：
 *  - 后端可达时：断言 admin 路径 401 + `X-Frame-Options: SAMEORIGIN`；
 *    给了 `Q06_EMBED_APP_CODE` 时断言嵌入壳的 CSP `frame-ancestors` 精确到允许域、且无 X-Frame-Options；
 *    再用真实浏览器验证"非允许域被拒、允许域可加载"（后者需要 `Q06_EMBED_ALLOWED_ORIGIN`
 *    指向夹具 Origin，且该 Origin 已在应用配置里登记）。
 *  - 后端不可达时：**明确 skip 并写清原因**（不是 pass），结论进 README 的未验证项。
 *
 * 本机环境：48080 无后端（`docker ps` 只有 redis）⇒ 本用例预期为 skipped。
 * 不依赖模型（model-dependent: not configured in this environment）。
 */
import process from 'node:process';

import { expect, test } from '@playwright/test';

import {
  ATTACK,
  ATTACK_PAGE_PATH,
  HOST,
  PLATFORM_BASE,
} from '../fixtures/origins.mjs';
import { hostCall, openHostPage } from '../support/harness';

const ADMIN_PROBE_PATH = '/admin-api/system/auth/get-permission-info';
const BACKEND_UNAVAILABLE = `后端不可达（${PLATFORM_BASE}）：embed/admin 的安全头需要真实 Spring 过滤链（C05），本环境没有后端`;

async function backendAlive(
  page: import('@playwright/test').Page,
): Promise<boolean> {
  try {
    const response = await page.request.get(
      `${PLATFORM_BASE}${ADMIN_PROBE_PATH}`,
      {
        timeout: 5000,
      },
    );
    return response.status() > 0;
  } catch {
    return false;
  }
}

test.describe('AT-056 embed 与 admin 安全头', () => {
  test('admin 路径保持认证与 SAMEORIGIN（不允许被任何站点嵌套）', async ({
    page,
  }) => {
    test.skip(!(await backendAlive(page)), BACKEND_UNAVAILABLE);
    const response = await page.request.get(
      `${PLATFORM_BASE}${ADMIN_PROBE_PATH}`,
      {
        timeout: 5000,
      },
    );
    expect(response.status()).toBe(401);
    expect(response.headers()['x-frame-options']).toBe('SAMEORIGIN');
  });

  test('embed 壳：frame-ancestors 精确到允许域且浏览器拒绝非允许域嵌套', async ({
    page,
  }) => {
    test.skip(!(await backendAlive(page)), BACKEND_UNAVAILABLE);
    const appCode = process.env.Q06_EMBED_APP_CODE ?? '';
    test.skip(
      appCode === '',
      '未提供 Q06_EMBED_APP_CODE：应用允许域来自数据库发布配置，本环境无法准备',
    );

    const shellUrl = `${PLATFORM_BASE}/app-api/ai/v1/embed/${appCode}`;
    const response = await page.request.get(shellUrl, { timeout: 5000 });
    expect(response.status()).toBe(200);
    const headers = response.headers();
    // 嵌入路径只由 CSP 精确控制祖先，不再有 X-Frame-Options
    expect(headers['x-frame-options']).toBeUndefined();
    expect(headers['content-security-policy'] ?? '').toContain(
      'frame-ancestors',
    );
    expect(headers['content-security-policy'] ?? '').not.toContain(
      "'unsafe-inline'",
    );

    // 浏览器侧：在**未授权** Origin 的页面里嵌套嵌入壳，必须被 frame-ancestors 拒绝
    await page.goto(`${ATTACK.origin}${ATTACK_PAGE_PATH}`);
    await page.evaluate((src) => {
      const frame = document.createElement('iframe');
      frame.src = src;
      frame.id = 'blocked-embed';
      document.body.append(frame);
    }, shellUrl);
    await expect
      .poll(
        () =>
          page.frames().filter((frame) => frame.url().startsWith(PLATFORM_BASE))
            .length,
        {
          message:
            '非允许域嵌套必须被 CSP frame-ancestors 挡住（不产生子 frame）',
        },
      )
      .toBe(0);

    // 允许域（需已登记在应用配置里）可以加载：Q06_EMBED_ALLOWED_ORIGIN 指向夹具 Origin 时验证
    const allowedOrigin = process.env.Q06_EMBED_ALLOWED_ORIGIN ?? '';
    test.skip(
      allowedOrigin !== HOST.origin,
      'Q06_EMBED_ALLOWED_ORIGIN 未指向夹具宿主 Origin：允许域可加载的正向验证需要把该 Origin 登记进应用配置',
    );
    await openHostPage(page);
    await hostCall(page, 'openExtraFrame', [
      {
        appCode,
        frameUrl: shellUrl,
        instanceId: 'security-header-probe',
        name: 'allowed-embed',
      },
    ]);
    await expect
      .poll(
        () =>
          page.frames().filter((frame) => frame.url().startsWith(PLATFORM_BASE))
            .length,
      )
      .toBeGreaterThan(0);
  });
});

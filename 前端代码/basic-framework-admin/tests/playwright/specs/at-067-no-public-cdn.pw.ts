import type { Page } from '@playwright/test';

/**
 * AT-067：禁公共 CDN 环境 —— 管理端 / Chat 产物页只用本地 Origin，且断言**能变红**。
 *
 * 做法：
 *  1. 打开真实生产产物页（管理端登录页、独立 Chat 页），记录**每一条网络请求**；
 *  2. 判定数据就是仓库自己的登记表（`apps/ai-chat/scripts/public-cdn-registry.json`）：
 *     - `publicCdnHosts`：出现即违规；
 *     - `registeredHosts`：登记表原文声明它们"只作为命名空间常量/文档链接/配置占位"、
 *       "Q06 网络断言确认无请求"——所以**运行期被请求同样违规**；
 *     - 其余任何非本地 Origin 的 http(s) 请求也违规（禁公网环境的判据）；
 *  3. 拒绝测试：向页面注入一个指向公共 CDN 的 `<img>`，断言探针确实检出违规
 *     （否则"全绿"可能只是探针坏了，禁止空脚本绿灯）。
 *
 * 部署期配置：管理端产物里的 `_app.config.js` 是运行时配置资产（`vite:extra-app-config`），
 * 本夹具按同一机制在服务时把 `VITE_GLOB_API_URL` 换成本地基址（禁公网部署的正常做法）；
 * 用例会先断言替换确实生效（否则断言的是"产物自带的占位域名"而不是禁公网行为）。
 *
 * 未覆盖：报表页需要登录态与后端数据（R07），本环境无后端/凭据 ⇒ 明确 skip。
 * 也不依赖模型（model-dependent: not configured in this environment）。
 */
import { readFileSync } from 'node:fs';
import { join } from 'node:path';

import { expect, test } from '@playwright/test';

import {
  ADMIN,
  CHAT_APP,
  FRONTEND_ROOT,
  PLATFORM_BASE,
} from '../fixtures/origins.mjs';
import { capture } from '../support/harness';

interface CdnRegistry {
  publicCdnHosts: string[];
  registeredHosts: Record<string, string>;
}

const registry = JSON.parse(
  readFileSync(
    join(FRONTEND_ROOT, 'apps/ai-chat/scripts/public-cdn-registry.json'),
    'utf8',
  ),
) as CdnRegistry;

const LOCAL_HOSTS = new Set(['127.0.0.1', '::1', 'localhost']);
const PLATFORM_HOST = new URL(PLATFORM_BASE).hostname;
const RUNTIME_ALLOWED = new Set([PLATFORM_HOST, ...LOCAL_HOSTS]);

function requestHost(url: string): null | string {
  try {
    const parsed = new URL(url);
    if (parsed.protocol !== 'http:' && parsed.protocol !== 'https:')
      return null;
    return parsed.hostname;
  } catch {
    return null;
  }
}

interface Violations {
  /** 公共 CDN（登记表 publicCdnHosts）：出现即违规。 */
  publicCdn: string[];
  /** 登记表声明"不自动请求"的域名，运行期却被请求了。 */
  registeredButRequested: string[];
  /** 未登记的外部域名。 */
  unknownExternal: string[];
}

function violations(urls: string[]): Violations {
  const publicCdn = new Set(registry.publicCdnHosts);
  const registered = new Set(Object.keys(registry.registeredHosts));
  const result: Violations = {
    publicCdn: [],
    registeredButRequested: [],
    unknownExternal: [],
  };
  for (const url of urls) {
    const host = requestHost(url);
    if (host === null || RUNTIME_ALLOWED.has(host)) continue;
    if (publicCdn.has(host)) result.publicCdn.push(url);
    else if (registered.has(host)) result.registeredButRequested.push(url);
    else result.unknownExternal.push(url);
  }
  return result;
}

function collect(page: Page): string[] {
  const urls: string[] = [];
  page.on('request', (request) => {
    urls.push(request.url());
  });
  return urls;
}

test.describe('AT-067 禁公共 CDN', () => {
  test('管理端生产产物页：只有本地 Origin 的请求，无字体/图标外部域', async ({
    page,
  }) => {
    // 先确认"禁公网部署"的接口基址已经生效（否则测的是产物占位域名）
    const configResponse = await page.request.get(
      `${ADMIN.origin}/_app.config.js`,
    );
    const configText = await configResponse.text();
    expect(configText).toContain(`${PLATFORM_BASE}/admin-api`);
    expect(configText).not.toContain('api.example.com');

    const urls = collect(page);
    await page.goto(`${ADMIN.origin}/#/auth/login`, {
      waitUntil: 'networkidle',
    });
    await expect(page.locator('input[name="username"]')).toBeVisible();

    const found = violations(urls);
    expect(urls.length).toBeGreaterThan(3);
    expect(
      found.publicCdn,
      `公共 CDN 请求：${found.publicCdn.join(', ')}`,
    ).toEqual([]);
    expect(
      found.unknownExternal,
      `未登记外部请求：${found.unknownExternal.join(', ')}`,
    ).toEqual([]);
    // 登记表原文："Iconify 运行时图标 API 回退地址（图标集已随包构建，Q06 网络断言确认无请求）"
    expect(
      found.registeredButRequested,
      [
        '登记表声明这些域名不应被运行期请求，但管理端登录页实际请求了：',
        ...found.registeredButRequested,
        '（captcha 组件 verify-slide.vue 用字符串图标 lucide:refresh-ccw / lucide:x，',
        '@iconify/vue 会回退到 api.iconify.design 取图标）',
      ].join('\n'),
    ).toEqual([]);

    await capture(page, 'at-067-admin-local-only');
  });

  test('独立 Chat 生产产物页：只有本地 Origin 的请求', async ({ page }) => {
    const urls = collect(page);
    await page.goto(`${CHAT_APP.origin}/`, { waitUntil: 'networkidle' });
    await expect(page.locator('h1')).toHaveText('AI 助手');

    const found = violations(urls);
    expect(found.publicCdn).toEqual([]);
    expect(found.registeredButRequested).toEqual([]);
    expect(found.unknownExternal).toEqual([]);
    expect(urls.length).toBeGreaterThan(2);

    await capture(page, 'at-067-chat-local-only');
  });

  test('探针本身可被触发（拒绝测试：注入公共 CDN 请求必须被判违规）', async ({
    page,
  }) => {
    const urls = collect(page);
    await page.goto(`${CHAT_APP.origin}/`, { waitUntil: 'networkidle' });
    expect(violations(urls).publicCdn).toEqual([]);

    // 故意引入一个公共 CDN 请求：探针必须能检出（否则"全绿"没有意义）
    await page.evaluate(() => {
      const image = document.createElement('img');
      image.src = 'https://cdn.jsdelivr.net/npm/nonexistent-q06-probe.png';
      image.alt = 'q06 probe';
      document.body.append(image);
    });
    await expect
      .poll(() => violations(urls).publicCdn.length, {
        message: '注入的 CDN 请求必须被探针检出',
      })
      .toBeGreaterThan(0);
    expect(
      violations(urls).publicCdn.some((url) =>
        url.includes('cdn.jsdelivr.net'),
      ),
    ).toBe(true);
  });

  test('报表页（R07）在网络断言下：需要登录态与后端（未验证）', async ({
    page,
  }) => {
    const urls = collect(page);
    await page.goto(`${ADMIN.origin}/#/auth/login`, {
      waitUntil: 'networkidle',
    });
    const apiRequests = urls.filter((url) => url.includes('/admin-api/'));
    expect(apiRequests.length).toBeGreaterThan(0);
    expect(
      apiRequests.every((url) => requestHost(url) === PLATFORM_HOST),
      `管理端接口请求必须全部指向本地基址：${apiRequests.join(', ')}`,
    ).toBe(true);
    test.skip(
      true,
      '报表页需要登录态与后端数据（本环境无后端/凭据）：报表页在禁公网下的断言未验证',
    );
  });
});

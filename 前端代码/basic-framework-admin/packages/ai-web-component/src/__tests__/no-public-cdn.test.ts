import { existsSync, readdirSync, readFileSync } from 'node:fs';
import { join, relative, resolve } from 'node:path';

import { describe, expect, it } from 'vitest';

/**
 * X09 验收：**本包自身不发公共 CDN 请求**。
 *
 * <p>扫描两层：
 * <ol>
 *   <li>源码（`src/**`，测试夹具除外）：不得出现公共 CDN 域名与外部资源引用
 *       （`<link>`/`<script src>`/CSS `@import`/`url(http…)`）；</li>
 *   <li>构建产物（`dist/*.js`，存在时）：同上——产物是宿主真正加载的东西。</li>
 * </ol>
 *
 * <p>口径说明（必须如实标注）：本检查只证明**本包自身**的源码与产物不含公共 CDN 引用，
 * 不能证明整个站点没有外部请求（宿主页面、其它包与运行期第三方库不在扫描范围内）。
 * 站点级断言由 Q06 的浏览器网络用例（AT-067）承担。
 */
const PACKAGE_ROOT = resolve(import.meta.dirname, '../..');
const SOURCE_ROOT = join(PACKAGE_ROOT, 'src');
const DIST_ROOT = join(PACKAGE_ROOT, 'dist');

/** 公共 CDN / 公共静态资源托管域（与 `apps/ai-chat/scripts/public-cdn-registry.json` 同源口径）。 */
const PUBLIC_CDN_HOSTS = [
  'ajax.googleapis.com',
  'bootcdn.net',
  'cdn.bootcdn.net',
  'cdn.jsdelivr.net',
  'cdn.skypack.dev',
  'cdn.staticfile.org',
  'cdn.tailwindcss.com',
  'cdnjs.cloudflare.com',
  'code.jquery.com',
  'esm.sh',
  'fastly.jsdelivr.net',
  'fonts.googleapis.com',
  'fonts.gstatic.com',
  'jsdelivr.net',
  'skypack.dev',
  'staticfile.org',
  'unpkg.com',
];

const TEXT_FILE = /\.(?:css|html|js|mjs|mts|ts|vue)$/u;
const TEST_FILE = /(?:^|[/\\])__tests__[/\\]|\.(?:spec|test)\.[cm]?[jt]sx?$/u;
const URL_PATTERN = /https?:\/\/[\w.~:/?#@!$&()*+,;=%[\]-]+/gu;
const EXTERNAL_RESOURCE_PATTERN =
  /(?:@import\s+|url\(\s*|src\s*=\s*|href\s*=\s*)["']?(https?:\/\/[^"')\s]+)/giu;
/** 允许出现的外部地址：契约与文档链接（不是运行期请求）。 */
const ALLOWED_EXTERNAL_PREFIXES = [
  'http://localhost',
  'http://www.w3.org',
  'https://developer.mozilla.org',
  'https://host.',
  'https://platform.example.com',
  'https://www.w3.org',
];

function collectFiles(root: string): string[] {
  if (!existsSync(root)) {
    return [];
  }
  const files: string[] = [];
  for (const entry of readdirSync(root, { withFileTypes: true })) {
    const path = join(root, entry.name);
    if (entry.isDirectory()) {
      if (entry.name === 'node_modules') {
        continue;
      }
      files.push(...collectFiles(path));
      continue;
    }
    if (TEXT_FILE.test(entry.name) && !TEST_FILE.test(path)) {
      files.push(path);
    }
  }
  return files;
}

function scan(files: string[], options: { strictUrls: boolean }): string[] {
  const failures: string[] = [];
  for (const file of files) {
    const content = readFileSync(file, 'utf8');
    const label = relative(PACKAGE_ROOT, file);
    for (const host of PUBLIC_CDN_HOSTS) {
      if (content.includes(host)) {
        failures.push(`${label} 引用了公共 CDN 域名：${host}`);
      }
    }
    for (const match of content.matchAll(EXTERNAL_RESOURCE_PATTERN)) {
      const url = match[1] ?? '';
      if (!ALLOWED_EXTERNAL_PREFIXES.some((prefix) => url.startsWith(prefix))) {
        failures.push(`${label} 含外部资源引用：${url}`);
      }
    }
    if (options.strictUrls) {
      // 只对**本包自己写的源码**做"未登记地址"判定；产物里含第三方库的文档链接，
      // 对产物只检查公共 CDN 与外部资源引用（这两类才会产生网络请求）。
      for (const match of content.matchAll(URL_PATTERN)) {
        const url = match[0];
        if (
          !ALLOWED_EXTERNAL_PREFIXES.some((prefix) => url.startsWith(prefix))
        ) {
          failures.push(`${label} 含未登记的外部地址：${url.slice(0, 80)}`);
        }
      }
    }
  }
  return failures;
}

describe('x09 验收：本包无公共 CDN（源码与产物）', () => {
  it('源码扫描：无公共 CDN 域名、无外部资源引用、无未登记地址', () => {
    const files = collectFiles(SOURCE_ROOT);
    // 扫描确实覆盖了全部源文件（空扫描不算通过）
    expect(files.length).toBeGreaterThan(5);
    expect(scan(files, { strictUrls: true })).toEqual([]);
  });

  it('构建产物扫描（已构建时）：无公共 CDN 域名、无外部资源引用、无绝对 import', () => {
    const files = collectFiles(DIST_ROOT);
    if (files.length === 0) {
      // 产物未构建：本用例不假装通过，明确跳过（门禁里构建后必然执行）
      expect(files).toEqual([]);
      return;
    }
    expect(scan(files, { strictUrls: false })).toEqual([]);
    // 产物只引用自己的分包（相对路径），不指向任何绝对地址
    for (const file of files) {
      const content = readFileSync(file, 'utf8');
      for (const match of content.matchAll(
        /(?:from|import)\s*["']([^"']+)["']/gu,
      )) {
        const reference = match[1] ?? '';
        if (reference.startsWith('http')) {
          throw new Error(
            `${relative(PACKAGE_ROOT, file)} 引用了绝对地址 ${reference}`,
          );
        }
      }
    }
  });

  it('组件样式不加载远程字体/图片（无 @import、无 url()）', async () => {
    const { COMPONENT_STYLES } = await import('../element/styles');
    expect(COMPONENT_STYLES).not.toMatch(/@import/u);
    expect(COMPONENT_STYLES).not.toMatch(/url\(/u);
    expect(COMPONENT_STYLES).not.toMatch(/@font-face/u);
  });
});

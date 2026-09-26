/**
 * 公共 CDN 引用扫描（F04 验收项 3）。
 *
 * 为什么需要它：离线/内网环境不允许运行期公共 CDN 请求。构建成功不代表产物干净，因此对
 * **构建产物与 AI 前端源码**做静态扫描，规则分三层：
 *   1. 公共 CDN 域名出现即失败（清单是数据：`public-cdn-registry.json`）；
 *   2. HTML/CSS 里的**外部资源引用**（script/link/url()）出现即失败——这类引用一定会发网络请求；
 *   3. 其他外部域名必须登记用途（命名空间常量/文档链接/配置占位/第三方库内置文案），
 *      未登记即失败，避免"新引入的远程地址"被默默放行。
 * 测试夹具（`__tests__`、`*.test.*`）不参与扫描：它们是离线用例数据，不进入运行期。
 *
 * 已知边界：第三方库代码里可能残留运行时回退地址（如 Iconify 的图标 API 地址），
 * 静态扫描只能按"已登记说明+不属于公共 CDN"处理；真实请求有无由 Q06 的浏览器网络断言确认。
 *
 * 用法（在 前端代码/basic-framework-admin 下执行）：
 *   node apps/ai-chat/scripts/check-public-cdn.mjs
 */
import { existsSync } from 'node:fs';
import { readdir, readFile } from 'node:fs/promises';
import { join, relative, resolve } from 'node:path';
import process from 'node:process';

const WORKSPACE_ROOT = resolve(import.meta.dirname, '../../..');
const REGISTRY_PATH = join(import.meta.dirname, 'public-cdn-registry.json');

/** 读取域名登记表（数据文件）；结构不合法即失败，避免"清单为空所以通过"。 */
function readRegistry(content) {
  const registry = JSON.parse(content);
  const registeredHosts =
    registry.registeredHosts && typeof registry.registeredHosts === 'object'
      ? registry.registeredHosts
      : {};
  if (
    registry.version !== 1 ||
    !Array.isArray(registry.publicCdnHosts) ||
    registry.publicCdnHosts.length === 0 ||
    registry.publicCdnHosts.some((host) => typeof host !== 'string') ||
    Object.values(registeredHosts).some(
      (reason) => typeof reason !== 'string' || reason.length === 0,
    )
  ) {
    throw new Error('public-cdn-registry.json 结构无效');
  }
  return {
    publicCdnHosts: new Set(registry.publicCdnHosts),
    registeredHosts: new Map(Object.entries(registeredHosts)),
  };
}

const FIRST_PARTY_SOURCE_TARGETS = [
  'apps/ai-chat/src',
  'packages/ai-chat-ui/src',
  'packages/ai-contracts/src',
  'packages/ai-embed-sdk/src',
];

const SCAN_TARGETS = [
  {
    label: 'apps/ai-chat/dist',
    path: join(WORKSPACE_ROOT, 'apps/ai-chat/dist'),
    required: true,
  },
  {
    label: 'apps/ai-chat/index.html',
    path: join(WORKSPACE_ROOT, 'apps/ai-chat/index.html'),
    required: true,
  },
  {
    label: 'apps/web-ele/dist',
    path: join(WORKSPACE_ROOT, 'apps/web-ele/dist'),
    required: false,
  },
  ...FIRST_PARTY_SOURCE_TARGETS.map((label) => ({
    label,
    path: join(WORKSPACE_ROOT, label),
    required: true,
  })),
];

const TEXT_EXTENSION_PATTERN = /\.(?:css|html|js|json|mjs|mts|ts|vue)$/;
const TEST_FILE_PATTERN =
  /(?:^|[/\\])__tests__[/\\]|\.(?:spec|test)\.[cm]?[jt]sx?$/;
const HOSTNAME_PATTERN =
  /^(?:[a-z\d](?:[a-z\d-]*[a-z\d])?\.)+[a-z]{2,}$|^(?:\d{1,3}\.){3}\d{1,3}$|^localhost$/i;
const URL_PATTERN = /https?:\/\/[\w.~:/?#@!$&()*+,;=%[\]-]+/g;
const CSS_EXTERNAL_REFERENCE_PATTERN =
  /(?:@import\s+|url\(\s*)["']?(https?:\/\/[^"')\s]+)/gi;

function write(line) {
  process.stdout.write(`${line}\n`);
}

function writeError(line) {
  process.stderr.write(`${line}\n`);
}

async function collectFiles(path) {
  if (!existsSync(path)) {
    return [];
  }
  const entries = await readdir(path, { withFileTypes: true }).catch(
    () => null,
  );
  if (entries === null) {
    return [path];
  }
  const files = [];
  for (const entry of entries) {
    const child = join(path, entry.name);
    if (entry.isDirectory()) {
      if (entry.name === 'node_modules') {
        continue;
      }
      files.push(...(await collectFiles(child)));
    } else if (
      TEXT_EXTENSION_PATTERN.test(entry.name) &&
      !TEST_FILE_PATTERN.test(child)
    ) {
      files.push(child);
    }
  }
  return files;
}

/** 从 HTML 的资源引用里抽取外部地址（脚本/样式/图标等运行期会发请求的引用）。 */
function externalHtmlReferences(html) {
  return [...html.matchAll(/(?:src|href)\s*=\s*"([^"]+)"/g)]
    .map((match) => match[1])
    .filter((reference) => /^[a-z][\w+.-]*:/i.test(reference));
}

async function main() {
  const { publicCdnHosts, registeredHosts } = readRegistry(
    await readFile(REGISTRY_PATH, 'utf8'),
  );
  const occurrences = new Map();
  const skipped = new Set();
  const failures = [];
  let scannedFiles = 0;

  for (const target of SCAN_TARGETS) {
    if (!existsSync(target.path)) {
      if (target.required) {
        failures.push(`扫描目标缺失：${target.label}（先构建对应产物）`);
      }
      continue;
    }
    for (const file of await collectFiles(target.path)) {
      const content = await readFile(file, 'utf8');
      scannedFiles += 1;
      const relativePath = relative(WORKSPACE_ROOT, file);
      if (file.endsWith('.html')) {
        const external = externalHtmlReferences(content);
        if (external.length > 0) {
          failures.push(
            `${relativePath} 的 HTML 引用了外部资源：${external.join(', ')}`,
          );
        }
      }
      if (file.endsWith('.css')) {
        const external = [
          ...content.matchAll(CSS_EXTERNAL_REFERENCE_PATTERN),
        ].map((match) => match[1]);
        if (external.length > 0) {
          failures.push(
            `${relativePath} 的 CSS 引用了外部资源：${[...new Set(external)].join(', ')}`,
          );
        }
      }
      for (const url of content.match(URL_PATTERN) ?? []) {
        let host;
        try {
          host = new URL(url).hostname;
        } catch {
          continue;
        }
        if (!HOSTNAME_PATTERN.test(host)) {
          // 模板变量（如 https://${host}）、文档占位（如 https://host/...）不是真实域名
          skipped.add(url.slice(0, 60));
          continue;
        }
        const entry = occurrences.get(host) ?? {
          files: new Set(),
          registration: registeredHosts.get(host),
          total: 0,
        };
        entry.total += 1;
        entry.files.add(relativePath);
        occurrences.set(host, entry);
        if (publicCdnHosts.has(host)) {
          failures.push(`公共 CDN 域名：${host}（${relativePath}）`);
        }
      }
    }
  }

  write(`扫描文件：${scannedFiles} 个（构建产物 + AI 前端源码，测试夹具除外）`);
  write('外部域名清单：');
  for (const [host, entry] of [...occurrences].toSorted()) {
    const classification =
      entry.registration ??
      (publicCdnHosts.has(host) ? '公共 CDN（禁止）' : '未登记（需说明用途）');
    write(
      `  - ${host} ×${entry.total}（${entry.files.size} 个文件）：${classification}`,
    );
    if (!entry.registration && !publicCdnHosts.has(host)) {
      failures.push(
        `未登记的外部域名：${host}（${[...entry.files].join(', ')}）；如为文档链接/常量/配置占位，请登记到 public-cdn-registry.json 并写明用途`,
      );
    }
  }
  if (skipped.size > 0) {
    write(
      `非域名匹配（模板变量/文档占位，不计入外部域名）：${[...skipped].join(', ')}`,
    );
  }

  const cdnHosts = [...occurrences.keys()].filter((host) =>
    publicCdnHosts.has(host),
  );
  write(
    cdnHosts.length === 0
      ? '公共 CDN 域名：0 命中（清单见 public-cdn-registry.json）'
      : `公共 CDN 域名：${cdnHosts.length} 个命中（${cdnHosts.join(', ')}）`,
  );
  write('外部资源引用（HTML/CSS）：0 命中');

  if (failures.length > 0) {
    writeError(`\n公共 CDN 扫描失败：${failures.length} 项`);
    for (const failure of failures) {
      writeError(`  ☒ ${failure}`);
    }
    process.exitCode = 1;
    return;
  }
  write('公共 CDN 扫描通过：无公共 CDN 引用，外部域名均已登记用途');
}

await main();

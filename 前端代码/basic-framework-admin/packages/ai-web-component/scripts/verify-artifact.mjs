/**
 * 组件产物验证（X09 验收项：包体预算与无公共 CDN）。
 *
 * 为什么需要它：构建成功只说明"能打包"，不说明"包体在预算内、产物干净"。
 * 本脚本对 `dist/` 做三件事（全部基于**实测数字**，不写估计值）：
 *   1. 计量入口 JS / 样式资源 / 懒加载分包的原始与 gzip 体积，并与预算比较；
 *   2. 静态扫描公共 CDN 域名与外部资源引用（HTML/CSS/JS 里的 url()/@import/绝对地址）；
 *   3. 校验产物命名是版本化固定命名（无 `latest` 之类浮动路径），且入口/样式/分包同目录。
 *
 * 口径说明：本脚本只证明**本包产物自身**不含公共 CDN 引用；站点级"零外部请求"由 Q06 的浏览器
 * 网络用例（AT-067）断言。图表分包（`@antv/g2`）是**懒加载**：只有真正渲染图表时才请求。
 *
 * 用法（在 前端代码/basic-framework-admin 下执行）：
 *   pnpm -F @vben/ai-web-component run build
 *   pnpm -F @vben/ai-web-component run verify:artifact
 */
import { existsSync, readFileSync } from 'node:fs';
import { readdir, readFile, stat } from 'node:fs/promises';
import { join, resolve } from 'node:path';
import process from 'node:process';
import { gzipSync } from 'node:zlib';

const PACKAGE_ROOT = resolve(import.meta.dirname, '..');
const DIST_DIR = resolve(PACKAGE_ROOT, 'dist');

/**
 * 包体预算（X09 首发口径，相对实测值留出约 25% 余量；升级依赖时必须重新测量并说明变化）。
 *
 * 实测（2026-09-28，vite 7.3.6 + 后置 esbuild 压缩，构建后 on-disk 字节数）：
 *   入口 ai-web-component-5.6.0.js：212,309 B（≈207.3 KiB），gzip 68,933 B（≈67.3 KiB）；
 *   样式 ai-web-component-5.6.0.css：1,252 B，gzip 358 B；
 *   图表懒加载分包：1,311,015 B（≈1280.3 KiB），gzip 388,045 B（≈379.0 KiB）。
 * 本脚本按 1 KiB = 1024 B 计量；vite 构建日志按 1 kB = 1000 B 打印（同一个文件的两种写法）。
 */
const BUDGETS = [
  {
    gzipKb: 85,
    label: '入口 JS（宿主首屏加载）',
    pattern: /^ai-web-component-\d+\.\d+\.\d+\.js$/u,
    rawKb: 260,
  },
  {
    gzipKb: 8,
    label: '样式资源（与入口同目录，注入 Shadow DOM）',
    pattern: /^ai-web-component-\d+\.\d+\.\d+\.css$/u,
    rawKb: 8,
  },
  {
    gzipKb: 470,
    label: '图表懒加载分包（只在渲染图表时请求）',
    pattern: /^ai-web-component-\d+\.\d+\.\d+\.[\w-]+\.chunk\.js$/u,
    rawKb: 1600,
  },
];

/**
 * 公共 CDN 域名清单**不在本文件里重复维护**：读 F04 的权威登记表
 * （`apps/ai-chat/scripts/public-cdn-registry.json` 的 `publicCdnHosts`）。
 * 清单缺失或结构非法即失败——不允许"清单为空所以通过"。
 */
const CDN_REGISTRY_PATH = resolve(
  PACKAGE_ROOT,
  '../../apps/ai-chat/scripts/public-cdn-registry.json',
);

function readPublicCdnHosts() {
  const registry = JSON.parse(readFileSync(CDN_REGISTRY_PATH, 'utf8'));
  if (
    registry.version !== 1 ||
    !Array.isArray(registry.publicCdnHosts) ||
    registry.publicCdnHosts.length === 0 ||
    registry.publicCdnHosts.some((host) => typeof host !== 'string')
  ) {
    throw new Error(
      'public-cdn-registry.json 结构无效（无法作为本包的 CDN 清单）',
    );
  }
  return registry.publicCdnHosts;
}

const EXTERNAL_REFERENCE =
  /(?:@import\s+|url\(\s*|src\s*=\s*|href\s*=\s*)["']?(https?:\/\/[^"')\s]+)/giu;
/** 允许出现的绝对地址：第三方库内置的命名空间/文档链接（不是运行期请求）。 */
const ALLOWED_EXTERNAL = [
  'http://www.w3.org',
  'https://www.w3.org',
  'https://vuejs.org',
];

const failures = [];

function write(line) {
  process.stdout.write(`${line}\n`);
}

function writeError(line) {
  process.stderr.write(`${line}\n`);
}

function fail(message) {
  failures.push(message);
  writeError(`  ☒ ${message}`);
}

function pass(message) {
  write(`  ☑ ${message}`);
}

function kb(bytes) {
  return Math.round((bytes / 1024) * 100) / 100;
}

async function main() {
  if (!existsSync(DIST_DIR)) {
    writeError(
      '产物目录不存在：先执行 pnpm -F @vben/ai-web-component run build',
    );
    process.exitCode = 1;
    return;
  }

  const entries = await readdir(DIST_DIR, { withFileTypes: true });
  const files = entries
    .filter((entry) => entry.isFile())
    .map((entry) => entry.name)
    .toSorted();

  write(`产物文件：${files.join(', ') || '（空）'}`);
  if (files.length === 0) {
    fail('产物为空（dist/ 下没有任何文件）');
    process.exitCode = 1;
    return;
  }

  // 1) 预算：每个预算项必须恰好命中一个文件（漏产物或命名变化都要被发现）
  for (const budget of BUDGETS) {
    const matched = files.filter((name) => budget.pattern.test(name));
    if (matched.length === 0) {
      fail(`${budget.label}：产物缺失（期望形如 ${budget.pattern.source}）`);
      continue;
    }
    for (const name of matched) {
      const path = join(DIST_DIR, name);
      const content = await readFile(path);
      const info = await stat(path);
      const rawKb = kb(info.size);
      const gzipKb = kb(gzipSync(content).length);
      const within = rawKb <= budget.rawKb && gzipKb <= budget.gzipKb;
      const summary = `${name}：${rawKb} kB / gzip ${gzipKb} kB（预算 ≤ ${budget.rawKb} kB / gzip ≤ ${budget.gzipKb} kB）`;
      if (within) {
        pass(`${budget.label} —— ${summary}`);
      } else {
        fail(`${budget.label} 超预算 —— ${summary}`);
      }
    }
  }

  // 2) 命名：产物必须版本化，禁止浮动路径
  for (const name of files) {
    if (!name.startsWith('ai-web-component-')) {
      fail(`产物命名不符合版本化口径：${name}`);
    }
    if (/latest|next|dev/iu.test(name)) {
      fail(`产物命名含浮动路径：${name}`);
    }
  }

  // 3) 公共 CDN 与外部资源引用
  const publicCdnHosts = readPublicCdnHosts();
  let cdnHits = 0;
  let externalHits = 0;
  for (const name of files) {
    const content = await readFile(join(DIST_DIR, name), 'utf8');
    for (const host of publicCdnHosts) {
      if (content.includes(host)) {
        cdnHits += 1;
        fail(`产物 ${name} 含公共 CDN 域名：${host}`);
      }
    }
    for (const match of content.matchAll(EXTERNAL_REFERENCE)) {
      const url = match[1] ?? '';
      if (!ALLOWED_EXTERNAL.some((prefix) => url.startsWith(prefix))) {
        externalHits += 1;
        fail(`产物 ${name} 含外部资源引用：${url.slice(0, 80)}`);
      }
    }
    // 运行期绝对地址（import 指向 http(s)）：产物必须是相对分包
    for (const match of content.matchAll(
      /(?:from|import)\s*["'](https?:\/\/[^"']+)["']/gu,
    )) {
      fail(`产物 ${name} 引用了绝对地址：${match[1]}`);
    }
  }
  if (cdnHits === 0) {
    pass(`公共 CDN 域名：0 命中（扫描 ${files.length} 个产物文件）`);
  }
  if (externalHits === 0) {
    pass('外部资源引用（@import/url()/src/href）：0 命中');
  }

  if (failures.length > 0) {
    writeError(`\n产物验证失败：${failures.length} 项`);
    process.exitCode = 1;
    return;
  }
  write(
    '产物验证通过：包体在预算内、命名版本化、无公共 CDN 与外部资源引用（仅证明本包产物自身）',
  );
}

await main();

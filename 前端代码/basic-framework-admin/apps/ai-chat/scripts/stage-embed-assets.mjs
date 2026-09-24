#!/usr/bin/env node
/**
 * 嵌入产物暂存（C05）：把 `apps/ai-chat` 的构建输出整理成服务端可直接提供的形态。
 *
 * <p>为什么需要这一步：嵌入页要求**所有静态资产自托管**（不引用公共 CDN、不使用 `latest` 路径），
 * 而服务端只按**构建清单**提供服务（见 `AiEmbedAssetCatalog`）。本脚本把 `dist/` 摊平成
 * `<目标目录>/assets/<文件名>`，并写出 `asset-manifest.json`：
 *
 * <pre>
 *   { "version": 1, "entryJs": "index-xxxx.js", "entryCss": "index-xxxx.css",
 *     "files": { "index-xxxx.js": "<sha256>", ... } }
 * </pre>
 *
 * 用法：
 *   node scripts/stage-embed-assets.mjs [目标目录]
 *   默认目标目录 = `embed-assets`（与 `basic-framework.ai.embed.assets-directory` 的默认值一致）
 */

import { createHash } from 'node:crypto';
import { cp, mkdir, readdir, readFile, rm, writeFile } from 'node:fs/promises';
import { dirname, join, resolve } from 'node:path';
import process from 'node:process';
import { fileURLToPath } from 'node:url';

const appRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const distDir = join(appRoot, 'dist');
const targetDir = resolve(process.argv[2] ?? join(appRoot, 'embed-assets'));
const assetsDir = join(targetDir, 'assets');

const ENTRY_SCRIPT = /<script[^>]+src="([^"]+\.js)"/i;
const ENTRY_STYLE = /<link[^>]+rel="stylesheet"[^>]+href="([^"]+\.css)"/i;
const HASHED_NAME = /^[A-Z0-9][\w.-]*$/i;

function fail(message) {
  process.stderr.write(`stage-embed-assets: ${message}\n`);
  process.exit(1);
}

/** 入口文件名：必须是 `assets/` 下的单段、内容哈希命名的文件。 */
function entryNameFrom(html, pattern, label) {
  const match = pattern.exec(html);
  if (!match) {
    return null;
  }
  const name = match[1].split('/').pop() ?? '';
  if (!HASHED_NAME.test(name)) {
    fail(`${label} 文件名不合法：${name}`);
  }
  return name;
}

const html = await readFile(join(distDir, 'index.html'), 'utf8').catch(
  () => null,
);
if (html === null) {
  fail('未找到构建输出（先执行 pnpm -F @vben/ai-chat build）');
}
const entryJs = entryNameFrom(html, ENTRY_SCRIPT, '入口脚本');
if (entryJs === null) {
  fail('构建输出里没有入口脚本（index.html 必须引用 assets/ 下的模块脚本）');
}
const entryCss = entryNameFrom(html, ENTRY_STYLE, '入口样式');

const builtFiles = await readdir(join(distDir, 'assets')).catch(() => null);
if (builtFiles === null) {
  fail('构建输出缺少 assets 目录');
}

await rm(targetDir, { force: true, recursive: true });
await mkdir(assetsDir, { recursive: true });

const files = {};
for (const name of builtFiles.toSorted()) {
  // 只暂存可被浏览器直接加载的文件：源映射、隐藏文件与目录一律不进产物
  if (
    !HASHED_NAME.test(name) ||
    name.endsWith('.map') ||
    name.startsWith('.')
  ) {
    continue;
  }
  const source = join(distDir, 'assets', name);
  await cp(source, join(assetsDir, name));
  const digest = createHash('sha256')
    .update(await readFile(source))
    .digest('hex');
  files[name] = digest;
}

if (files[entryJs] === undefined) {
  fail(`入口脚本 ${entryJs} 不在构建产物里`);
}

await writeFile(
  join(targetDir, 'asset-manifest.json'),
  `${JSON.stringify({ entryCss, entryJs, files, version: 1 }, null, 2)}\n`,
);

process.stdout.write(
  `stage-embed-assets: ${Object.keys(files).length} 个文件 → ${targetDir}（入口 ${entryJs}）\n`,
);

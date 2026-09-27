/**
 * Q06 浏览器夹具的 global setup：把"必须真实存在的产物"准备好，缺什么就现场构建。
 *
 * 为什么要现场构建而不是提交产物：本切片不提交二进制/构建输出（.gitignore 已忽略 dist），
 * 而浏览器用例必须跑在**真实生产产物**上，不能跑在源码态或手工拼接的 HTML 上。
 *
 * 产物与环境变量的对应关系（写进证据，不做隐式替换）：
 *  - `@vben/ai-embed-sdk`：版本化产物 `dist/ai-embed-sdk-<version>.js`（宿主页按固定命名加载）；
 *  - `@vben/ai-chat`：生产构建 + `VITE_AI_API_BASE_URL=http://127.0.0.1:48080/app-api/ai/v1`
 *    （禁公网环境：接口基址必须是本地；默认的 `.env.production` 是部署占位域名）；
 *  - `@vben/web-ele`：生产构建 + `VITE_GLOB_API_URL=http://127.0.0.1:48080/admin-api`（同上）；
 *  - 嵌入壳 bundle：用 vite 打包测试侧入口，入口里 import 的是**真实** iframe 桥源码。
 *
 * `Q06_FORCE_REBUILD=1` 会强制重建两个应用产物（最终证据跑一次全量重建）。
 */
import { spawn } from 'node:child_process';
import { mkdir, readdir, readFile, stat } from 'node:fs/promises';
import { dirname, join } from 'node:path';
import process from 'node:process';

import vue from '@vitejs/plugin-vue';
import { build } from 'vite';

import {
  ADMIN_DIST_DIR,
  ARTIFACT_ROOT,
  CHAT_APP_DIST_DIR,
  FIXTURES_DIR,
  FRONTEND_ROOT,
  SDK_DIST_DIR,
  SHELL_BUNDLE_DIR,
} from '../fixtures/origins.mjs';
import {
  Q07_PROBE_BUNDLE_FILE,
  Q07_PROBE_ENTRY,
} from './q07-resilience-api.mjs';

const PLATFORM_API_BASE = 'http://127.0.0.1:48080';

async function exists(path) {
  try {
    await stat(path);
    return true;
  } catch {
    return false;
  }
}

function run(command, args, options = {}) {
  return new Promise((done, fail) => {
    const child = spawn(command, args, {
      cwd: FRONTEND_ROOT,
      env: { ...process.env, ...options.env },
      stdio: ['ignore', 'pipe', 'pipe'],
    });
    let output = '';
    child.stdout.on('data', (chunk) => {
      output += chunk;
    });
    child.stderr.on('data', (chunk) => {
      output += chunk;
    });
    child.on('error', fail);
    child.on('close', (code) => {
      if (code === 0) done(output);
      else
        fail(
          new Error(`${command} ${args.join(' ')} 退出码 ${code}\n${output}`),
        );
    });
  });
}

async function readPackageVersion() {
  const manifest = JSON.parse(
    await readFile(
      join(FRONTEND_ROOT, 'packages/ai-embed-sdk/package.json'),
      'utf8',
    ),
  );
  return manifest.version;
}

async function ensureSdkArtifact() {
  const version = await readPackageVersion();
  const artifact = join(SDK_DIST_DIR, `ai-embed-sdk-${version}.js`);
  // 产物存在也可能是改动前的旧包（SDK 源码改了但没重建 → 浏览器里跑的还是旧代码），
  // 因此 Q06_FORCE_REBUILD=1 时与两个应用一样强制重建。
  const force = process.env.Q06_FORCE_REBUILD === '1';
  if (force || !(await exists(artifact))) {
    process.stdout.write('[q06] 构建 @vben/ai-embed-sdk 版本化产物\n');
    await run('pnpm', ['-F', '@vben/ai-embed-sdk', 'run', 'build']);
  }
  const info = await stat(artifact);
  if (info.size < 1024) {
    throw new Error(`SDK 产物异常（${info.size} 字节）：${artifact}`);
  }
  process.stdout.write(`[q06] SDK 产物 ${artifact}（${info.size} 字节）\n`);
  return artifact;
}

/** 宿主页按固定命名加载版本化产物；命名对不上就明确失败，避免静默用错产物。 */
async function verifyHostPageSdkImport(artifact) {
  const hostPage = await readFile(
    join(FIXTURES_DIR, 'pages/host-shell.js'),
    'utf8',
  );
  const match = hostPage.match(/\/sdk\/(ai-embed-sdk-[\d.]+\.js)/u);
  if (match === null) {
    throw new Error('宿主页没有声明 SDK 产物路径');
  }
  if (join(SDK_DIST_DIR, match[1]) !== artifact) {
    throw new Error(
      `宿主页加载 ${match[1]}，但当前产物是 ${artifact}（版本已变化，需同步宿主页）`,
    );
  }
}

async function buildShellBundle() {
  await mkdir(SHELL_BUNDLE_DIR, { recursive: true });
  await build({
    build: {
      emptyOutDir: true,
      lib: {
        entry: join(FIXTURES_DIR, 'shell/entry.mjs'),
        fileName: () => 'shell-bundle.js',
        formats: ['es'],
        name: 'Q06Shell',
      },
      minify: false,
      outDir: SHELL_BUNDLE_DIR,
      sourcemap: false,
      target: 'chrome120',
    },
    configFile: false,
    logLevel: 'warn',
    root: FRONTEND_ROOT,
  });
  const info = await stat(join(SHELL_BUNDLE_DIR, 'shell-bundle.js'));
  process.stdout.write(`[q06] 嵌入壳 bundle ${info.size} 字节\n`);
}

/**
 * Q07 韧性探针 bundle：入口 import 的是**真实**组件与解析（`ResultTable.vue`、`blocks.ts`）
 * 与真实 SDK 客户端；这里只负责把它打成浏览器能加载的一份产物。
 *
 * 与两个应用产物同样的新鲜度规则：`Q06_FORCE_REBUILD=1` 强制重建（改了 packages 源码后必须重建，
 * 否则浏览器里跑的是旧包）。
 */
async function buildProbeBundle(force) {
  if (!force && (await exists(Q07_PROBE_BUNDLE_FILE))) {
    return;
  }
  await mkdir(dirname(Q07_PROBE_BUNDLE_FILE), { recursive: true });
  await build({
    build: {
      emptyOutDir: true,
      lib: {
        entry: Q07_PROBE_ENTRY,
        fileName: () => 'probe-entry.js',
        formats: ['es'],
        name: 'Q07Probe',
      },
      minify: false,
      outDir: dirname(Q07_PROBE_BUNDLE_FILE),
      sourcemap: false,
      target: 'chrome120',
    },
    configFile: false,
    // vue 的 esm-bundler 产物读 process.env.NODE_ENV；浏览器里没有 process，构建期定死
    define: { 'process.env.NODE_ENV': JSON.stringify('production') },
    logLevel: 'warn',
    plugins: [vue()],
    root: FRONTEND_ROOT,
  });
  const info = await stat(Q07_PROBE_BUNDLE_FILE);
  process.stdout.write(`[q07] 韧性探针 bundle ${info.size} 字节\n`);
}

async function ensureAppDist({ distDir, env, force, label, packageName }) {
  const indexPath = join(distDir, 'index.html');
  if (force || !(await exists(indexPath))) {
    process.stdout.write(
      `[q06] 构建 ${packageName} 生产产物（接口基址 = 本地）\n`,
    );
    await run('pnpm', ['-F', packageName, 'run', 'build'], { env });
  }
  const files = await readdir(distDir);
  const assets = files.filter(
    (name) => name === 'assets' || name.endsWith('.js'),
  );
  process.stdout.write(
    `[q06] ${label} 产物 ${distDir}（${files.length} 项，assets=${assets.length}）\n`,
  );
  return indexPath;
}

export default async function globalSetup() {
  await mkdir(ARTIFACT_ROOT, { recursive: true });
  const force = process.env.Q06_FORCE_REBUILD === '1';
  const artifact = await ensureSdkArtifact();
  await verifyHostPageSdkImport(artifact);
  await buildShellBundle();
  await buildProbeBundle(force);
  await ensureAppDist({
    distDir: CHAT_APP_DIST_DIR,
    env: { VITE_AI_API_BASE_URL: `${PLATFORM_API_BASE}/app-api/ai/v1` },
    force,
    label: '独立 Chat',
    packageName: '@vben/ai-chat',
  });
  await ensureAppDist({
    distDir: ADMIN_DIST_DIR,
    env: { VITE_GLOB_API_URL: `${PLATFORM_API_BASE}/admin-api` },
    force,
    label: '管理端',
    packageName: '@vben/web-ele',
  });
}

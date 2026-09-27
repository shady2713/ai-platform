/**
 * AT-065 浏览器回归的 global setup：把探针入口用 vite 打成单文件 `probe.js`。
 *
 * <p>产物写在仓库根的 `.local-state/q08-compatibility/probe/`（已 gitignore，不提交二进制），
 * 与 Q06/Q07 的夹具产物同一口径；每次运行都重建，避免"源码改了但浏览器里跑旧包"的假通过。
 * 入口里 import 的是**真实组件**（`AiChart`/`ChartRenderer`/`AiReportView`）与真实 G2（懒加载被内联），
 * 只把"没有后端"的部分留在页面外——本探针不请求任何后端。
 */
import { mkdir, stat } from 'node:fs/promises';
import { dirname, join, resolve } from 'node:path';
import process from 'node:process';
import { fileURLToPath } from 'node:url';

import vue from '@vitejs/plugin-vue';
import { build } from 'vite';

const HERE = dirname(fileURLToPath(import.meta.url));
const FRONTEND_ROOT = resolve(HERE, '../../../..');
const ENTRY = join(HERE, 'entry.ts');
const OUT_DIR = resolve(
  FRONTEND_ROOT,
  '../../.local-state/q08-compatibility/probe',
);

export default async function globalSetup() {
  await mkdir(OUT_DIR, { recursive: true });
  await build({
    build: {
      emptyOutDir: true,
      lib: {
        entry: ENTRY,
        fileName: () => 'probe.js',
        formats: ['es'],
        name: 'Q08RenderProbe',
      },
      minify: false,
      outDir: OUT_DIR,
      rollupOptions: { output: { inlineDynamicImports: true } },
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
  const info = await stat(join(OUT_DIR, 'probe.js'));
  if (info.size < 1024) {
    throw new Error(
      `探针产物异常（${info.size} 字节）：${join(OUT_DIR, 'probe.js')}`,
    );
  }
  process.stdout.write(
    `[q08] 渲染探针产物 ${join(OUT_DIR, 'probe.js')}（${info.size} 字节）\n`,
  );
}

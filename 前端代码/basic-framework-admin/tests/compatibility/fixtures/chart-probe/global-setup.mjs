/**
 * AT-065 / Q11 浏览器回归的 global setup：把探针入口用 vite 打成单文件 `probe.js`。
 *
 * <p>产物写在仓库根的 `.local-state/q08-compatibility/probe/`（已 gitignore，不提交二进制），
 * 与 Q06/Q07 的夹具产物同一口径；每次运行都重建，避免"源码改了但浏览器里跑旧包"的假通过。
 * 入口里 import 的是**真实组件**（`AiChart`/`ChartRenderer`/`AiReportView`）与真实 G2（懒加载被内联），
 * 只把"没有后端"的部分留在页面外——本探针不请求任何后端。
 *
 * <p>Q11 追加：为管理端页面（`apps/web-ele/src/views/ai/**`）补一批 alias，
 * 把"应用外壳"（Pinia access store、vxe-table 网格、form 控件、popup 弹窗、HTTP 客户端）
 * 换成 `fixtures/chart-probe/admin/*` 下的桥接，页面组件本身仍然是**真实**的。
 * 被 alias 的 specifier 只有下面这些，其余（含 AT-065 依赖的 `packages/**`）解析行为不变。
 */
import { mkdir, stat } from 'node:fs/promises';
import { dirname, join, resolve } from 'node:path';
import process from 'node:process';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';

import vue from '@vitejs/plugin-vue';
import { build } from 'vite';

const HERE = dirname(fileURLToPath(import.meta.url));
const FRONTEND_ROOT = resolve(HERE, '../../../..');
const ENTRY = join(HERE, 'entry.ts');
const ADMIN = join(HERE, 'admin');
const OUT_DIR = resolve(
  FRONTEND_ROOT,
  '../../.local-state/q08-compatibility/probe',
);

/**
 * `zod` 与 lucide 图标 JSON 都不在本探针目录的依赖图里（pnpm 严格隔离）：
 *  - zod 只在 `@vben-core/form-ui` 下有，而页面的 `data.ts` 通过 `@vben/common-ui` 取 `z`；
 *  - `@iconify/json` 只声明在 `internal/tailwind-config` 下。
 * 用 `createRequire` 从各自真实属主现场解析，避免把版本号写死在探针里。
 * zod alias 指向**包目录**（不是 `require` 给的 CJS 入口），让 vite 自己挑 ESM 产物。
 */
const requireFromFormUi = createRequire(
  join(FRONTEND_ROOT, 'packages/@core/ui-kit/form-ui/package.json'),
);
const requireFromTailwind = createRequire(
  join(FRONTEND_ROOT, 'internal/tailwind-config/package.json'),
);
const ZOD_ENTRY = dirname(requireFromFormUi.resolve('zod/package.json'));
const LUCIDE_ICONS = requireFromTailwind.resolve(
  '@iconify/json/json/lucide.json',
);

/**
 * element-plus 的**中文语言包**。生产应用在 `#/locales` 里挂 `ElConfigProvider`，
 * 探针不复制那套应用外壳，但必须让浮层文案与生产一致：
 * 否则 ElPopconfirm 的确认按钮会渲染成默认英文 `Yes/No`，
 * 用例就会去断言一个生产界面永远不会出现的字符串。
 *
 * <p>用 `q11:` 虚拟 id 而不是改写 `element-plus` 本身：
 * `table-action.vue` 等产品代码对 `element-plus` 的解析必须保持原样，
 * `require.resolve` 给的又是 CJS 入口，插进去反而会破坏 vite 的 ESM 优选。
 */
const ELEMENT_PLUS = join(
  FRONTEND_ROOT,
  'apps/web-ele/node_modules/element-plus/es/index.mjs',
);
const ELEMENT_PLUS_ZH_CN = join(
  FRONTEND_ROOT,
  'apps/web-ele/node_modules/element-plus/es/locale/lang/zh-cn.mjs',
);

/** Q11：把应用外壳换成探针桥接；`TableAction`/`Page`/zod/词表仍是真实实现。 */
const Q11_ALIASES = [
  { find: /^zod$/, replacement: ZOD_ENTRY },
  { find: 'q11:lucide-icons', replacement: LUCIDE_ICONS },
  { find: 'q11:element-plus', replacement: ELEMENT_PLUS },
  { find: 'q11:element-plus-lang-zh-cn', replacement: ELEMENT_PLUS_ZH_CN },
  { find: /^@vben\/access$/, replacement: join(ADMIN, 'access-bridge.ts') },
  {
    find: /^@vben\/common-ui$/,
    replacement: join(ADMIN, 'common-ui-bridge.ts'),
  },
  { find: /^#\/locales$/, replacement: join(ADMIN, 'locales-bridge.ts') },
  {
    find: /^#\/utils\/feedback$/,
    replacement: join(ADMIN, 'feedback-bridge.ts'),
  },
  {
    find: /^#\/adapter\/vxe-table$/,
    replacement: join(ADMIN, 'grid-bridge.ts'),
  },
  { find: /^#\/adapter\/form$/, replacement: join(ADMIN, 'form-bridge.ts') },
  {
    find: /^#\/api\/ai\/semantic$/,
    replacement: join(ADMIN, 'api-semantic-bridge.ts'),
  },
  {
    find: /^#\/api\/ai\/grant$/,
    replacement: join(ADMIN, 'api-grant-bridge.ts'),
  },
  {
    find: /^#\/api\/ai\/data$/,
    replacement: join(ADMIN, 'api-data-bridge.ts'),
  },
  {
    find: /^#\/api\/ai\/model-endpoint$/,
    replacement: join(ADMIN, 'api-model-endpoint-bridge.ts'),
  },
  {
    find: /^#\/api\/ai\/report$/,
    replacement: join(ADMIN, 'api-report-bridge.ts'),
  },
  {
    find: /^#\/api\/ai\/open-platform$/,
    replacement: join(ADMIN, 'api-report-bridge.ts'),
  },
];

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
    resolve: { alias: Q11_ALIASES },
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

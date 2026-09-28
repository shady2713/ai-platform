import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

import Vue from '@vitejs/plugin-vue';
import { defineConfig } from 'vite';

/**
 * 组件级集成包的版本化产物（X09）。
 *
 * <p>与 `@vben/ai-embed-sdk` 的产物口径一致：**自托管、固定命名、不使用公共 CDN**。
 * 入口文件名为 `ai-web-component-<version>.js`；Vue 与 ChatUI 一并内联（宿主不需要自己的构建链），
 * 图表厂商（`@antv/g2`）仍走动态 import，产物里保留为带版本号的独立分包（R02 的懒加载口径）。
 */
const pkg = JSON.parse(
  readFileSync(new URL('package.json', import.meta.url), 'utf8'),
) as {
  version: string;
};

const CHUNK_NAME = `ai-web-component-${pkg.version}.[hash].chunk.js`;

export default defineConfig({
  // 以包目录为 root：无论从仓库根还是包目录调用，产物路径都一致
  root: fileURLToPath(new URL('.', import.meta.url)),
  plugins: [Vue()],
  build: {
    // 每次构建清空产物目录：包体预算与"同目录 .css"的推导都依赖目录里只有当前版本
    emptyOutDir: true,
    lib: {
      entry: 'src/index.ts',
      fileName: () => `ai-web-component-${pkg.version}.js`,
      formats: ['es'],
      name: 'AiWebComponent',
    },
    // 依赖内联：宿主只需要按固定命名托管本包产物（不需要自己的打包器与依赖图）
    // 注意：Vite 的 ES 库模式会保留空白与注释（不压 whitespace），压缩由 build 脚本的
    // `scripts/minify-artifact.mjs` 后置完成（见该脚本头部的说明）
    minify: false,
    outDir: 'dist',
    rollupOptions: {
      external: [],
      output: {
        // 样式资源与入口同名（元素按 `import.meta.url` 推导同目录 .css 并注入 Shadow DOM）
        assetFileNames: `ai-web-component-${pkg.version}.[ext]`,
        chunkFileNames: CHUNK_NAME,
      },
    },
    sourcemap: false,
    target: 'es2020',
  },
});

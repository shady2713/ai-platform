import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

import { defineConfig } from 'vite';

/**
 * SDK 版本化产物（C10）：自托管、固定版本路径、内容哈希命名的单文件 ESM/IIFE 包。
 *
 * <p>为什么需要产物而不是只用源码：第三方宿主不能（也不应）编译平台源码，
 * 只能加载**固定版本路径**的静态脚本；构建产物的文件名带版本与内容哈希，
 * 缓存与"一个部署内 SDK 与 Chat 协议兼容 N/N-1"都以它为准。
 */
const pkg = JSON.parse(
  readFileSync(new URL('package.json', import.meta.url), 'utf8'),
) as {
  version: string;
};

export default defineConfig({
  // 以包目录为 root：无论从仓库根还是包目录调用，产物路径都一致
  root: fileURLToPath(new URL('.', import.meta.url)),
  build: {
    emptyOutDir: false,
    lib: {
      entry: 'src/index.ts',
      fileName: () => `ai-embed-sdk-${pkg.version}.js`,
      formats: ['es'],
      name: 'AiEmbedSdk',
    },
    outDir: 'dist',
    // 依赖内联：宿主只需要一个文件（zod 之外的运行时代码都在这里）
    rollupOptions: { external: [] },
    sourcemap: false,
  },
});

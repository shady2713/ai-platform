import { fileURLToPath } from 'node:url';

import vue from '@vitejs/plugin-vue';
import { defineConfig } from 'vite';

/** Vue 宿主示例：独立构建（不使用管理端配置），产物可被任意静态服务器托管。 */
export default defineConfig({
  plugins: [vue()],
  root: fileURLToPath(new URL('.', import.meta.url)),
  build: {
    outDir: 'dist',
    rollupOptions: { external: [] },
  },
  server: { port: 5181 },
});

import vue from '@vitejs/plugin-vue';
import { defineConfig } from 'vite';

/**
 * 独立 Chat 的 Vite 配置：刻意不使用 @vben/vite-config。
 *
 * 该共享配置会注入管理端的公开运行时配置（VITE_GLOB_API_URL / window._VBEN_ADMIN_PRO_APP_CONF_），
 * 而独立 Chat 按架构约束不加载后台路由、管理权限与 ADMIN refresh 逻辑，
 * 只通过 VITE_AI_API_BASE_URL 访问开放 API。
 */
export default defineConfig({
  build: {
    outDir: 'dist',
    rollupOptions: {
      output: {
        // 分离图表库，便于记录包体与缓存；图表体积是 F04 验证项之一
        manualChunks: (id: string) => {
          if (id.includes('@antv')) {
            return 'vendor-antv';
          }
          if (
            id.includes('node_modules/vue') ||
            id.includes('node_modules/@vue')
          ) {
            return 'vendor-vue';
          }
          return undefined;
        },
      },
    },
    sourcemap: false,
  },
  plugins: [vue()],
  server: {
    port: 5175,
  },
});

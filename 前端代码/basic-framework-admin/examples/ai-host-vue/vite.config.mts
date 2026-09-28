import { readFile } from 'node:fs/promises';
import { join } from 'node:path';
import process from 'node:process';
import { fileURLToPath } from 'node:url';

import vue from '@vitejs/plugin-vue';
import { defineConfig } from 'vite';

/**
 * Vue 宿主示例：独立构建（不使用管理端配置），产物可被任意静态服务器托管。
 *
 * <p>X09 追加两件事（都只在示例里，不进产品代码）：
 * <ol>
 *   <li>`/component/*`：按固定命名提供**版本化组件产物**（供组件路径加载；生产由宿主静态托管）；</li>
 *   <li>`/app-api` 开发代理：组件路径的应用端请求由宿主的网关转发（示例里用 vite 代理代替网关）。</li>
 * </ol>
 */
const COMPONENT_DIST = fileURLToPath(
  new URL('../../packages/ai-web-component/dist', import.meta.url),
);
const COMPONENT_FILE_PATTERN =
  /^ai-web-component-\d+\.\d+\.\d+(?:(?:\.[\w-]+)*\.js|\.css)$/u;
const PLATFORM_BASE = process.env.AI_PLATFORM_BASE ?? 'http://localhost:48080';

/** 提供版本化组件产物：只认固定命名（与 C10 的示例后端同一口径）。 */
function serveComponentArtifacts() {
  const serve = (server: {
    middlewares: {
      use: (
        handler: (
          request: { url?: string },
          response: {
            end: (body: unknown) => void;
            setHeader: (name: string, value: string) => void;
            statusCode: number;
          },
          next: () => void,
        ) => void,
      ) => void;
    };
  }) => {
    server.middlewares.use(async (request, response, next) => {
      const path = request.url ?? '';
      if (!path.startsWith('/component/')) {
        next();
        return;
      }
      const name = path.slice('/component/'.length).split('?')[0] ?? '';
      if (!COMPONENT_FILE_PATTERN.test(name) || name.includes('/')) {
        response.statusCode = 404;
        response.end('component artifact not found');
        return;
      }
      try {
        const body = await readFile(join(COMPONENT_DIST, name));
        response.setHeader(
          'content-type',
          name.endsWith('.css')
            ? 'text/css; charset=utf-8'
            : 'text/javascript; charset=utf-8',
        );
        response.setHeader(
          'cache-control',
          'public, max-age=31536000, immutable',
        );
        response.setHeader('x-content-type-options', 'nosniff');
        response.end(body);
      } catch {
        response.statusCode = 503;
        response.end('component artifact unreadable（先构建组件包）');
      }
    });
  };
  return {
    configurePreviewServer: serve,
    configureServer: serve,
    name: 'serve-ai-web-component-artifacts',
  };
}

export default defineConfig({
  plugins: [vue(), serveComponentArtifacts()],
  root: fileURLToPath(new URL('.', import.meta.url)),
  build: {
    outDir: 'dist',
    rollupOptions: { external: [] },
  },
  server: {
    port: 5181,
    proxy: {
      // 组件路径的应用端请求：宿主网关的示例形态（生产由宿主自己的后端转发）
      '/app-api': { changeOrigin: true, target: PLATFORM_BASE },
    },
  },
  preview: {
    proxy: {
      '/app-api': { changeOrigin: true, target: PLATFORM_BASE },
    },
  },
});

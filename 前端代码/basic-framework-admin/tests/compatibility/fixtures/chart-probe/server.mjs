/**
 * Q08 渲染探针的静态服务（只监听 127.0.0.1，只提供本探针产物）。
 *
 * <p>最小职责：返回探针页面、返回 vite 打出的 `probe.js`、提供一个健康检查端点。
 * 不提供 SDK 产物、不换票、不代理任何外部请求（与本切片"禁公共 CDN/禁外部写入"一致）。
 */
import { createReadStream, existsSync } from 'node:fs';
import { createServer } from 'node:http';
import { dirname, join, resolve } from 'node:path';
import process from 'node:process';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const frontendRoot = resolve(here, '../../../..');
const pageFile = join(here, 'page.html');
/** 探针产物由 global-setup 现场构建到 `.local-state/q08-compatibility/probe/`（gitignore）。 */
const probeFile = resolve(
  frontendRoot,
  '../../.local-state/q08-compatibility/probe/probe.js',
);
const PORT = Number(process.env.Q08_PROBE_PORT ?? 5399);
const HOST = '127.0.0.1';

const server = createServer((request, response) => {
  const path = (request.url ?? '/').split('?')[0];
  if (path === '/health') {
    response.writeHead(200, { 'content-type': 'text/plain; charset=utf-8' });
    response.end('q08-chart-probe-ready');
    return;
  }
  const file =
    path === '/' || path === '/page.html'
      ? pageFile
      : path === '/probe.js'
        ? probeFile
        : null;
  if (file === null || !existsSync(file)) {
    response.writeHead(404, { 'content-type': 'text/plain; charset=utf-8' });
    response.end('not found');
    return;
  }
  response.writeHead(200, {
    'cache-control': 'no-store',
    'content-type': file.endsWith('.js')
      ? 'text/javascript; charset=utf-8'
      : 'text/html; charset=utf-8',
  });
  createReadStream(file).pipe(response);
});

server.listen(PORT, HOST, () => {
  process.stdout.write(`[q08] 渲染探针：http://${HOST}:${PORT}/\n`);
});

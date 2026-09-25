import { readdir, readFile } from 'node:fs/promises';
/**
 * 最小宿主示例后端（C10）。
 *
 * 三个职责：把宿主页面与**版本化 SDK 产物**作为静态资源提供、用应用客户端凭据换取短期票据、
 * 以及把跨源请求限制在配置的宿主 Origin 上。
 *
 * 它**不是**生产匿名换票代理，四条硬约束写在代码里（并有单测）：
 *   1. 没有配置凭据就直接失败（503），不提供"谁都能换票"的端点；
 *   2. 只允许配置的宿主 Origin（其他 Origin 一律 403，且不回通配 CORS）；
 *   3. 平台基址来自服务端配置，客户端不能指定（避免被当成任意转发器）；
 *   4. 响应里不含 appSecret，也不回显任何凭据字段。
 */
import { createServer } from 'node:http';
import { dirname, join, resolve } from 'node:path';
import process from 'node:process';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const publicDir = join(here, 'public');
const sdkDistDir = resolve(here, '../../packages/ai-embed-sdk/dist');

const SDK_FILE_PATTERN = /^ai-embed-sdk-\d+\.\d+\.\d+\.js$/u;
const STATIC_FILES = new Map([
  ['/', ['index.html', 'text/html; charset=utf-8']],
  ['/host.js', ['host.js', 'text/javascript; charset=utf-8']],
]);

/** 读取配置：缺任一项即视为未配置（服务端只失败，不猜测）。 */
export function readConfig(env = process.env) {
  return {
    appCode: env.AI_APP_CODE ?? '',
    appSecret: env.AI_APP_SECRET ?? '',
    hostOrigin: env.AI_HOST_ORIGIN ?? '',
    platformBase: env.AI_PLATFORM_BASE ?? '',
  };
}

export function isConfigured(config) {
  return Boolean(
    config.appCode &&
    config.appSecret &&
    config.hostOrigin &&
    config.platformBase,
  );
}

/**
 * 兼容旧版运行时：`readdir(dir, { recursive: true })` 在部分环境不可用，这里只列一层。
 */
async function listSdkArtifacts() {
  try {
    const files = await readdir(sdkDistDir);
    return files.filter((name) => SDK_FILE_PATTERN.test(name)).toSorted();
  } catch {
    return [];
  }
}

/**
 * 请求处理器（可单测）：不启动端口，只做路由与约束判定。
 */
export function createHandler(options = {}) {
  const config = options.config ?? readConfig();
  const fetchImpl = options.fetch ?? globalThis.fetch;
  const now = options.now ?? (() => Date.now());

  return async function handle(request, response) {
    const url = new URL(request.url ?? '/', 'http://localhost');
    const origin = request.headers.origin ?? '';

    if (!isConfigured(config)) {
      respond(response, 503, {
        body: '宿主后端未配置应用凭据（AI_APP_CODE/AI_APP_SECRET/AI_HOST_ORIGIN/AI_PLATFORM_BASE）',
        contentType: 'text/plain; charset=utf-8',
      });
      return;
    }

    // 换票端点：只接受来自配置 Origin 的跨源调用
    if (url.pathname === '/your-backend/ai-ticket') {
      if (request.method !== 'POST') {
        respond(response, 405, {
          body: 'method not allowed',
          contentType: 'text/plain; charset=utf-8',
        });
        return;
      }
      if (origin !== config.hostOrigin) {
        respond(response, 403, {
          body: 'origin not allowed',
          contentType: 'text/plain; charset=utf-8',
        });
        return;
      }
      try {
        const upstream = await fetchImpl(
          `${config.platformBase}/app-api/ai/auth/ticket`,
          {
            body: JSON.stringify({
              appCode: config.appCode,
              appSecret: config.appSecret,
            }),
            headers: { 'content-type': 'application/json' },
            method: 'POST',
          },
        );
        if (!upstream.ok) {
          // 上游失败原样暴露状态码，不回显上游正文（可能含内部信息）
          respond(response, upstream.status, {
            body: 'ticket exchange failed',
            contentType: 'text/plain; charset=utf-8',
          });
          return;
        }
        const payload = await upstream.json();
        const data = payload?.data ?? {};
        respond(response, 200, {
          body: JSON.stringify({
            expiresAt: data.expiresAt,
            token: data.token,
          }),
          contentType: 'application/json',
          extraHeaders: { 'cache-control': 'no-store' },
        });
      } catch {
        respond(response, 502, {
          body: 'ticket exchange failed',
          contentType: 'text/plain; charset=utf-8',
        });
      }
      return;
    }

    // 版本化 SDK 产物：只提供固定命名（含版本号）的文件，长缓存
    if (url.pathname.startsWith('/sdk/')) {
      const name = url.pathname.slice('/sdk/'.length);
      const artifacts = await listSdkArtifacts();
      if (!SDK_FILE_PATTERN.test(name) || !artifacts.includes(name)) {
        respond(response, 404, {
          body: 'sdk artifact not found',
          contentType: 'text/plain; charset=utf-8',
        });
        return;
      }
      try {
        const body = await readFile(join(sdkDistDir, name));
        respond(response, 200, {
          body,
          contentType: 'text/javascript; charset=utf-8',
          extraHeaders: {
            'cache-control': 'public, max-age=31536000, immutable',
          },
        });
      } catch {
        respond(response, 503, {
          body: 'sdk artifact unreadable',
          contentType: 'text/plain; charset=utf-8',
        });
      }
      return;
    }

    const staticFile = STATIC_FILES.get(url.pathname);
    if (staticFile !== undefined) {
      const [name, contentType] = staticFile;
      try {
        const body = await readFile(join(publicDir, name));
        respond(response, 200, {
          body,
          contentType,
          extraHeaders: {
            'cache-control': 'no-store',
            'x-sample-time': String(now()),
          },
        });
      } catch {
        respond(response, 500, {
          body: 'host page missing',
          contentType: 'text/plain; charset=utf-8',
        });
      }
      return;
    }

    respond(response, 404, {
      body: 'not found',
      contentType: 'text/plain; charset=utf-8',
    });
  };
}

function respond(response, status, { body, contentType, extraHeaders = {} }) {
  response.writeHead(status, {
    'content-type': contentType,
    'x-content-type-options': 'nosniff',
    ...extraHeaders,
  });
  response.end(body);
}

export async function main() {
  const config = readConfig();
  const port = Number(process.env.AI_HOST_PORT ?? 5180);
  const server = createServer(createHandler({ config }));
  server.listen(port, () => {
    process.stdout.write(
      `宿主示例已启动：http://localhost:${port}（宿主 Origin=${config.hostOrigin || '未配置'}；SDK 产物目录=${sdkDistDir}）\n`,
    );
  });
  return server;
}

if (import.meta.url === `file://${process.argv[1]}`) {
  await main();
}

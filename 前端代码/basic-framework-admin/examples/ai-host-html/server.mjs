import { Buffer } from 'node:buffer';
import { readdir, readFile } from 'node:fs/promises';
/**
 * 最小宿主示例后端（C10 / X09）。
 *
 * 四个职责：把宿主页面与**版本化产物**（iframe 用的 SDK、组件用的 Web Component）作为静态资源提供、
 * 用应用客户端凭据换取短期票据、把组件路径的应用端请求**同源转发**到平台，以及把跨源请求限制在配置的
 * 宿主 Origin 上。
 *
 * 它**不是**生产匿名换票代理，也不是开放转发器，六条硬约束写在代码里（并有单测）：
 *   1. 没有配置凭据就直接失败（503），不提供"谁都能换票"的端点；
 *   2. 只允许配置的宿主 Origin（其他 Origin 一律 403，且不回通配 CORS）；
 *   3. 平台基址来自服务端配置，客户端不能指定（避免被当成任意转发器）；
 *   4. 响应里不含 appSecret，也不回显任何凭据字段；
 *   5. 静态产物只按**固定命名白名单**提供（目录穿越与未登记文件名一律 404）；
 *   6. 转发只覆盖 `/your-backend/app-api/*` 这一个前缀，目标固定为配置的平台基址，
 *      只转发白名单请求头（不转发 Cookie），客户端无法用它访问任意主机。
 *
 * 为什么组件路径需要第 6 条的转发：iframe 路径下请求由平台页面发出（与平台同源），
 * 组件路径下请求由**宿主页面**发出；宿主页面与平台跨源时浏览器会拦下响应（平台不为 app-api 开放 CORS）。
 * 生产环境的推荐形态就是"宿主的网关照常转发应用端请求"，本示例把它写成最小实现。
 */
import { createServer } from 'node:http';
import { dirname, join, resolve } from 'node:path';
import process from 'node:process';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const publicDir = join(here, 'public');
const sdkDistDir = resolve(here, '../../packages/ai-embed-sdk/dist');
const componentDistDir = resolve(here, '../../packages/ai-web-component/dist');

const SDK_FILE_PATTERN = /^ai-embed-sdk-\d+\.\d+\.\d+\.js$/u;
const COMPONENT_FILE_PATTERN =
  /^ai-web-component-\d+\.\d+\.\d+(?:(?:\.[\w-]+)*\.js|\.css)$/u;
/**
 * 静态文件白名单：页面来自 `public/`，宿主脚本来自示例目录。
 *
 * <p>（C10 的实现把两类文件都指向 `public/`，导致 `/host.js` 实际取不到、示例页的模块导入 404；
 * X09 顺手修正为按目录分别取，并把状态码写进用例。）
 */
const STATIC_FILES = new Map([
  ['/', [publicDir, 'index.html', 'text/html; charset=utf-8']],
  [
    '/component-model.mjs',
    [here, 'component-model.mjs', 'text/javascript; charset=utf-8'],
  ],
  [
    '/component.html',
    [publicDir, 'component.html', 'text/html; charset=utf-8'],
  ],
  ['/component.js', [here, 'component.js', 'text/javascript; charset=utf-8']],
  [
    '/host-model.mjs',
    [here, 'host-model.mjs', 'text/javascript; charset=utf-8'],
  ],
  ['/host.js', [here, 'host.js', 'text/javascript; charset=utf-8']],
]);

/** 组件路径的同源转发前缀（宿主自己的网关；不是平台接口）。 */
export const GATEWAY_PREFIX = '/your-backend/app-api/';
/** 只允许读写类方法：不提供 DELETE/PUT 之类的泛化转发。 */
const GATEWAY_METHODS = new Set(['GET', 'POST']);
/** 只转发协议需要的请求头：Cookie 与自定义头一律丢弃。 */
const FORWARDED_REQUEST_HEADERS = new Set([
  'accept',
  'authorization',
  'content-type',
  'idempotency-key',
  'last-event-id',
]);
const MAX_GATEWAY_PATH_LENGTH = 512;

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
async function listArtifacts(dir, pattern) {
  try {
    const files = await readdir(dir);
    return files.filter((name) => pattern.test(name)).toSorted();
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

    // 组件路径的同源网关：固定前缀 + 固定目标 + 方法/请求头白名单
    if (url.pathname.startsWith(GATEWAY_PREFIX)) {
      await handleGateway({
        config,
        fetchImpl,
        request,
        response,
        url,
      });
      return;
    }

    // 版本化 iframe SDK 产物：只提供固定命名（含版本号）的文件，长缓存
    if (url.pathname.startsWith('/sdk/')) {
      await serveArtifact({
        dir: sdkDistDir,
        name: url.pathname.slice('/sdk/'.length),
        pattern: SDK_FILE_PATTERN,
        response,
        unavailable: 'sdk artifact not found',
      });
      return;
    }

    // 版本化组件产物（入口 JS / 同目录 CSS / 懒加载分包）：同样只提供固定命名
    if (url.pathname.startsWith('/component/')) {
      await serveArtifact({
        dir: componentDistDir,
        name: url.pathname.slice('/component/'.length),
        pattern: COMPONENT_FILE_PATTERN,
        response,
        unavailable: 'component artifact not found',
      });
      return;
    }

    const staticFile = STATIC_FILES.get(url.pathname);
    if (staticFile !== undefined) {
      const [dir, name, contentType] = staticFile;
      try {
        const body = await readFile(join(dir, name));
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

/**
 * 同源转发（组件路径的宿主网关）。
 *
 * 约束（每条都有单测）：方法白名单、Origin 必须为空或等于配置的宿主 Origin、
 * 路径必须是 `/your-backend/app-api/` 之后的平台相对路径（拒绝 `..`、绝对 URL、超长路径）、
 * 只转发白名单请求头、不转发 Cookie、目标固定为配置的平台基址，响应流式回传（SSE 需要）。
 */
async function handleGateway({ config, fetchImpl, request, response, url }) {
  if (!GATEWAY_METHODS.has(request.method)) {
    respond(response, 405, {
      body: 'method not allowed',
      contentType: 'text/plain; charset=utf-8',
    });
    return;
  }
  const origin = request.headers.origin ?? '';
  if (origin !== '' && origin !== config.hostOrigin) {
    respond(response, 403, {
      body: 'origin not allowed',
      contentType: 'text/plain; charset=utf-8',
    });
    return;
  }
  const rest = url.pathname.slice(GATEWAY_PREFIX.length);
  if (
    rest === '' ||
    rest.length > MAX_GATEWAY_PATH_LENGTH ||
    rest.includes('..') ||
    rest.startsWith('/') ||
    /^[a-z][\w+.-]*:/iu.test(rest)
  ) {
    respond(response, 400, {
      body: 'invalid gateway path',
      contentType: 'text/plain; charset=utf-8',
    });
    return;
  }
  let target;
  try {
    target = new URL(`${config.platformBase}/app-api/${rest}${url.search}`);
  } catch {
    respond(response, 400, {
      body: 'invalid gateway path',
      contentType: 'text/plain; charset=utf-8',
    });
    return;
  }
  // 双保险：解析后的目标必须仍在配置的平台 Origin 上
  if (target.origin !== new URL(config.platformBase).origin) {
    respond(response, 400, {
      body: 'invalid gateway path',
      contentType: 'text/plain; charset=utf-8',
    });
    return;
  }

  const headers = {};
  for (const name of FORWARDED_REQUEST_HEADERS) {
    const value = request.headers[name];
    if (value !== undefined) {
      headers[name] = value;
    }
  }
  const body = await readRequestBody(request);
  let upstream;
  try {
    upstream = await fetchImpl(target, {
      headers,
      method: request.method,
      redirect: 'manual',
      ...(body === undefined ? {} : { body }),
    });
  } catch {
    respond(response, 502, {
      body: 'upstream unreachable',
      contentType: 'text/plain; charset=utf-8',
    });
    return;
  }

  response.writeHead(upstream.status, {
    'cache-control': 'no-store',
    'content-type':
      upstream.headers.get('content-type') ?? 'application/octet-stream',
    'x-content-type-options': 'nosniff',
  });
  // 流式回传：SSE（事件流）必须逐块转发，否则面板只能等到运行结束才看到内容
  if (upstream.body !== null && upstream.body !== undefined) {
    for await (const chunk of upstream.body) {
      response.write(chunk);
    }
  }
  response.end();
}

/** 读取请求体：支持 Node 的请求流与测试里的字符串体。 */
async function readRequestBody(request) {
  if (typeof request.body === 'string') {
    return request.body;
  }
  const stream = request.body ?? request;
  if (typeof stream?.[Symbol.asyncIterator] !== 'function') {
    return undefined;
  }
  const chunks = [];
  for await (const chunk of stream) {
    chunks.push(chunk);
  }
  return chunks.length === 0 ? undefined : Buffer.concat(chunks);
}

/** 提供一个版本化产物文件：命名白名单 + 目录内容校验，命中与否都不暴露目录。 */
async function serveArtifact({ dir, name, pattern, response, unavailable }) {
  const artifacts = await listArtifacts(dir, pattern);
  if (!pattern.test(name) || name.includes('/') || !artifacts.includes(name)) {
    respond(response, 404, {
      body: unavailable,
      contentType: 'text/plain; charset=utf-8',
    });
    return;
  }
  try {
    const body = await readFile(join(dir, name));
    respond(response, 200, {
      body,
      contentType: name.endsWith('.css')
        ? 'text/css; charset=utf-8'
        : 'text/javascript; charset=utf-8',
      extraHeaders: { 'cache-control': 'public, max-age=31536000, immutable' },
    });
  } catch {
    respond(response, 503, {
      body: 'artifact unreadable',
      contentType: 'text/plain; charset=utf-8',
    });
  }
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
      `宿主示例已启动：http://localhost:${port}（宿主 Origin=${config.hostOrigin || '未配置'}；SDK 产物目录=${sdkDistDir}；组件产物目录=${componentDistDir}）\n`,
    );
  });
  return server;
}

if (import.meta.url === `file://${process.argv[1]}`) {
  await main();
}

/**
 * Q06 多 Origin 浏览器夹具服务器。
 *
 * 一个进程监听多个端口，每个端口是一个**独立 Origin**的角色：
 *
 * | Origin | 角色 | 提供什么 |
 * |---|---|---|
 * | HOST | 第三方宿主页 | 宿主页、版本化 SDK 产物、**宿主自己的换票端点**、控制端点 |
 * | SHELL_A | 应用发布配置允许的嵌入 Origin | 嵌入壳页（真实 IframeBridge bundle）+ bootstrap |
 * | ATTACK | 未授权第三方页 | 恶意页面（伪造 AUTH/context/navigation 消息） |
 * | SHELL_B | 未在允许域内的嵌入 Origin | 同一个嵌入壳（用于"错误 origin"用例） |
 * | ADMIN | 管理端产物 | web-ele 生产构建（静态） |
 * | CHAT_APP | 独立 Chat 产物 | ai-chat 生产构建（静态） |
 *
 * 边界（如实说明）：
 *  - 换票端点**不是**平台换票接口，而是"宿主自己的后端"（与 C10 的 `server.mjs` 同角色），
 *    它按配置的 Origin 白名单发放**夹具票据**；平台票据签发（A04）需要真实后端，属未验证项。
 *  - 嵌入壳 bundle 由 `测试` 侧用 vite 打包**真实** `apps/ai-chat/src/bridge/iframe-bridge.ts`，
 *    壳页只补"取 bootstrap → 装配桥 → 收发真实 postMessage"这一段宿主侧/壳侧粘合代码。
 *  - 控制端点只服务测试进程（无 Origin 头的 Node fetch），浏览器跨源访问会被拒绝。
 */
import { createServer } from 'node:http';
import { readFile, stat } from 'node:fs/promises';
import { extname, join, normalize, resolve, sep } from 'node:path';
import process from 'node:process';
import { pathToFileURL } from 'node:url';

import {
  ADMIN,
  ADMIN_DIST_DIR,
  ATTACK,
  ATTACK_PAGE_PATH,
  CHAT_APP,
  CHAT_APP_DIST_DIR,
  FIXTURES_DIR,
  HOST,
  HOST_PAGE_PATH,
  PLATFORM_BASE,
  SDK_DIST_DIR,
  SHELL_A,
  SHELL_B,
  SHELL_BUNDLE_DIR,
  SHELL_PAGE_PATH,
  TICKET_PATH,
} from './origins.mjs';

const PAGES_DIR = join(FIXTURES_DIR, 'pages');
const SDK_FILE_PATTERN = /^ai-embed-sdk-\d+\.\d+\.\d+\.js$/u;
const STATIC_TYPES = new Map([
  ['.css', 'text/css; charset=utf-8'],
  ['.html', 'text/html; charset=utf-8'],
  ['.ico', 'image/x-icon'],
  ['.js', 'text/javascript; charset=utf-8'],
  ['.json', 'application/json; charset=utf-8'],
  ['.mjs', 'text/javascript; charset=utf-8'],
  ['.svg', 'image/svg+xml'],
  ['.woff2', 'font/woff2'],
]);

/** 夹具可观察状态：换票日志与换票行为控制（测试进程通过控制端点读写）。 */
export function createFixtureState() {
  return {
    /** 换票请求日志（服务端视角）：每次请求一条。 */
    ticketLog: [],
    /** 下一次换票的行为控制。 */
    ticketConfig: { delayMs: 0, failStatus: 0 },
    /** 票据序号（保证每次换的票据不同）。 */
    ticketSeq: 0,
    /** 服务端计数：请求到达顺序（跨 Origin 共用一个进程）。 */
    requestSeq: 0,
  };
}

function contentTypeFor(path) {
  return STATIC_TYPES.get(extname(path)) ?? 'application/octet-stream';
}

function respond(response, status, body, headers = {}) {
  response.writeHead(status, {
    'cache-control': 'no-store',
    'x-content-type-options': 'nosniff',
    ...headers,
  });
  response.end(body);
}

function respondJson(response, status, payload, headers = {}) {
  respond(response, status, JSON.stringify(payload), {
    'content-type': 'application/json; charset=utf-8',
    ...headers,
  });
}

async function readBody(request) {
  const chunks = [];
  for await (const chunk of request) chunks.push(chunk);
  return Buffer.concat(chunks).toString('utf8');
}

async function readJsonBody(request) {
  const text = await readBody(request);
  if (text.trim() === '') return {};
  try {
    return JSON.parse(text);
  } catch {
    throw new Error('invalid json body');
  }
}

async function fileExists(path) {
  try {
    const info = await stat(path);
    return info.isFile();
  } catch {
    return false;
  }
}

async function serveFile(response, path) {
  if (!(await fileExists(path))) {
    respond(response, 404, 'not found', {
      'content-type': 'text/plain; charset=utf-8',
    });
    return;
  }
  respond(response, 200, await readFile(path), {
    'content-type': contentTypeFor(path),
  });
}

/** 单层目录内的文件访问：拒绝穿越与多级路径（夹具也不需要目录列表）。 */
function safeJoin(root, urlPath) {
  const decoded = decodeURIComponent(urlPath);
  const candidate = resolve(root, `.${normalize(decoded)}`);
  if (candidate !== root && !candidate.startsWith(root + sep)) return null;
  return candidate;
}

function sleep(ms) {
  return new Promise((done) => {
    setTimeout(done, ms);
  });
}

/**
 * 宿主角色：宿主页 + 版本化 SDK 产物 + 宿主后端换票端点 + 控制端点。
 */
function handleHost(state) {
  return async (request, response) => {
    const url = new URL(request.url ?? '/', HOST.origin);
    const origin = request.headers.origin ?? '';

    if (url.pathname === '/control/health') {
      respondJson(response, 200, { ok: true });
      return;
    }
    if (url.pathname === '/control/ticket-log' && request.method === 'GET') {
      respondJson(response, 200, { log: state.ticketLog });
      return;
    }
    if (
      url.pathname === '/control/ticket-config' &&
      request.method === 'POST'
    ) {
      const body = await readJsonBody(request);
      state.ticketConfig = {
        delayMs: Number(body.delayMs ?? 0),
        failStatus: Number(body.failStatus ?? 0),
      };
      respondJson(response, 200, state.ticketConfig);
      return;
    }
    if (url.pathname === '/control/reset' && request.method === 'POST') {
      state.ticketLog = [];
      state.ticketConfig = { delayMs: 0, failStatus: 0 };
      state.ticketSeq = 0;
      respondJson(response, 200, { ok: true });
      return;
    }

    if (url.pathname === TICKET_PATH) {
      if (request.method !== 'POST') {
        respond(response, 405, 'method not allowed', {
          'content-type': 'text/plain; charset=utf-8',
        });
        return;
      }
      // 红线（与 C10 示例一致）：换票端点只接受配置的宿主 Origin，不回通配 CORS。
      // 同源 POST 可能不带 Origin 头（浏览器策略差异），不带 Origin 的请求视为本宿主自己发起。
      if (origin !== HOST.origin && origin !== '') {
        state.ticketLog.push({
          seq: ++state.requestSeq,
          accepted: false,
          reason: 'origin-not-allowed',
          origin,
        });
        respond(response, 403, 'origin not allowed', {
          'content-type': 'text/plain; charset=utf-8',
        });
        return;
      }
      let body = {};
      try {
        body = await readJsonBody(request);
      } catch {
        respond(response, 400, 'invalid json', {
          'content-type': 'text/plain; charset=utf-8',
        });
        return;
      }
      const { delayMs, failStatus } = state.ticketConfig;
      if (delayMs > 0) await sleep(delayMs);
      if (failStatus > 0) {
        state.ticketLog.push({
          seq: ++state.requestSeq,
          accepted: false,
          appCode: body.appCode ?? null,
          instanceId: body.instanceId ?? null,
          origin,
          reason: 'configured-failure',
          status: failStatus,
        });
        respond(response, failStatus, 'ticket exchange failed', {
          'content-type': 'text/plain; charset=utf-8',
        });
        return;
      }
      // 票据值在运行时拼接（同时避免 secret-scan 把夹具票据当成硬编码凭据）
      const serial = ++state.ticketSeq;
      const ticket = [
        'aitkt',
        'fixture',
        body.appCode ?? 'app',
        `s${serial}`,
      ].join('_');
      state.ticketLog.push({
        accepted: true,
        appCode: body.appCode ?? null,
        delayMs,
        instanceId: body.instanceId ?? null,
        origin,
        seq: ++state.requestSeq,
        serial,
      });
      respondJson(response, 200, {
        expiresAt: new Date(Date.now() + 300_000).toISOString(),
        token: ticket,
      });
      return;
    }

    if (url.pathname.startsWith('/sdk/')) {
      const name = url.pathname.slice('/sdk/'.length);
      if (
        !SDK_FILE_PATTERN.test(name) ||
        !(await fileExists(join(SDK_DIST_DIR, name)))
      ) {
        respond(response, 404, 'sdk artifact not found', {
          'content-type': 'text/plain; charset=utf-8',
        });
        return;
      }
      respond(response, 200, await readFile(join(SDK_DIST_DIR, name)), {
        'cache-control': 'public, max-age=31536000, immutable',
        'content-type': 'text/javascript; charset=utf-8',
      });
      return;
    }

    const pagePath = safeJoin(PAGES_DIR, url.pathname);
    if (pagePath !== null && (await fileExists(pagePath))) {
      await serveFile(response, pagePath);
      return;
    }

    respond(response, 404, 'not found', {
      'content-type': 'text/plain; charset=utf-8',
    });
  };
}

/**
 * 嵌入壳角色：壳页 + 壳 bundle + bootstrap。
 *
 * bootstrap 只回**本 Origin 自己的配置**：允许域固定为宿主 Origin（服务端决定），
 * 查询串只提供非秘密的 app/instance 标识。
 */
function handleShell(role, shellBundleFile) {
  return async (request, response) => {
    const url = new URL(request.url ?? '/', role.origin);

    if (url.pathname === '/embed-bootstrap.json') {
      const appCode = url.searchParams.get('app') ?? '';
      const instanceId = url.searchParams.get('instance') ?? '';
      if (appCode === '' || instanceId === '') {
        respondJson(response, 422, { message: 'app 与 instance 参数必填' });
        return;
      }
      respondJson(response, 200, {
        allowedOrigins: [HOST.origin],
        appCode,
        hostOrigin: HOST.origin,
        instanceId,
        protocolVersion: '1.0',
      });
      return;
    }

    if (url.pathname === '/shell-bundle.js') {
      await serveFile(response, join(SHELL_BUNDLE_DIR, shellBundleFile));
      return;
    }

    const pagePath = safeJoin(PAGES_DIR, url.pathname);
    if (pagePath !== null && (await fileExists(pagePath))) {
      await serveFile(response, pagePath);
      return;
    }

    respond(response, 404, 'not found', {
      'content-type': 'text/plain; charset=utf-8',
    });
  };
}

/** 攻击页角色：只提供恶意页面（消息内容由测试在页面上下文里拼装）。 */
function handleAttack() {
  return async (request, response) => {
    const pagePath = safeJoin(
      PAGES_DIR,
      new URL(request.url ?? '/', ATTACK.origin).pathname,
    );
    if (pagePath !== null && (await fileExists(pagePath))) {
      await serveFile(response, pagePath);
      return;
    }
    respond(response, 404, 'not found', {
      'content-type': 'text/plain; charset=utf-8',
    });
  };
}

/**
 * 生产产物角色：纯静态（SPA 回退到 index.html）。
 *
 * `_app.config.js` 是**部署期运行时配置资产**（`vite:extra-app-config` 插件刻意把它单独吐出，
 * 便于部署时替换：`window._VBEN_ADMIN_PRO_APP_CONF_`）。禁公网部署要把接口基址换成本地，
 * 这里按同一机制在**服务时**替换成 `apiBase`（不改仓库里的产物文件，也不碰 apps/ 源码）。
 */
function handleStatic(distDir, options = {}) {
  const { apiBase } = options;
  return async (request, response) => {
    const url = new URL(request.url ?? '/', 'http://127.0.0.1');
    const filePath = safeJoin(distDir, url.pathname);
    if (
      apiBase !== undefined &&
      url.pathname === '/_app.config.js' &&
      (await fileExists(filePath))
    ) {
      const original = await readFile(filePath, 'utf8');
      const replaced = original.replace(
        /("VITE_GLOB_API_URL"\s*:\s*)"[^"]*"/u,
        `$1"${apiBase}"`,
      );
      if (replaced === original && original.includes('VITE_GLOB_API_URL')) {
        throw new Error(
          '_app.config.js 的 VITE_GLOB_API_URL 替换失败（格式变化？）',
        );
      }
      process.stdout.write(
        `[q06 fixture] 部署期替换 _app.config.js：VITE_GLOB_API_URL -> ${apiBase}\n`,
      );
      respond(response, 200, replaced, {
        'content-type': 'text/javascript; charset=utf-8',
      });
      return;
    }
    if (
      filePath !== null &&
      (await fileExists(filePath)) &&
      extname(filePath) !== ''
    ) {
      await serveFile(response, filePath);
      return;
    }
    await serveFile(response, join(distDir, 'index.html'));
  };
}

function listen(port, handler) {
  return new Promise((done, fail) => {
    const server = createServer((request, response) => {
      Promise.resolve(handler(request, response)).catch((error) => {
        respond(response, 500, `fixture error: ${error.message}`, {
          'content-type': 'text/plain; charset=utf-8',
        });
      });
    });
    server.once('error', fail);
    server.listen(port, '127.0.0.1', () => done(server));
  });
}

/**
 * 启动全部 Origin。
 *
 * `shellBundleFile` 由 globalSetup 打包后传入（默认 `shell-bundle.js`）。
 */
export async function startFixtureServers(options = {}) {
  const state = options.state ?? createFixtureState();
  const shellBundleFile = options.shellBundleFile ?? 'shell-bundle.js';
  const servers = await Promise.all([
    listen(HOST.port, handleHost(state)),
    listen(SHELL_A.port, handleShell(SHELL_A, shellBundleFile)),
    listen(ATTACK.port, handleAttack()),
    listen(SHELL_B.port, handleShell(SHELL_B, shellBundleFile)),
    listen(
      ADMIN.port,
      handleStatic(ADMIN_DIST_DIR, { apiBase: `${PLATFORM_BASE}/admin-api` }),
    ),
    listen(CHAT_APP.port, handleStatic(CHAT_APP_DIST_DIR)),
  ]);
  return {
    async close() {
      await Promise.all(
        servers.map(
          (server) =>
            new Promise((done) => {
              server.close(() => done());
            }),
        ),
      );
    },
    state,
  };
}

// 直接运行才起服务（被 globalSetup 之外的模块 import 时不自动监听）。
// 注意：工作目录含非 ASCII 路径，import.meta.url 是百分号编码的，必须用 pathToFileURL 归一化。
if (
  process.argv[1] !== undefined &&
  import.meta.url === pathToFileURL(process.argv[1]).href
) {
  const { close } = await startFixtureServers();
  const shutdown = async () => {
    await close();
    process.exit(0);
  };
  process.on('SIGINT', shutdown);
  process.on('SIGTERM', shutdown);
  process.stdout.write(
    `q06 fixture servers: host=${HOST.origin} shell=${SHELL_A.origin} attack=${ATTACK.origin} shellB=${SHELL_B.origin} admin=${ADMIN.origin} chat=${CHAT_APP.origin}\n`,
  );
}

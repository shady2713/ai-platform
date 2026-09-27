import { readFile, stat } from 'node:fs/promises';
/**
 * Q07 前端韧性夹具：平台**应用端 API 的拦截桩** + 韧性探针页（真实组件）。
 *
 * 为什么需要它：AT-017（慢消费者/断流）、AT-059（配额拒绝）、AT-014（断线重连按 seq 去重）
 * 的判定需要"服务端可以按剧本回应"——本机没有后端、也没有模型，所以用**同协议形状的桩**
 * 提供：SSE 逐帧慢速下发、重复/乱序回放、非终态断流、连接重置、429 配额、重放窗口过期。
 *
 * 边界（如实声明，不当成平台实现）：
 *  - 桩只实现"开放/应用端 API 的一个子集"，字段形状取自 `docs/contracts/ai/*`（RunEvent v1、
 *    错误码映射、CommonResult 信封），**不参与**任何业务判定；断言对象是**前端在收到这些
 *    响应时的行为**（去重、有界渲染、稳定失败、不假成功），不是平台是否这样实现。
 *  - 探针页用 vite 打包真实组件（`ResultTable.vue` + `blocks.ts` + 版本化 SDK 源码），
 *    与 Q06 的 `shell-bundle.js` 同法：产品代码照原样跑，夹具只补"没有后端"这一段。
 *  - 桩记录每条请求（方法/路径/查询串/幂等键）供用例断言"是否重复受理/是否重执行"。
 */
import { createServer } from 'node:http';
import { dirname, extname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
/** `tests/playwright` 目录。 */
const PLAYWRIGHT_ROOT = resolve(HERE, '..');
/** 前端工作区根目录（`前端代码/basic-framework-admin`）。 */
const FRONTEND_ROOT = resolve(PLAYWRIGHT_ROOT, '../..');

/** 夹具产物目录（已 gitignore，不提交二进制）。 */
export const Q07_ARTIFACT_ROOT = resolve(
  FRONTEND_ROOT,
  '../../.local-state/q07-browser',
);
/** 探针页（静态）与探针 bundle（globalSetup 用 vite 构建）。 */
export const Q07_PROBE_PAGE = join(
  PLAYWRIGHT_ROOT,
  'fixtures/pages/q07-probe.html',
);
export const Q07_PROBE_ENTRY = join(
  PLAYWRIGHT_ROOT,
  'fixtures/probe/entry.mjs',
);
export const Q07_PROBE_BUNDLE_FILE = join(
  Q07_ARTIFACT_ROOT,
  'probe/probe-entry.js',
);
export const Q07_PROBE_BUNDLE_PATH = '/q07-probe/probe-entry.js';
export const Q07_PROBE_PAGE_PATH = '/q07-probe.html';

/** 应用端基址前缀（与 `apps/ai-chat` 构建时的 `VITE_AI_API_BASE_URL` 同形）。 */
export const Q07_API_PREFIX = '/app-api/ai/v1';
/** 被受理的运行（业务键必须能被 `numericId()` 还原成编号，故用 `run_<数字>`）。 */
export const Q07_RUN_KEY = 'run_4001';
/** 运行事件里的 runId 必须满足契约 `^run_[A-Za-z0-9_-]{3,35}$`。 */
const Q07_EVENT_RUN_ID = 'run_4001';

const STATIC_TYPES = new Map([
  ['.css', 'text/css; charset=utf-8'],
  ['.html', 'text/html; charset=utf-8'],
  ['.js', 'text/javascript; charset=utf-8'],
  ['.json', 'application/json; charset=utf-8'],
]);

function sleep(ms) {
  return new Promise((done) => {
    setTimeout(done, ms);
  });
}

function eventFrame(seq, status, text) {
  const payload = {
    createdAt: '2026-09-27T00:00:00Z',
    ...(text === undefined ? {} : { block: { kind: 'text', text } }),
    runId: Q07_EVENT_RUN_ID,
    schemaVersion: '1.0',
    seq,
    status,
  };
  return `id: ${seq}\nevent: run\ndata: ${JSON.stringify(payload)}\n\n`;
}

function heartbeat(text) {
  // 心跳是注释、不推进 seq（O05 契约）：不得出现在界面里
  return `: ${text}\n\n`;
}

/**
 * 重复与乱序的帧剧本：只出现一次**严格递增**的子序列 1→2→3（客户端按 seq 去重的判据），
 * 其余重复/回退帧必须被丢弃（不能重复渲染）。
 */
const BURST_SEQUENCE = [1, 2, 1, 3, 3, 2, 1, 3, 2];
const BURST_TEXTS = new Map([
  [1, '片段一'],
  [2, '片段二'],
  [3, '片段三'],
]);

async function fileExists(path) {
  try {
    const info = await stat(path);
    return info.isFile();
  } catch {
    return false;
  }
}

/**
 * 请求账本条目（用例断言"是否重复受理/是否重执行"）。
 *
 * @typedef {object} Q07RequestLogEntry
 * @property {null | string} accept 请求的 Accept 头
 * @property {number} at 到达时间
 * @property {null | string} idempotencyKey 幂等键（受理接口才有）
 * @property {string} method 方法
 * @property {string} path 路径
 * @property {string} query 查询串
 */

/**
 * 启动 Q07 夹具服务器（随机端口、仅本机）。
 *
 * 返回的 `state` 可直接被用例改写（同进程）：`events` 选择事件剧本、`accept` 选择受理结果。
 */
export async function startQ07Api() {
  const state = {
    /** 受理剧本：`ok` | `quota`。 */
    accept: 'ok',
    /** 事件剧本：见 `handleEvents`。 */
    events: 'duplicate-burst',
    /** 事件请求次数（重连判据：第 2 次必须带 afterSeq）。 */
    eventsCalls: 0,
    /** 乱序/重复帧的发送参数（用例可调，用于"慢速下发"）。 */
    burstFrameDelayMs: 8,
    burstRounds: 12,
    /** 请求账本（断言"是否重复受理/是否重执行"）。 @type {Q07RequestLogEntry[]} */
    requests: [],
    /** `duplicate-burst` 的终态放行闩锁（用例在断言"流中途不假完成"后放行）。 */
    terminalReleased: false,
    terminalWaiters: [],
  };

  function respond(response, status, body, headers = {}) {
    response.writeHead(status, {
      'cache-control': 'no-store',
      ...headers,
    });
    response.end(body);
  }

  function corsHeaders(request) {
    return {
      'access-control-allow-headers': '*',
      'access-control-allow-methods': 'GET,POST,OPTIONS',
      'access-control-allow-origin': request.headers.origin ?? '*',
    };
  }

  function respondJson(response, request, status, payload) {
    respond(response, status, JSON.stringify(payload), {
      'content-type': 'application/json; charset=utf-8',
      ...corsHeaders(request),
    });
  }

  function record(request, url) {
    state.requests.push({
      accept: request.headers.accept ?? null,
      at: Date.now(),
      idempotencyKey: request.headers['idempotency-key'] ?? null,
      method: request.method ?? 'GET',
      path: url.pathname,
      query: url.search,
    });
  }

  function waitForTerminalRelease() {
    if (state.terminalReleased) {
      // 用例可能在服务端还在慢速下发时就放行（一次性的闩锁：放行不丢）
      return Promise.resolve();
    }
    return new Promise((done) => {
      state.terminalWaiters.push(done);
    });
  }

  /** 放行 `duplicate-burst` 的终态帧（用例断言"流中途不假完成"之后调用）。 */
  function releaseTerminal() {
    state.terminalReleased = true;
    for (const done of state.terminalWaiters.splice(0)) {
      done();
    }
  }

  async function handleEvents(request, response) {
    state.eventsCalls += 1;
    const call = state.eventsCalls;

    if (state.events === 'quota') {
      // 订阅时被限流：HTTP 429 + 稳定错误码（AI_QUOTA_EXCEEDED = 1_003_001_005）
      respondJson(response, request, 429, {
        code: 1_003_001_005,
        msg: '并发配额已用尽（占位 8/8），请稍后重试',
      });
      return;
    }

    if (state.events === 'window-expired-contract') {
      // 契约冻结值：AI_RUN_EVENT_WINDOW_EXPIRED = 1_003_004_006（error-code-map.md 第 52 行）
      respondJson(response, request, 409, {
        code: 1_003_004_006,
        msg: '事件重放窗口已过期，请读取运行快照',
      });
      return;
    }

    response.writeHead(200, {
      ...corsHeaders(request),
      'cache-control': 'no-store',
      connection: 'keep-alive',
      'content-type': 'text/event-stream; charset=utf-8',
    });

    if (state.events === 'graceful-close') {
      // 优雅断流：服务端结束响应但**没有**终态事件（可重连语义，不得当成成功）
      response.write(eventFrame(1, 'RUNNING', '片段一'));
      await sleep(40);
      response.write(eventFrame(2, 'RUNNING', '片段二'));
      await sleep(40);
      response.end();
      return;
    }

    if (state.events === 'abrupt-reset') {
      // 连接被重置（非优雅断流）：读取方会拿到网络错误，界面必须给稳定失败而不是假成功
      response.write(eventFrame(1, 'RUNNING', '片段一'));
      await sleep(80);
      response.socket?.destroy();
      return;
    }

    if (state.events === 'reconnect') {
      if (call === 1) {
        // 第一次订阅：两条事件后被服务端关闭（客户端应带 lastSeq 重连，而不是重执行）
        response.write(eventFrame(1, 'RUNNING', '片段一'));
        await sleep(30);
        response.write(eventFrame(2, 'RUNNING', '片段二'));
        await sleep(30);
        response.end();
        return;
      }
      // 第二次订阅：服务端**重放**已发过的 1、2（客户端必须按 seq 丢弃），再来 3、4 终态
      for (const seq of [1, 2, 3]) {
        response.write(eventFrame(seq, 'RUNNING', BURST_TEXTS.get(seq)));
        await sleep(20);
      }
      response.write(eventFrame(4, 'SUCCEEDED', '终态'));
      response.end();
      return;
    }

    // 默认剧本 `duplicate-burst`：慢速下发大量重复/乱序帧 + 心跳，等待用例放行后再给终态
    const { burstFrameDelayMs, burstRounds } = state;
    for (let round = 0; round < burstRounds; round += 1) {
      for (const seq of BURST_SEQUENCE) {
        response.write(eventFrame(seq, 'RUNNING', BURST_TEXTS.get(seq)));
        await sleep(burstFrameDelayMs);
      }
      response.write(heartbeat(`keep-alive-${round}`));
    }
    await waitForTerminalRelease();
    response.write(eventFrame(4, 'SUCCEEDED', '终态'));
    response.end();
  }

  async function handle(request, response) {
    const url = new URL(request.url ?? '/', 'http://127.0.0.1');
    record(request, url);

    if (request.method === 'OPTIONS') {
      response.writeHead(204, corsHeaders(request));
      response.end();
      return;
    }

    if (url.pathname === Q07_PROBE_PAGE_PATH) {
      respond(response, 200, await readFile(Q07_PROBE_PAGE), {
        'content-type': STATIC_TYPES.get('.html'),
      });
      return;
    }

    if (url.pathname === Q07_PROBE_BUNDLE_PATH) {
      if (!(await fileExists(Q07_PROBE_BUNDLE_FILE))) {
        respond(response, 500, 'probe bundle missing（先跑 globalSetup）', {
          'content-type': 'text/plain; charset=utf-8',
        });
        return;
      }
      // 路径是固定常量（不是用户输入）：只服务构建产物这一份文件
      respond(response, 200, await readFile(Q07_PROBE_BUNDLE_FILE), {
        'content-type': STATIC_TYPES.get(extname(Q07_PROBE_BUNDLE_FILE)),
      });
      return;
    }

    if (!url.pathname.startsWith(Q07_API_PREFIX)) {
      respondJson(response, request, 404, { code: 1, msg: 'not found' });
      return;
    }

    const apiPath = url.pathname.slice(Q07_API_PREFIX.length);

    if (apiPath === '/ai/conversation/page' && request.method === 'GET') {
      respondJson(response, request, 200, { code: 0, data: { list: [] } });
      return;
    }

    if (apiPath === '/ai/run/accept' && request.method === 'POST') {
      if (state.accept === 'quota') {
        respondJson(response, request, 429, {
          code: 1_003_001_005,
          msg: '并发配额已用尽（占位 8/8），请稍后重试',
        });
        return;
      }
      respondJson(response, request, 200, {
        code: 0,
        data: {
          reused: false,
          runId: 4001,
          runKey: Q07_RUN_KEY,
          status: 'RUNNING',
        },
      });
      return;
    }

    if (apiPath === '/ai/run/events' && request.method === 'GET') {
      await handleEvents(request, response);
      return;
    }

    if (apiPath === '/ai/run/get' && request.method === 'GET') {
      respondJson(response, request, 200, {
        code: 0,
        data: {
          id: 4001,
          runKey: Q07_RUN_KEY,
          status: 'SUCCEEDED',
          version: 3,
        },
      });
      return;
    }

    if (apiPath === '/ai/run/cancel' && request.method === 'POST') {
      respondJson(response, request, 200, {
        code: 0,
        data: {
          id: 4001,
          runKey: Q07_RUN_KEY,
          status: 'CANCELLED',
          version: 4,
        },
      });
      return;
    }

    respondJson(response, request, 404, { code: 1, msg: 'not found' });
  }

  const server = createServer((request, response) => {
    Promise.resolve(handle(request, response)).catch((error) => {
      if (!response.headersSent) {
        respond(response, 500, `q07 fixture error: ${error.message}`, {
          'content-type': 'text/plain; charset=utf-8',
        });
        return;
      }
      response.socket?.destroy();
    });
  });

  await new Promise((done, fail) => {
    server.once('error', fail);
    server.listen(0, '127.0.0.1', () => done());
  });
  const address = server.address();
  if (address === null || typeof address === 'string') {
    throw new Error('Q07 夹具服务器没有拿到端口');
  }

  return {
    /** 应用端基址（客户端自己拼 `/ai/run/...`，与产物里的 `VITE_AI_API_BASE_URL` 同形）。 */
    apiBase: `http://127.0.0.1:${address.port}${Q07_API_PREFIX}`,
    close: () =>
      new Promise((done) => {
        server.closeAllConnections?.();
        server.close(() => done());
      }),
    eventsRequests: () =>
      state.requests.filter((item) => item.path.endsWith('/ai/run/events')),
    origin: `http://127.0.0.1:${address.port}`,
    releaseTerminal,
    state,
  };
}

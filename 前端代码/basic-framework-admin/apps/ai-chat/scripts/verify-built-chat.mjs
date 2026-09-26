/**
 * Chat 生产构建产物验证（F04 验收项 2/3）。
 *
 * 为什么需要这个脚本：生产构建成功只说明"能打包"，不说明"能渲染"。本脚本直接用
 * `dist/index.html` 引用的真实产物在 DOM 环境里挂载应用，并通过注入的 HTTP 边界驱动一次
 * "新建会话 → 发送 → SSE 事件返回文本与图表结果块"的真实渲染，最后断言图表分包确实存在、
 * 走懒加载且被记录体积。
 *
 * 边界与诚实性：
 *  - 网络是外部边界，脚本注入 fetch 桩（不打真实后端）；除 fetch/store 外流程全用构建产物；
 *  - 真实浏览器渲染（G2 canvas、真实 CSP 报错采集、窄屏/键盘）属 Q06/G5，本脚本不声称已覆盖；
 *    G2 在无 canvas 的 DOM 环境里会按适配层约定降级为表格，因此断言"图表或降级表格"二者之一。
 *
 * 用法（在 前端代码/basic-framework-admin 下执行）：
 *   pnpm -F @vben/ai-chat run build
 *   node apps/ai-chat/scripts/verify-built-chat.mjs
 */
import { readdir, readFile, stat } from 'node:fs/promises';
import { join, resolve } from 'node:path';
import process from 'node:process';
import { pathToFileURL } from 'node:url';
import { gzipSync } from 'node:zlib';

const APP_ROOT = resolve(import.meta.dirname, '..');
const DIST_DIR = resolve(APP_ROOT, argumentValue('--dist') ?? 'dist');
const CHART_CHUNK_PATTERN = /vendor-antv-[\w-]+\.js$/;
/** G2 的稳定代码标识（压缩后仍保留）：theta 坐标系、interval 标记、G2 类名。 */
const CHART_CHUNK_MARKERS = ['interval', 'theta', 'G2'];
const failures = [];

function argumentValue(name) {
  const index = process.argv.indexOf(name);
  return index === -1 ? undefined : process.argv[index + 1];
}

function write(line) {
  process.stdout.write(`${line}\n`);
}

function writeError(line) {
  process.stderr.write(`${line}\n`);
}

function fail(message) {
  failures.push(message);
  writeError(`  ☒ ${message}`);
}

function pass(message) {
  write(`  ☑ ${message}`);
}

function formatSize(bytes) {
  return `${(bytes / 1024).toFixed(2)} kB`;
}

async function listFiles(directory) {
  const entries = await readdir(directory, { withFileTypes: true });
  const files = [];
  for (const entry of entries) {
    const path = join(directory, entry.name);
    if (entry.isDirectory()) {
      files.push(...(await listFiles(path)));
    } else {
      files.push(path);
    }
  }
  return files;
}

/** 1. 产物结构：index.html 只引用本地资源，入口 chunk 存在。 */
async function checkDistShape(html, files) {
  write('\n[1/3] 产物结构');
  const externalReferences = [
    ...html.matchAll(/(?:src|href)\s*=\s*"([^"]+)"/g),
  ].map((match) => match[1]);
  const external = externalReferences.filter((reference) =>
    /^[a-z][\w+.-]*:/i.test(reference),
  );
  if (external.length > 0) {
    fail(`index.html 引用了外部资源：${external.join(', ')}`);
  } else {
    pass(
      `index.html 仅引用本地资源（${externalReferences.length} 条：${externalReferences.join(', ')}）`,
    );
  }
  if (!html.includes('id="app"')) {
    fail('index.html 缺少 #app 挂载容器');
  }
  const entryMatch = html.match(/src="\/(assets\/index-[\w-]+\.js)"/);
  const entryAsset = entryMatch?.[1];
  if (!entryAsset) {
    fail('index.html 未引用入口 chunk');
    return { entryPath: undefined };
  }
  const entryPath = files.find((file) => file.endsWith(entryAsset));
  if (!entryPath) {
    fail(`入口 chunk 不在产物中：${entryAsset}`);
    return { entryPath: undefined };
  }
  pass(`入口 chunk：${entryAsset}`);
  return { entryPath };
}

/** 2. 图表分包：非空、懒加载、被记录体积。 */
async function checkChartChunk(files, entryPath, html) {
  write('\n[2/3] 图表分包与体积');
  const chartChunks = files.filter((file) => CHART_CHUNK_PATTERN.test(file));
  if (chartChunks.length === 0) {
    fail('产物中没有图表分包（@antv/g2 未进入构建，图表集成未生效）');
    return;
  }
  for (const chunk of chartChunks) {
    const { size } = await stat(chunk);
    const content = await readFile(chunk, 'utf8');
    const gzip = gzipSync(content).length;
    const markers = CHART_CHUNK_MARKERS.filter((marker) =>
      content.includes(marker),
    );
    if (size === 0 || markers.length !== CHART_CHUNK_MARKERS.length) {
      fail(
        `图表分包 ${chunk.split('/').at(-1)} 不完整：${size} 字节，标记 ${markers.join('/') || '无'}`,
      );
      continue;
    }
    pass(
      `图表分包 ${chunk.split('/').at(-1)}：${formatSize(size)}（gzip ${formatSize(gzip)}）`,
    );
  }
  const entrySource = entryPath ? await readFile(entryPath, 'utf8') : '';
  for (const chunk of chartChunks) {
    const name = chunk.split('/').at(-1);
    if (!entrySource.includes(name)) {
      fail(`入口 chunk 未引用图表分包 ${name}`);
    } else if (
      new RegExp(String.raw`from\s*["']\./${name}["']`).test(entrySource)
    ) {
      fail(`图表分包被静态导入（${name}），首屏会为图表付出体积`);
    } else {
      pass(`图表分包由入口动态 import（懒加载），首屏不为图表付体积`);
    }
    if (html.includes(name)) {
      fail(`index.html 预加载了图表分包 ${name}`);
    }
  }
}

/** 3. 构建产物真实渲染 + 结果块（文本/图表）端到端。 */
async function checkBuiltRender(entryPath) {
  write('\n[3/3] 构建产物真实渲染');
  if (!entryPath) {
    fail('缺少入口 chunk，无法进行渲染验证');
    return;
  }
  const { Window } = await import('happy-dom');
  const window = new Window({ url: 'https://ai.test/' });
  const document = window.document;
  document.body.innerHTML = '<div id="app"></div>';

  const chartSpec = {
    categories: ['一月', '二月'],
    series: [{ data: [120, 200], name: '销售额' }],
    title: '月度销售额',
    type: 'bar',
  };
  const streamFrames = [
    {
      block: { kind: 'text', text: '华东前十结果如下' },
      createdAt: '2026-09-26T10:00:00Z',
      runId: 'run_demo1',
      schemaVersion: '1.0',
      seq: 1,
      status: 'RUNNING',
    },
    {
      block: { kind: 'chart', spec: chartSpec },
      createdAt: '2026-09-26T10:00:01Z',
      runId: 'run_demo1',
      schemaVersion: '1.0',
      seq: 2,
      status: 'RUNNING',
    },
    {
      createdAt: '2026-09-26T10:00:02Z',
      runId: 'run_demo1',
      schemaVersion: '1.0',
      seq: 3,
      status: 'SUCCEEDED',
    },
  ];
  const requests = [];
  const fetchStub = createFetchStub({ requests, streamFrames });

  installGlobals(window, fetchStub);
  try {
    await import(pathToFileURL(entryPath).href);
    await waitFor(
      () => document.querySelector('[data-testid="ai-conversation"]') !== null,
      '会话面板未渲染',
    );
  } catch (error) {
    fail(
      `挂载构建产物失败：${error instanceof Error ? error.message : String(error)}`,
    );
    return;
  }

  const heading = document.querySelector('h1')?.textContent?.trim();
  heading === 'AI 助手'
    ? pass(`应用外壳渲染：h1=${heading}`)
    : fail(`应用外壳标题不符：${heading}`);

  const status = document
    .querySelector('[data-testid="ai-chat-status"]')
    ?.textContent?.trim();
  if (status !== '就绪') {
    fail(`生产构建未配置开放 API 基址（状态：${status}），无法驱动运行链路`);
    return;
  }
  pass(`开放 API 基址已配置（状态：${status}）`);

  await waitFor(
    () =>
      document.querySelectorAll('[data-testid="ai-conversation-item"]').length >
      0,
    '会话列表未按接口数据渲染',
  );
  const listItem = document
    .querySelector('[data-testid="ai-conversation-item"]')
    ?.textContent?.trim();
  pass(`会话列表按接口数据渲染：${listItem}`);

  await click(document.querySelector('[data-testid="ai-conversation-create"]'));
  const input = document.querySelector('[data-testid="ai-conversation-input"]');
  input.value = '上个月华东前十';
  input.dispatchEvent(new window.Event('input', { bubbles: true }));
  const composeForm = document
    .querySelector('[data-testid="ai-conversation-send"]')
    ?.closest('form');

  // 无 canvas 的 DOM 环境里 G2 会抛"缺少浏览器能力"的错误，适配层按约定降级为表格；
  // 渲染窗口内把这类降级告警汇总成一行，其它错误照常输出。
  const degradationWarnings = muteDegradationWarnings();
  composeForm.dispatchEvent(
    new window.Event('submit', { bubbles: true, cancelable: true }),
  );

  try {
    await waitFor(
      () => document.body.textContent?.includes('华东前十结果如下') === true,
      '运行事件携带的文本块未渲染',
    );
    pass('运行事件携带的文本块渲染到消息区');

    await waitFor(
      () =>
        document.querySelector('[data-testid="ai-chart-canvas"]') !== null ||
        document.querySelector('[data-testid="ai-chart-table"]') !== null,
      '图表结果块既没有图表实例也没有降级表格',
    );
    const chartFigure = document.querySelector(
      '[data-testid="ai-conversation-chart"]',
    );
    const chartMode = document.querySelector('[data-testid="ai-chart-canvas"]')
      ? '图表实例（厂商渲染）'
      : '降级表格（当前 DOM 环境无 canvas，按适配层约定降级）';
    if (chartFigure?.textContent?.includes('月度销售额')) {
      pass(`图表结果块渲染：${chartMode}`);
    } else {
      fail('图表块未渲染图表标题');
    }
    if (document.body.textContent?.includes('结果块')) {
      fail('消息区仍出现旧占位文本（结果块未被渲染）');
    } else {
      pass('消息区无占位文本（图表/文本块都由共享组件渲染）');
    }
  } catch (error) {
    fail(error instanceof Error ? error.message : String(error));
    writeError(`  已发生的请求：${requests.join(' | ')}`);
    writeError(
      `  消息区文本：${document.body.textContent?.trim().slice(0, 400)}`,
    );
  } finally {
    await settle();
    degradationWarnings.restore();
    if (degradationWarnings.count > 0) {
      pass(
        `渲染期降级告警 ${degradationWarnings.count} 条已汇总（G2 在无 canvas 环境按约定降级为表格）`,
      );
    }
  }
  write(`  请求桩命中：${requests.join(' | ')}`);
}

/** 渲染窗口内汇总"缺少浏览器能力"的告警，避免噪声淹没证据；其它错误原样输出。 */
function muteDegradationWarnings() {
  const state = { count: 0 };
  const degradationPattern =
    /is not defined|getContext|canvas|Failed to execute|not implemented/i;
  const originals = { error: console.error, warn: console.warn };
  const wrap =
    (original) =>
    (...args) => {
      const text = args
        .map((argument) =>
          argument instanceof Error ? argument.message : String(argument),
        )
        .join(' ');
      if (degradationPattern.test(text)) {
        state.count += 1;
        return;
      }
      original(...args);
    };
  console.error = wrap(originals.error);
  console.warn = wrap(originals.warn);
  return {
    get count() {
      return state.count;
    },
    restore() {
      console.error = originals.error;
      console.warn = originals.warn;
    },
  };
}

function createFetchStub({ requests, streamFrames }) {
  const encoder = new TextEncoder();
  const json = (data) =>
    Response.json(
      { code: 0, data },
      {
        headers: { 'content-type': 'application/json' },
        status: 200,
      },
    );
  const sse = () =>
    new Response(
      new ReadableStream({
        start(controller) {
          for (const frame of streamFrames) {
            controller.enqueue(
              encoder.encode(`data: ${JSON.stringify(frame)}\n\n`),
            );
          }
          controller.close();
        },
      }),
      { headers: { 'content-type': 'text/event-stream' }, status: 200 },
    );
  return async function fetchStub(input, init = {}) {
    const url = new URL(
      typeof input === 'string' ? input : input.url,
      'https://ai.test/',
    );
    const route = `${init.method ?? 'GET'} ${url.pathname}`;
    requests.push(route);
    if (url.pathname.endsWith('/ai/conversation/page')) {
      return json({
        list: [{ conversationKey: 'conv_1', id: 1, title: '销售分析' }],
      });
    }
    if (url.pathname.endsWith('/ai/conversation/create')) {
      return json({ conversationKey: 'conv_2', id: 2, title: '新会话' });
    }
    if (url.pathname.endsWith('/ai/run/accept')) {
      return json({
        reused: false,
        runId: 1,
        runKey: 'run_demo1',
        status: 'QUEUED',
      });
    }
    if (url.pathname.endsWith('/ai/run/events')) {
      return sse();
    }
    return Response.json(
      { code: 404, msg: route },
      {
        headers: { 'content-type': 'application/json' },
        status: 404,
      },
    );
  };
}

/**
 * 安装 DOM 全局；Vue 运行时与适配层在"模块初始化 + 挂载"两处都要用到这些全局，
 * 因此必须在 import 构建产物之前完成。
 */
function installGlobals(window, fetchStub) {
  const globals = {
    cancelAnimationFrame: (handle) => clearTimeout(handle),
    CustomEvent: window.CustomEvent,
    document: window.document,
    Document: window.Document,
    DocumentFragment: window.DocumentFragment,
    Element: window.Element,
    Event: window.Event,
    fetch: fetchStub,
    getComputedStyle: window.getComputedStyle.bind(window),
    HTMLCanvasElement: window.HTMLCanvasElement,
    HTMLElement: window.HTMLElement,
    HTMLImageElement: window.HTMLImageElement,
    Image: window.Image,
    InputEvent: window.InputEvent,
    KeyboardEvent: window.KeyboardEvent,
    localStorage: window.localStorage,
    location: window.location,
    matchMedia:
      window.matchMedia?.bind(window) ??
      (() => ({
        addEventListener() {},
        matches: false,
        removeEventListener() {},
      })),
    MouseEvent: window.MouseEvent,
    MutationObserver: window.MutationObserver,
    navigator: window.navigator,
    Node: window.Node,
    requestAnimationFrame: (callback) =>
      setTimeout(() => callback(Date.now()), 0),
    ResizeObserver:
      window.ResizeObserver ??
      class {
        disconnect() {}
        observe() {}
        unobserve() {}
      },
    sessionStorage: window.sessionStorage,
    SVGElement: window.SVGElement,
    Text: window.Text,
    window,
  };
  for (const [key, value] of Object.entries(globals)) {
    Object.defineProperty(globalThis, key, {
      configurable: true,
      value,
      writable: true,
    });
  }
}

async function click(element) {
  element.dispatchEvent(
    new Event('click', { bubbles: true, cancelable: true }),
  );
  await settle();
}

async function settle(milliseconds = 20) {
  await new Promise((resolvePromise) =>
    setTimeout(resolvePromise, milliseconds),
  );
}

/** 轮询等待条件成立（构建产物里的异步渲染由微任务/定时器驱动）。 */
async function waitFor(predicate, failureMessage, timeout = 5000) {
  const deadline = Date.now() + timeout;
  for (;;) {
    if (predicate()) {
      return;
    }
    if (Date.now() > deadline) {
      throw new Error(failureMessage);
    }
    await settle();
  }
}

async function main() {
  write(`构建产物验证：${DIST_DIR}`);
  const html = await readFile(join(DIST_DIR, 'index.html'), 'utf8').catch(
    () => undefined,
  );
  if (html === undefined) {
    fail('缺少 dist/index.html：请先执行 pnpm -F @vben/ai-chat run build');
  } else {
    const files = await listFiles(DIST_DIR);
    const { entryPath } = await checkDistShape(html, files);
    await checkChartChunk(files, entryPath, html);
    await checkBuiltRender(entryPath);
  }
  if (failures.length > 0) {
    writeError(`\n构建产物验证失败：${failures.length} 项`);
    process.exitCode = 1;
    return;
  }
  write('\n构建产物验证通过：结构/图表分包/真实渲染全部符合预期');
}

try {
  await main();
} catch (error) {
  writeError(
    `构建产物验证异常：${error instanceof Error ? (error.stack ?? error.message) : String(error)}`,
  );
  process.exitCode = 1;
}

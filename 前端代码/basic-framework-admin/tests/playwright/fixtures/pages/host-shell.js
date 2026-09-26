/**
 * Q06 宿主夹具页（宿主侧）。
 *
 * 它是一个**真实的第三方宿主**：只加载版本化 SDK 产物（与 C10 的 `examples/ai-host-html/host.js`
 * 同一加载方式）、只向**宿主自己的后端**要票据、用**精确 targetOrigin** 发消息，
 * 并把 SDK 的生命周期/事件接到页面上。规格用例通过 `window.__q06Host` 驱动它并读取快照。
 *
 * 三条与产品代码同源的约束：
 *  1. 票据端点必须是宿主自己的后端（不是平台接口，浏览器里没有长期凭据）；
 *  2. 允许域（`allowedOrigins`）由用例显式给出，模拟"应用发布配置"；
 *  3. 宿主**不**信任任何来源：消息一律交给 SDK 的 `receive()` 判定，页面不做"先处理再判断"。
 *
 * 为了在真实浏览器里暴露 SDK 的判定结果，页面把 `onError/onRejected/onReady` 记进事件日志；
 * `openMount` 之外还提供 `openBridge`（直接用 `createHostBridge` + 真实 iframe/postMessage），
 * 因为 `createChatMount` 目前**没有**把收到的消息接进内部桥的入口（见 README 的已知缺陷）。
 */
import {
  createBusinessContextStore,
  createChatMount,
  createHostBridge,
  createHostEventHandlers,
} from '/sdk/ai-embed-sdk-5.6.0.js';

const TICKET_ENDPOINT = '/your-backend/ai-ticket';
const CONTEXT_UPDATE_KEYS = [
  'locale',
  'objectId',
  'objectType',
  'page',
  'timezone',
];

/** 宿主登记的路由（未登记的会被 SDK 的校验器拒绝）。 */
const ROUTES = {
  'order.detail': { params: { id: 'string' } },
  'report.list': { params: { page: 'number' } },
};

const containers = {
  mount: document.querySelector('#host-mount-point'),
  raw: document.querySelector('#host-frame-point'),
};

const state = {
  bridges: new Map(),
  contexts: new Map(),
  events: [],
  frames: new Map(),
  handlers: new Map(),
  /** 每个实例（桥或挂载）的配置：postToFrame / remount 都要用它。 */
  instanceConfigs: new Map(),
  mounts: new Map(),
  navigate: [],
  reportCreated: [],
  ticketCalls: [],
};
let seq = 0;

function record(kind, detail = {}) {
  state.events.push({ at: Date.now(), kind, seq: ++seq, ...detail });
}

/** 宿主侧换票：只走宿主后端；失败即抛错（不返回假票据，让 SDK 的失败路径生效）。 */
function fetchTicket({ appCode, instanceId, name }) {
  // 请求发出即记录：用例要靠它判断"换票在途"（响应可能被故意延迟）
  record('ticket-requested', { appCode, instanceId, name });
  return issueTicket({ appCode, instanceId, name });
}

async function issueTicket({ appCode, instanceId, name }) {
  const response = await fetch(TICKET_ENDPOINT, {
    body: JSON.stringify({ appCode, instanceId }),
    headers: { 'content-type': 'application/json' },
    method: 'POST',
  });
  state.ticketCalls.push({
    appCode,
    instanceId,
    status: response.status,
    at: Date.now(),
  });
  record('ticket-called', {
    appCode,
    instanceId,
    name,
    status: response.status,
  });
  if (!response.ok) {
    throw new Error(`ticket ${response.status}`);
  }
  const payload = await response.json();
  return { expiresAt: payload.expiresAt, token: payload.token };
}

function createFramePort(name, frameUrl, instanceId) {
  return {
    create() {
      const frame = document.createElement('iframe');
      frame.dataset.instance = instanceId;
      frame.name = name;
      frame.title = `AI 助手 ${name}`;
      frame.src = frameUrl;
      state.frames.set(name, frame);
      record('frame-created', { name, instanceId, src: frameUrl });
      return frame;
    },
    destroy() {
      const frame = state.frames.get(name);
      if (frame !== undefined) {
        frame.remove();
        state.frames.delete(name);
        record('frame-destroyed', { name, instanceId });
      }
    },
  };
}

function newHandlers(name) {
  const handlers = createHostEventHandlers({
    onNavigate: ({ params: routeParams, route }) => {
      state.navigate.push({ params: routeParams, route, target: name });
      record('navigate-accepted', { name, route });
    },
    onReportCreated: ({ reportId, version }) => {
      state.reportCreated.push({ reportId, target: name, version });
      record('report-created', { name, reportId });
    },
    routes: ROUTES,
  });
  state.handlers.set(name, handlers);
  return handlers;
}

/**
 * 低层宿主桥：真实 iframe + 真实跨源 postMessage + SDK 的状态机。
 *
 * 顺序很重要：先建 frame 并等 load（否则 HELLO 发进空气），再 `start()`。
 */
async function openBridge(config) {
  const { allowedOrigins, appCode, frameUrl, instanceId, name, targetOrigin } =
    config;
  const frame = document.createElement('iframe');
  frame.dataset.instance = instanceId;
  frame.dataset.testid = 'q06-bridge-frame';
  frame.name = name;
  frame.title = `桥夹具 ${name}`;
  frame.src = frameUrl;
  containers.raw.append(frame);
  state.frames.set(name, frame);
  await new Promise((done) => {
    frame.addEventListener('load', done, { once: true });
  });

  state.contexts.set(name, createBusinessContextStore());
  const bridge = createHostBridge({
    allowedOrigins,
    appCode,
    getAccessToken: () => fetchTicket({ appCode, instanceId, name }),
    instanceId,
    onError: (error) => record('bridge-error', { name, ...error }),
    onReady: () => record('bridge-ready', { name }),
    onRejected: (reason) => record('bridge-rejected', { name, reason }),
    transport: {
      destroy: () => {
        const current = state.frames.get(name);
        if (current !== undefined) {
          current.remove();
          state.frames.delete(name);
          record('bridge-frame-destroyed', { name });
        }
      },
      post: (message) => {
        frame.contentWindow?.postMessage(message, targetOrigin);
      },
      source: () => frame.contentWindow,
    },
  });
  state.bridges.set(name, bridge);
  state.instanceConfigs.set(name, config);
  newHandlers(name);
  bridge.start();
  record('bridge-started', { name, appCode, instanceId });
  return name;
}

/** Chat 挂载：`createChatMount`（C07）——外壳/生命周期/焦点/窄屏都是用它的真实实现。 */
function openMount(config) {
  const {
    allowedOrigins,
    appCode,
    frameUrl,
    instanceId,
    maxHeight,
    mode = 'inline',
    name,
    targetOrigin,
    theme,
  } = config;
  const container = containers.mount;
  const framePort = createFramePort(name, frameUrl, instanceId);
  const mount = createChatMount({
    allowedOrigins,
    appCode,
    container,
    frame: framePort,
    getAccessToken: () => fetchTicket({ appCode, instanceId, name }),
    instanceId,
    mode,
    onError: (error) => record('mount-error', { name, ...error }),
    ...(maxHeight === undefined ? {} : { maxHeight }),
    ...(theme === undefined ? {} : { theme }),
  });
  state.mounts.set(name, mount);
  state.instanceConfigs.set(name, config);
  state.contexts.set(name, createBusinessContextStore());
  newHandlers(name);
  record('mount-created', { appCode, instanceId, mode, name });
  if (config.open === true) mount.open();
  return name;
}

/** 未接桥的 frame：真实嵌入壳（会自己 handshake）或攻击页；宿主**不**为它建桥。 */
async function openExtraFrame({ appCode, frameUrl, instanceId, name }) {
  const frame = document.createElement('iframe');
  frame.dataset.instance = instanceId;
  frame.dataset.testid = 'q06-decoy-frame';
  frame.name = name;
  frame.title = `未注册 frame ${name}`;
  frame.src = frameUrl;
  containers.raw.append(frame);
  state.frames.set(name, frame);
  await new Promise((done) => {
    frame.addEventListener('load', done, { once: true });
  });
  return name;
}

/** 销毁一个实例：桥进入 DESTROYED、frame 移除、上下文清空。 */
function destroyInstance(name) {
  state.bridges.get(name)?.destroy();
  state.bridges.delete(name);
  const frame = state.frames.get(name);
  if (frame !== undefined) {
    frame.remove();
    state.frames.delete(name);
  }
  state.contexts.get(name)?.clear();
  state.handlers.delete(name);
  record('instance-destroyed', { name });
}

/**
 * 宿主切用户（协议层）：销毁旧实例 + 清空上下文 + 用新的实例标识重新登记。
 *
 * 与 C10 `switchUserAction` 同口径（销毁旧实例，重新挂载即新用户的新会话）；
 * 在途的换票由 SDK 的代次过滤丢弃（`HostBridge.resetSession` 的同一语义）。
 */
async function switchUser(name, overrides) {
  const previous = state.instanceConfigs.get(name);
  if (previous === undefined) return false;
  destroyInstance(name);
  const next = { ...previous, ...overrides };
  await openBridge(next);
  return true;
}

/** 全局消息路由：把每条消息送给**所有**桥实例（最坏情况），由 SDK 的来源/实例校验决定归属。 */
globalThis.addEventListener('message', (event) => {
  const data = event.data;
  const type = data !== null && typeof data === 'object' ? data.type : null;
  record('message-received', {
    origin: event.origin,
    type: typeof type === 'string' ? type : null,
  });
  for (const bridge of state.bridges.values()) {
    bridge.receive({
      data: event.data,
      origin: event.origin,
      source: event.source,
    });
  }
});

function snapshotBridge(name) {
  const bridge = state.bridges.get(name);
  const frame = state.frames.get(name);
  return {
    exists: bridge !== undefined,
    frameInstance: frame?.dataset.instance ?? null,
    generation: bridge?.currentGeneration() ?? null,
    state: bridge?.state() ?? null,
  };
}

function snapshotMount(name) {
  const mount = state.mounts.get(name);
  const frame = state.frames.get(name);
  const panel = document.querySelector(`[data-testid="ai-chat-panel"]`);
  return {
    frameInstance: frame?.dataset.instance ?? null,
    framePresent: frame !== undefined && frame.isConnected,
    isOpen: mount?.isOpen() ?? null,
    mode: mount?.mode() ?? null,
    overlayCount: document.querySelectorAll('[data-testid="ai-chat-overlay"]')
      .length,
    panelNarrow: panel?.dataset.narrow ?? null,
  };
}

globalThis.__q06Host = {
  /** 清空全部实例与日志（用例之间不共享状态）。 */
  reset() {
    for (const mount of state.mounts.values()) mount.destroy();
    for (const bridge of state.bridges.values()) bridge.destroy();
    for (const frame of state.frames.values()) frame.remove();
    state.bridges.clear();
    state.contexts.clear();
    state.events = [];
    state.frames.clear();
    state.handlers.clear();
    state.mounts.clear();
    state.instanceConfigs.clear();
    state.navigate = [];
    state.reportCreated = [];
    state.ticketCalls = [];
    seq = 0;
    return true;
  },
  destroyInstance,
  events: () => [...state.events],
  openBridge,
  openExtraFrame,
  openMount,
  openMountByName(name) {
    const mount = state.mounts.get(name);
    if (mount === undefined) return false;
    mount.open();
    return true;
  },
  mountAction(name, action, payload) {
    const mount = state.mounts.get(name);
    if (mount === undefined) return false;
    if (action === 'open') mount.open();
    if (action === 'close') mount.close();
    if (action === 'destroy') mount.destroy();
    if (action === 'setMode') mount.setMode(payload);
    if (action === 'updateTheme') mount.updateTheme(payload);
    return true;
  },
  /**
   * destroy 之后按新实例标识重新挂载（宿主切用户的动作，与 C10 的 switchUserAction 同口径）。
   *
   * 与 `switchUser` 一样：换实例标识时调用方必须同时给出新的 `frameUrl`
   * （实例标识在壳的 URL 上，URL 与 instanceId 不一致会让壳与宿主对不上号）。
   * 原来打开的面板保持打开（切用户后新会话应当可见）。
   */
  remount(name, overrides = {}) {
    const previous = state.instanceConfigs.get(name);
    if (previous === undefined) return false;
    const wasOpen = state.mounts.get(name)?.isOpen() === true;
    state.mounts.get(name)?.destroy();
    state.mounts.delete(name);
    const frame = state.frames.get(name);
    if (frame !== undefined) {
      frame.remove();
      state.frames.delete(name);
    }
    openMount({ ...previous, ...overrides, name });
    if (wasOpen) state.mounts.get(name)?.open();
    return true;
  },
  /** 把宿主自己（parent）的报文发给注册 frame：用于验证 iframe 侧对伪造 host 消息的拒绝。 */
  /** 触发 SDK 的代次切换（切用户时清掉在途换票、丢弃旧代次结果）。 */
  resetSession(name) {
    const bridge = state.bridges.get(name);
    if (bridge === undefined) return false;
    bridge.resetSession();
    record('session-reset', { generation: bridge.currentGeneration(), name });
    return true;
  },
  /** 触发 SDK 切用户动作（销毁 + 新实例）。 */
  switchUser(name, overrides = {}) {
    return switchUser(name, overrides);
  },
  postToFrame(name, payload) {
    const config = state.instanceConfigs.get(name);
    const frame = state.frames.get(name);
    if (frame === undefined) return false;
    const target = config?.targetOrigin;
    if (typeof target !== 'string') return false;
    frame.contentWindow?.postMessage(payload, target);
    return true;
  },
  setContext(name, input) {
    const store = state.contexts.get(name);
    if (store === undefined) return false;
    store.update(input);
    record('context-updated', { keys: Object.keys(input).sort(), name });
    return true;
  },
  clearContext(name) {
    state.contexts.get(name)?.clear();
    return true;
  },
  contextSnapshot: (name) => state.contexts.get(name)?.snapshot() ?? null,
  /** 宿主收到 iframe 上报后按登记路由校验（validateHostNavigation 的真实入口）。 */
  handleNavigate(name, request) {
    const handlers = state.handlers.get(name);
    if (handlers === undefined) return { ok: false, reason: 'NO_HANDLER' };
    const result = handlers.navigate(request);
    if (!result.ok)
      record('navigate-rejected', { name, reason: result.reason });
    return result;
  },
  handleReportCreated(name, event) {
    const handlers = state.handlers.get(name);
    if (handlers === undefined) return null;
    return handlers.reportCreated(event);
  },
  /** 断言用快照：只含可序列化字段。 */
  snapshot() {
    const bridges = {};
    for (const name of state.bridges.keys())
      bridges[name] = snapshotBridge(name);
    const mounts = {};
    for (const name of state.mounts.keys()) mounts[name] = snapshotMount(name);
    return {
      bridges,
      contextKeys: Object.fromEntries(
        [...state.contexts.keys()].map((name) => [
          name,
          Object.keys(state.contexts.get(name)?.current() ?? {}),
        ]),
      ),
      dom: {
        iframeCount: document.querySelectorAll('iframe').length,
        // 注意：mount.ts 在 ensureShell 里会把自己的 testid 写到同一个属性上（'ai-chat-frame'），
        // 所以挂载实例的 frame 只能按产品标记统计。
        mountedFrames: document.querySelectorAll(
          '[data-testid="ai-chat-frame"]',
        ).length,
        overlays: document.querySelectorAll('[data-testid="ai-chat-overlay"]')
          .length,
        panels: document.querySelectorAll('[data-testid="ai-chat-panel"]')
          .length,
        scrollHosts: document.querySelectorAll('[data-testid="ai-chat-scroll"]')
          .length,
      },
      mounts,
      navigate: [...state.navigate],
      registeredContextKeys: CONTEXT_UPDATE_KEYS,
      reportCreated: [...state.reportCreated],
      ticketCalls: [...state.ticketCalls],
    };
  },
};

for (const button of document.querySelectorAll('[data-mount]')) {
  button.addEventListener('click', () => {
    globalThis.__q06Host.openMountByName(button.dataset.mount);
  });
}

const status = document.querySelector('#host-status');
if (status !== null) status.textContent = 'host-fixture-ready';

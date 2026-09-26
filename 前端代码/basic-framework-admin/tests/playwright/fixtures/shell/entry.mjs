/**
 * Q06 嵌入壳夹具（iframe 侧），用 vite 打包成 `shell-bundle.js`。
 *
 * 这里**不重新实现协议**：桥本体是真实交付代码
 * `apps/ai-chat/src/bridge/iframe-bridge.ts`（C06/C08），契约复用 `@vben/ai-embed-sdk` 的再导出。
 * 夹具只补两件真实部署里由壳应用/宿主侧承担、而本环境没有后端可提供的东西：
 *
 *  1. 从壳自己的 `/embed-bootstrap.json` 取允许域与应用标识（真实实现由 C05 的壳页从
 *     平台 `/app-api/ai/v1/embed/<app>/bootstrap` 取；本环境无后端，改由夹具端点提供**同样形状**的配置）；
 *  2. 把若 `window` 的 message 事件接到 `bridge.receive`，并用**精确 targetOrigin** 回发
 *     （`IframeBridgeTransport` 是注入端口，真实部署里由壳应用实现）。
 *
 * 另外暴露 `window.__q06Shell` 供跨源断言读取内部状态（票据只在内存里，不写 URL/storage），
 * 其中 `raw()` 是**故意**保留的"伪造消息"出口：AT-051 需要"已注册的 frame 自己发出非法消息"
 * （错误 instanceId / 非法协议版本 / 未知类型），产品代码里不会存在这种出口。
 */
import { createIframeBridge } from '../../../../apps/ai-chat/src/bridge/iframe-bridge.ts';

const params = new URLSearchParams(globalThis.location.search);
const appCodeParam = params.get('app') ?? '';
const instanceParam = params.get('instance') ?? '';

const observations = {
  destroyed: false,
  errors: [],
  received: [],
};

/** AUTH 收到次数：AT-051/053 用它证明"没有第二条 AUTH 被接受"。 */
let authCount = 0;

async function main() {
  const response = await fetch(
    `/embed-bootstrap.json?app=${encodeURIComponent(appCodeParam)}&instance=${encodeURIComponent(instanceParam)}`,
  );
  if (!response.ok) {
    throw new Error(`bootstrap unavailable: ${response.status}`);
  }
  const bootstrap = await response.json();

  const bridge = createIframeBridge({
    allowedOrigins: bootstrap.allowedOrigins,
    appCode: bootstrap.appCode,
    instanceId: bootstrap.instanceId,
    onAuth: (auth) => {
      authCount += 1;
    },
    onContext: () => {},
    onDestroy: () => {
      observations.destroyed = true;
    },
    onError: (error) => {
      observations.errors.push(error);
    },
    onInit: () => {},
    onTheme: () => {},
    parentSource: globalThis.parent,
    transport: {
      destroy: () => {
        observations.destroyed = true;
      },
      post: (message) => {
        // 精确 targetOrigin：壳只回发到 bootstrap 里声明的宿主 Origin
        globalThis.parent.postMessage(message, bootstrap.hostOrigin);
      },
    },
  });

  globalThis.addEventListener('message', (event) => {
    const data = event.data;
    const type = data !== null && typeof data === 'object' ? data.type : null;
    observations.received.push({
      origin: event.origin,
      type: typeof type === 'string' ? type : null,
    });
    bridge.receive(event);
  });

  const status = document.querySelector('#shell-status');
  if (status !== null) status.textContent = 'shell-fixture-ready';

  // 运行时已就绪：通知宿主可以下发票据（真实壳应用在挂载完成后做同一件事）
  bridge.handshake();

  globalThis.__q06Shell = {
    /** 跨源可读的状态快照（票据只回前 40 位：足够分辨"哪张票"，又不是完整凭据）。 */
    snapshot: () => {
      const credential = bridge.credential();
      return {
        appCode: bootstrap.appCode,
        authCount,
        context: bridge.currentContext(),
        credential:
          credential === null
            ? null
            : {
                expiresAt: credential.expiresAt,
                prefix: String(credential.token).slice(0, 40),
              },
        destroyed: observations.destroyed,
        errors: observations.errors,
        hostOrigin: bootstrap.hostOrigin,
        instanceId: bootstrap.instanceId,
        received: observations.received,
        state: bridge.state(),
        theme: bridge.currentTheme(),
      };
    },
    notifyReportCreated: (event) => bridge.notifyReportCreated(event),
    /** 伪造出口：以**本 frame**（已注册来源）发出任意报文，用于 AT-051 的非法 instanceId/版本/类型。 */
    raw: (payload) =>
      globalThis.parent.postMessage(payload, bootstrap.hostOrigin),
    requestNavigate: (route, routeParams) =>
      bridge.requestNavigate(route, routeParams),
    requestToken: (reason) => bridge.requestToken(reason),
    snapshotContext: () => bridge.snapshotContext(),
  };
}

main().catch((error) => {
  const status = document.querySelector('#shell-status');
  if (status !== null)
    status.textContent = `shell-fixture-error: ${error.message}`;
});

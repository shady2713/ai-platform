# @vben/ai-embed-sdk

第三方宿主嵌入 AI Chat 用的轻量 SDK（C06/C07/C08）：桥协议客户端（握手/换票/实例隔离）、三种展示形态的挂载外壳（inline/drawer/dialog）、业务上下文仓库与宿主事件校验器。

SDK **不自占宿主页面的 `window` 监听器**，也**不把凭据写进 URL/存储**：宿主把收到的 message 事件交给 SDK，SDK 逐条判定 origin/source/instanceId/协议版本/schema 后再进入状态机。

## 用法

### 低层宿主桥（`createHostBridge`）

宿主自己建 iframe 与 `message` 监听器，把每条消息交给 `receive()`；`post()` 必须使用**精确 targetOrigin**（不使用 `'*'`）。

```ts
import { createHostBridge, createHostEventHandlers } from '@vben/ai-embed-sdk';

const handlers = createHostEventHandlers({
  onNavigate: ({ route, params }) => {
    /* 只执行登记过的路由 */
  },
  onReportCreated: ({ reportId }) => {},
  routes: { 'order.detail': { params: { id: 'string' } } },
});

const bridge = createHostBridge({
  allowedOrigins: ['https://ai.example.com'],
  appCode: 'crm-portal',
  getAccessToken: () => fetchTicketFromYourBackend(),
  instanceId: 'inst-1',
  onNavigate: handlers.navigate,
  onReportCreated: handlers.reportCreated,
  transport: {
    destroy: () => frame.remove(),
    post: (message) => frame.contentWindow?.postMessage(message, embedOrigin),
    source: () => frame.contentWindow,
  },
});

window.addEventListener('message', (event) => {
  bridge.receive({
    data: event.data,
    origin: event.origin,
    source: event.source,
  });
});
bridge.start();
```

`NAVIGATE_REQUEST`/`REPORT_CREATED` 只有在 INITIALIZED 之后、且 origin/source/instanceId/协议版本/schema 全部通过时才交给宿主回调；导航的路由注册与参数类型由 `createHostEventHandlers` 按登记表判定，未登记路由、任意 URL 与未知参数一律拒绝。宿主未登记回调时明确回 `MESSAGE_NOT_SUPPORTED`（不静默吞掉）。

### 挂载外壳（`createChatMount`）

`ChatMount.receive(event)` 是宿主事件入口（宿主自装 `message` 监听器）：

```ts
const mount = createChatMount({
  allowedOrigins: [embedOrigin],
  appCode: 'crm-portal',
  container: document.querySelector('#ai-chat-host'),
  frame: {
    create: () => {
      const frame = document.createElement('iframe');
      frame.src = embedUrl;
      return frame;
    },
    destroy: () => {},
  },
  getAccessToken: () => fetchTicketFromYourBackend(),
  instanceId: 'inst-1',
  mode: 'inline',
  onNavigate: handlers.navigate,
  onReportCreated: handlers.reportCreated,
});

mount.open();
window.addEventListener('message', (event) => {
  mount.receive(event);
});
```

## 生命周期与状态语义

| 动作 | 语义 |
| --- | --- |
| `open()` | 首次调用创建外壳与 iframe；**等 iframe `load` 之后**才发送 HELLO 开始握手 |
| `open()/close()` 重复调用 | 幂等；不重建 iframe（会话、滚动位置与图表实例保留） |
| `setMode()` / `updateTheme()` | 只重排外壳/更新外观令牌，不重建 iframe、不清会话 |
| `receive(event)` | 每条消息逐次判定；非本实例 iframe 来源（`event.source` 与 frame 的 `contentWindow` 不同）一律丢弃 |
| `destroy()` | 清理外壳、监听器、iframe 与桥实例；重复调用幂等 |
| **destroy 后复用** | **销毁是单向终态**：此后 `open()`/`setMode()`/`updateTheme()`/`receive()` 一律幂等拒绝——不创建 DOM、不改变状态、不复活外壳；要重新挂载必须新建实例 |

## 安全边界

- 桥协议只接受白名单消息类型与严格 schema；未知类型、多余字段一律拒绝。
- 凭据只通过 AUTH 消息下发，且只保存在内存；`post()` 只使用精确 targetOrigin。
- 导航只认宿主登记的路由名与声明过的标量参数；协议层不接 URL/脚本。
- 业务上下文只允许契约声明的键；身份/范围字段无法进入上下文。

## 验证

```bash
# 工作目录：前端代码/basic-framework-admin
pnpm exec vitest run --dom packages/ai-embed-sdk
pnpm exec eslint packages/ai-embed-sdk
pnpm exec tsc -p packages/ai-embed-sdk/tsconfig.json --noEmit
```

真实浏览器跨源验收（AT-051/052/053/055 等）见 `tests/playwright/README.md`。

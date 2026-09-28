# @vben/ai-web-component

组件级 Chat 集成包（任务卡 X09）：把现有 ChatUI 封装成 **Web Component**，宿主用一个标签 + 一个换票回调即可接入，**与 iframe 嵌入（`@vben/ai-embed-sdk`）共用同一套宿主契约**。

```html
<ai-chat-component
  app-code="crm-portal"
  api-base-url="/your-backend/app-api"
  service-id="svc_1"
  routes='{"order.detail":{"params":{"id":"string"}}}'
  theme='{"primaryColor":"#1677ff","radius":6,"fontFamily":"system-ui, -apple-system, \"PingFang SC\", \"Microsoft YaHei\", sans-serif"}'
></ai-chat-component>
```

```ts
import {
  defineAiChatElement,
  type AiChatElement,
} from '@vben/ai-web-component';

defineAiChatElement();
const chat = document.querySelector('ai-chat-component') as AiChatElement;
// 换票回调：与 iframe 路径同一签名，只向宿主自己的后端要票（浏览器不留长期凭据）
chat.getAccessToken = () =>
  fetch('/your-backend/ai-ticket', { method: 'POST' }).then((r) => r.json());
chat.open();
```

## 1. 宿主契约（与 iframe 路径对齐）

| 能力 | iframe 路径（`createChatMount`） | 组件路径（`<ai-chat-component>`） |
| --- | --- | --- |
| 身份 | `appCode` + `instanceId` | `app-code` + `instance-id` 属性 |
| 换票 | `getAccessToken()`（回调） | `getAccessToken` 属性（同一签名） |
| 主题 | `theme`（`Theme` tokens） | `theme` 属性（JSON，**经契约校验**） |
| 形态 | `open/close/setMode/updateTheme/destroy` | 同名方法 + `open` / `mode` 属性 |
| 导航 | `createHostEventHandlers` 登记表 | 同一张登记表（`routes` 属性/属性） |
| 事件 | 宿主回调 | 元素上的 DOM 事件：`ai-ready` / `ai-error` / `ai-navigate-request` / `ai-rejected` / `ai-report-created` / `ai-destroyed` |

- **桥不变**：宿主侧仍是 SDK 的 `HostBridge`（HELLO → READY → AUTH → INIT，换票 single-flight、代次过滤、稳定错误码都在那里），组件只提供**页内传输**的同一条协议（`src/protocol/local-port.ts`）。
- **界面不复制**：ChatUI 的会话面板与状态机（`ConversationPanel` + `useConversation`）原样复用；运行/会话请求走共享开放客户端（`createOpenApiClient`），票据只在内存、只在请求头。
- **上下文**：`updateContext()` 走 `createBusinessContextStore` 的"只作用下一次运行"语义，受理请求携带受理瞬间的快照（`businessContext`）。

## 2. 样式边界（Shadow DOM）

- 组件外壳与 ChatUI 的样式都装在 **Shadow DOM 内部**：宿主 CSS 选择器进不去，组件类名也不会污染宿主页面（宿主只能通过 `--ai-*` 自定义属性与 `part` 影响外观）。
- 沿用自平台主题令牌：`--ai-primary-color` / `--ai-radius` / `--ai-font-family` 等（值全部来自已校验 token）。
- 样式装载顺序：`surfaceStyles`（宿主给文本）→ `styles-url`（宿主给地址）→ 版本化产物同目录 `.css` （按 `import.meta.url` 推导）。产物路径下无需宿主做任何事；**源码导入**（被打进宿主自己的包）时样式不会自动进入 Shadow DOM，请用上面两种方式之一显式给出。

## 3. 产物（自托管、版本化、无公共 CDN）

```sh
pnpm -F @vben/ai-web-component run build           # 生成 dist/
pnpm -F @vben/ai-web-component run verify:artifact # 包体预算 + 命名 + CDN 扫描（实测数字）
```

| 产物 | 实测体积（2026-09-28） | 说明 |
| --- | --- | --- |
| `ai-web-component-5.6.0.js` | 212,309 B（207.3 KiB）/ gzip 68,933 B（67.3 KiB） | 入口，含 Vue 与 ChatUI（宿主首屏加载） |
| `ai-web-component-5.6.0.css` | 1,252 B（1.2 KiB）/ gzip 358 B | 与入口同目录，元素自动注入 Shadow DOM |
| `ai-web-component-5.6.0.<hash>.chunk.js` | 1,311,015 B（1280.3 KiB）/ gzip 388,045 B（379.0 KiB） | 图表厂商（G2）**懒加载**：只在渲染图表时请求 |

预算（脚本内声明，超限即失败）：入口 ≤ 260 KiB / gzip ≤ 85 KiB；样式 ≤ 8 KiB；懒加载分包 ≤ 1600 KiB / gzip ≤ 470 KiB。

产物在 `vite build` 之后由 `scripts/minify-artifact.mjs` 压缩（Vite 的 ES 库模式不压空白，见该脚本说明）。

## 4. 开发与测试

```sh
pnpm -F @vben/ai-web-component exec vue-tsc --noEmit --skipLibCheck
pnpm exec vitest run --dom packages/ai-web-component/src --coverage
pnpm -F @vben/ai-web-component run build && pnpm -F @vben/ai-web-component run verify:artifact
```

用例覆盖：参数解析、页内桥（握手顺序/来源/版本/续票/销毁）、应用端端口（信封错误、401 换票一次、上下文快照）、元素（打开/关闭/形态/焦点/Esc/主题/导航校验/销毁/重连）、 **同页两实例身份隔离**、**iframe 路径兼容**、**无公共 CDN 静态检查**。

## 5. 已知边界（如实记录）

1. 组件路径的请求由**宿主页面**发出：跨源基址必须在 `allow-origins` 登记，且平台需为 app-api 开放 CORS；推荐部署是宿主自己的**同源网关**转发 `/app-api`（示例见 `examples/ai-host-html/server.mjs`）。
2. 业务上下文只在组件路径生效（iframe 路径的 `CONTEXT_UPDATE` 发送入口仍缺失，属既有缺口）。
3. 真实浏览器验收（跨源、第三方 Cookie 禁用、窄屏、键盘走查）由 Q06/G5 承担；本包只给组件级（happy-dom）证据。
4. `apps/ai-chat` 的公共 CDN 扫描清单目前不包含本包，本包用自带脚本与用例自查（仅证明本包产物）。

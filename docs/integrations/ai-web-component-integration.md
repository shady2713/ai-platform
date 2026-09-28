# 组件级 Chat 集成与迁移接入（X09）

本文是 **组件级（Shadow DOM Web Component）集成**的接入与迁移说明，配套 C10 的
[iframe 宿主接入指南](ai-host-integration.md)。两条路径**共用同一份宿主契约**（身份/换票/主题/导航/事件桥），
因此"从 iframe 迁到组件"（或反向）只改承载方式，不改宿主侧的换票、路由登记与主题决策。

- 组件包：`packages/ai-web-component`（`@vben/ai-web-component`），README 见包内。
- 示例：`examples/ai-host-html`（`/component.html` 组件页 + `/` iframe 页）、
  `examples/ai-host-vue`（同一页演示两条路径，宿主逻辑只写一份）。
- 相关验收：AT-051（错误来源消息）、AT-052（同页两个 Chat）、AT-054（主题与窄屏）、
  AT-055（destroy 后重复挂载）、AT-057（N-1 兼容）、AT-067（禁公共 CDN）。

## 1. 兼容矩阵

| 维度           | iframe 路径（`createChatMount`）                                            | 组件路径（`<ai-chat-component>`）                                                                                  |
| -------------- | --------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------ |
| 承载           | 平台自托管嵌入页（跨源 iframe）                                             | ChatUI 直接挂进宿主页面的 Shadow DOM                                                                               |
| 握手协议       | HELLO → READY → AUTH → INIT（postMessage）                                  | 同一协议、同一状态机（`HostBridge`），页内直接传输                                                                 |
| 身份隔离       | `instanceId` + `event.source` + 允许域                                      | `instance-id` + 每实例身份令牌 + API 基址归属校验                                                                  |
| 换票           | `getAccessToken()`，票据只在 iframe 内存                                    | 同一回调签名，票据只在组件内存                                                                                     |
| 请求发出方     | **平台页面**（与平台同源，无需 CORS）                                       | **宿主页面**（跨源需平台开 CORS，推荐宿主网关同源转发）                                                            |
| 第三方 Cookie  | 不依赖（票据经 postMessage 传递）                                           | 不依赖（票据在宿主内存与请求头）                                                                                   |
| 主题           | `mount.updateTheme()`（只写外壳令牌，宽松）                                 | `theme` 属性 + `updateTheme()`（**经主题契约校验**：色值/半径/字体白名单）                                         |
| 业务上下文     | 宿主侧 `createBusinessContextStore`（**传给 iframe 的入口缺失**，既有缺口） | `updateContext()` 生效；受理请求带受理瞬间快照                                                                     |
| 导航与报表事件 | `onNavigate`/`onReportCreated` 回调                                         | 同一校验器；事件为元素上的 DOM 事件                                                                                |
| 形态与焦点     | inline/drawer/dialog，Esc/焦点归位                                          | 同名方法与属性；模态形态 Esc、Tab 循环、焦点归位                                                                   |
| destroy        | 幂等、单向终态，重挂载需新实例                                              | 同语义；DOM 断开只释放资源（重连可再初始化）                                                                       |
| 包体           | SDK 产物 100.84 kB / gzip 23.50 kB（C10 实测）                              | 入口 307,958 B / gzip 85,810 B + 样式 1,252 B（含 Vue 与 ChatUI；图表 G2 懒加载分包 1,842,557 B / gzip 456,539 B） |
| 无公共 CDN     | 自托管版本化产物                                                            | 自托管版本化产物（自带脚本 + 用例自查，见 §5）                                                                     |
| 浏览器验收     | Q06 套件（AT-051/052/053/055/056/067）                                      | **待补**：组件路径尚未接入浏览器套件（见 §6）                                                                      |

## 2. 迁移步骤（iframe → 组件）

1. **保留宿主的既有实现**：换票回调（`getAccessToken`）、路由登记表、主题 tokens 都不变。
2. **引入组件产物**：把 `dist/ai-web-component-<version>.js` 与同目录的 `.css`（以及
   `ai-web-component-<version>.<hash>.chunk.js`）托管在宿主的静态路径（固定命名、长缓存）。
   产物是 ES 模块，用 `<script type="module">` 或宿主打包器加载：
   ```html
   <script type="module">
     import { defineAiChatElement } from "/component/ai-web-component-5.6.0.js";
     defineAiChatElement();
   </script>
   ```
3. **创建元素并给参数**（`app-code` / `api-base-url` / `instance-id` / `service-id` / `routes` / `theme`），
   再把 `getAccessToken` 指到宿主后端，最后 `open()`。
4. **接管事件**：把 `ai-navigate-request` / `ai-report-created` 接到宿主既有的处理函数；
   被拒绝的请求会收到 `ai-rejected`（稳定原因码），不要静默忽略。
5. **应用端连通**：`api-base-url` 指向宿主自己的网关（同源转发到平台 `/app-api`），
   或让平台为宿主 Origin 开放 CORS（当前推荐前者；示例见 `examples/ai-host-html/server.mjs` 的
   `/your-backend/app-api/*`）。
6. **样式**：产物路径无需配置；源码导入时用 `styles-url` 或 `surfaceStyles` 把 ChatUI 的 CSS
   交给元素（否则面板可交互但无排版样式）。
7. **销毁与切用户**：页面卸载调用 `destroy()`；切用户调用 `resetSession()`（换代 + 重建界面，
   旧响应丢弃），不要复用已经 `destroy()` 的元素。

## 3. 宿主样式与 Shadow DOM 边界

- 组件内的节点只能被组件自己的样式命中：宿主 CSS 选择器（包括 `*`）不会进入 Shadow DOM。
- 会被继承的属性（`font-*`、`color`、`line-height`、`visibility`、`pointer-events`、`opacity` 等）
  在组件内已显式重置，宿主"通配放大/隐藏"只会影响宿主自己的节点（示例页 `component.html` 故意保留
  这类敌意样式用于走查）。
- 主题只通过 `--ai-*` 自定义属性注入；不接受任意 CSS 文本、远程字体或 `url()` 资源。

## 4. 同页多实例（身份隔离）

- 每个元素持有一个身份令牌、一份票据、一份上下文与一张路由登记表；事件只在自身元素上派发。
- 同一页面挂两个不同应用时：分别设置 `app-code` / `instance-id` / `service-id` 与各自的换票回调。
- 组件用例 `src/__tests__/instance-isolation.test.ts` 覆盖：票据不串线、事件归属、上下文/主题互不影响、
  销毁 A 不影响 B、切用户只换代 A。

## 5. 包体预算与"无公共 CDN"（本包口径）

| 检查               | 命令                                                                                       | 实测（2026-09-28）                                                                                               |
| ------------------ | ------------------------------------------------------------------------------------------ | ---------------------------------------------------------------------------------------------------------------- |
| 包体预算           | `pnpm -F @vben/ai-web-component run verify:artifact`                                       | 入口 300.7 KiB（307,958 B）/ gzip 83.8 KiB；样式 1.2 KiB；懒加载分包 1799.4 KiB / gzip 445.8 KiB —— 全部在预算内 |
| 无公共 CDN（产物） | 同上（CDN 清单读 F04 的 `public-cdn-registry.json`）                                       | 0 命中；外部资源引用 0 命中                                                                                      |
| 无公共 CDN（源码） | `pnpm exec vitest run --dom packages/ai-web-component/src/__tests__/no-public-cdn.test.ts` | 0 命中（含 `@import`/`url()`/绝对地址）                                                                          |

**口径声明**：上述检查只证明**本包自身**的源码与产物不含公共 CDN 引用与外部资源引用，
不等于整个站点零外部请求（宿主页面、其它包、运行期第三方库不在范围内）。
站点级断言由 Q06 的浏览器网络用例（AT-067）承担；本包的检查是它之前的静态闸门。

## 6. 未验证项（必须在后续关口补齐）

1. **组件路径的真实浏览器验收**：跨源（或宿主网关）、第三方 Cookie 禁用、窄屏、键盘走查、
   `destroy` 后内存观察与 CDN 网络断言目前只在组件级（happy-dom）与静态检查层验证；
   Q06 套件当前只覆盖 iframe 路径，建议在 G5 前把组件路径加入同一套件。
2. **真实平台联调**：示例需要本地平台与真实应用凭据才能跑通换票与对话；本包未在 CI 启动平台。
3. **平台侧 CORS**：组件路径跨源直连平台时需要在平台开放 app-api CORS——这属于平台配置变更
   （后端不在本卡允许范围），因此本卡只提供"宿主网关"这一推荐形态与示例实现。
4. **业务上下文对 iframe 路径的补齐**：iframe 路径仍缺 `CONTEXT_UPDATE` 的发送入口（既有缺口，
   属 SDK/挂载层后续改动）。

# Q06 浏览器夹具与跨源用例（Playwright + Chromium）

本目录落实 ADR 0046 的"真实浏览器门禁"中**跨源/渲染/权限**这一段：用真实 Chromium、真实生产产物、真实跨源 `postMessage` 覆盖 AT-051/052/053/054/055/056/067。只做**结构断言**，不做视觉回归（ADR 0046 决策 2）。

## 运行

```bash
# 工作目录：前端代码/basic-framework-admin
pnpm install                 # 首次；锁文件已含 @playwright/test 1.63.0
pnpm exec playwright install chromium   # 首次；本机无 Chromium 时需要（不加 --with-deps 也不需要 sudo）

# 全量（globalSetup 会自动补齐产物：SDK 版本化产物、嵌入壳 bundle、Chat/管理端生产构建）
pnpm exec playwright test --config tests/playwright/playwright.config.ts

# 单卡
pnpm exec playwright test --config tests/playwright/playwright.config.ts at-051

# 强制重建两个应用产物（证据跑用）
Q06_FORCE_REBUILD=1 pnpm exec playwright test --config tests/playwright/playwright.config.ts
```

产物（截图/录像/trace/报告）写在仓库根 `.local-state/q06-browser/`（已 gitignore，不提交二进制）。

## 夹具拓扑（每个都是真实 Origin）

| Origin | 角色 | 提供 |
| --- | --- | --- |
| `http://127.0.0.1:5290` | 第三方宿主页 | 宿主页（`pages/host-shell.html` + `.js`）、版本化 SDK 产物 `/sdk/…`、**宿主自己的换票端点**、控制端点 |
| `http://127.0.0.1:5291` | 允许域内的嵌入壳 A | 壳页（真实 `IframeBridge`）+ `/embed-bootstrap.json` |
| `http://127.0.0.1:5292` | 未授权第三方页 | 攻击页（伪造 AUTH/context/navigation 消息） |
| `http://127.0.0.1:5293` | 允许域外的嵌入壳 B | 同一个壳页（"错误 origin"对照物） |
| `http://127.0.0.1:5294` | 管理端产物 | `apps/web-ele/dist`（生产构建） |
| `http://127.0.0.1:5295` | Chat 产物 | `apps/ai-chat/dist`（生产构建） |

真实的与夹具自己写的（避免把夹具当产品验收）：

- **真实代码**：`apps/ai-chat/src/bridge/iframe-bridge.ts`（iframe 侧桥，vite 打成 `shell-bundle.js`）、 `@vben/ai-embed-sdk` 的 `createHostBridge` / `createChatMount` / `createHostEventHandlers` / `createBusinessContextStore`（宿主页直接加载**版本化产物**，与 C10 的 `host.js` 同方式）、 `apps/ai-chat` 与 `apps/web-ele` 的生产构建产物。
- **夹具自写**：壳页的 bootstrap 端点（真实实现由 C05 的后端 `/app-api/ai/v1/embed/<app>/bootstrap` 提供）、宿主自己的换票端点（与 C10 `examples/ai-host-html/server.mjs` 同角色，发的是**夹具票据**）、宿主页的 UI/路由胶水、攻击页。
- **不做的事**：不 stub 模型效果、不伪造票据（夹具票据只用于断言"票据去向/隔离"）、不在 Node 侧模拟协议（所有协议行为都发生在页面上下文里）。

## 用例与结论对照

| 用例 | 结论 | 备注 |
| --- | --- | --- |
| AT-051 错误 origin/source/frame 消息（6 例） | pass | 含伪造 READY/AUTH、诱饵 frame（伪 source）、错误 origin 的真壳、非法 instanceId/版本/类型、伪造上下文与导航、攻击页跨源换票被拒 |
| AT-052 一页两 Chat（4 例） | pass | 同 Origin 两个实例，宿主路由故意"最坏写法"（每条消息送所有桥），隔离由 SDK 的 source/instance 判定承担 |
| AT-053 宿主切用户（3 例） | pass | 慢换票在途时切用户 ⇒ 旧代次票据被丢弃、新代次重新换票；销毁旧实例；挂载层 destroy + remount |
| AT-054 主题/窄屏/键盘（5 例） | pass | 375x812 模态铺满、无横向滚动、Tab 不逃逸、Esc 归还焦点；主题令牌生效且不重建 iframe；管理端深浅色令牌切换 |
| AT-055 destroy 后重复 mount（3 例） | 2 pass + 1 **预期失败**（已确认缺陷） | DOM/监听器零残留、10 轮堆增长 96 KB（阈值 6 MB）、幂等销毁 |
| AT-056 embed/admin 安全头（2 例） | **skipped** | 本机 48080 无后端（`docker ps` 只有 redis）⇒ 需要真实 Spring 过滤链，属未验证项 |
| AT-067 禁公共 CDN（4 例） | 2 pass + 1 **fail（真实缺陷）** + 1 skipped | 管理端登录页会请求 `api.iconify.design`（登记表声明"不应请求"）；报表页无登录态 ⇒ skipped |

## 已确认缺陷（浏览器夹具发现，均不在本切片允许路径内）

1. **`createChatMount` 的内部桥没有任何接收入口**：`packages/ai-embed-sdk/src/display/mount.ts` 里 `createHostBridge(...)` 是内部变量，返回的 `ChatMount` 只有 `open/close/setMode/updateTheme/destroy/isOpen/mode`——宿主**无法把 `window` 的 message 事件喂进去**，真实浏览器下握手永远停在 HELLO（iframe 的 READY 无人接收），票据永远不会下发。此外 `open()` 立即 `bridge.start()`（HELLO 可能早于 iframe 文档加载而丢失），也没有等 `load` 的时序。证据：`tests/playwright/specs/at-052-two-instances.pw.ts` 的挂载用例断言"挂载层没有任何换票"。建议：给 `ChatMount` 暴露 `receive(event)`（或由 SDK 自装 listener），并等 iframe `load` 后再 `start()`。
2. **`destroy()` 后可复用同一实例 `open()` 会重建外壳**：`open()` 不检查 `destroyed`， `ensureShell()` 重新 append overlay/panel/iframe，而内部桥已是 DESTROYED，留下"僵尸面板 + 死桥"，每次 open/close 都累积 DOM。实测（`mount.ts` 现状）：destroy 后 `iframes=0, overlays=0`；再 `open()` 后 `iframes=1, overlays=1`（桥仍为 DESTROYED，不可能再握手）。用例：`at-055-destroy-remount.pw.ts` 的 `test.fail(...)`（修好后会变成"意外通过"，即 tripwire）。
3. **宿主侧从不接收 iframe 的业务事件**：`HostBridge.dispatch()` 对 `NAVIGATE_REQUEST`/`REPORT_CREATED` 走 `default` → `MESSAGE_NOT_SUPPORTED`，于是 C08 的宿主校验器（`validateHostNavigation`）在真实接线里拿不到输入。用例：`at-052-two-instances.pw.ts` 的"事件按实例归属"用例（记录现状 + 直接验证校验器）。
4. **`CONTEXT_UPDATE`/`THEME_UPDATE` 没有宿主侧发送方**：`HostBridge` 只发 HELLO/AUTH/INIT/ERROR/DESTROY，C08 的 `THEME_UPDATE` 处理在真实接线里无入口（主题更新只到外壳）。
5. **SDK 不提供外壳样式**：`createChatMount` 只写内联 width/height 到面板，iframe 的 box 由宿主 CSS 决定（裸挂载是浏览器默认 300x150）；窄屏"铺满视口"依赖宿主给 overlay 定位（夹具补了最小 CSS 才成立，见 `fixtures/pages/host-shell.html` 注释）。
6. **禁公网环境下管理端登录页会外发图标请求**（AT-067 fail）： `packages/effects/common-ui/src/components/captcha/verification/verify-slide.vue:352` 用字符串图标 `lucide:refresh-ccw`（另有 `lucide:x`），`@iconify/vue` 回退到 `https://api.iconify.design/lucide.json` ——与 `apps/ai-chat/scripts/public-cdn-registry.json` 里"图标集已随包构建，Q06 网络断言确认无请求"矛盾。建议：改用 `@vben/icons` 本地注册的图标（`createIconifyIcon`）或把图标加进离线集合。
7. **Chat 生产页在无后端时产生未处理的页面错误**：`apps/ai-chat` 会话列表加载失败只有 console 错误，界面无可见提示（用例以 annotation 记录，不改变 AT-054 的结构结论）。

## 本切片的未验证项

1. **AT-056（embed/admin 安全头）**：需要真实后端 + 应用允许域配置；本机 48080 不可达。用例已写好断言（401 + SAMEORIGIN、CSP frame-ancestors、浏览器拒绝非允许域嵌套），提供 `Q06_EMBED_APP_CODE`（和正向验证用的 `Q06_EMBED_ALLOWED_ORIGIN`）即可在有后端的环境跑。
2. **报表页（R07）的窄屏/主题/禁公网断言**：需要登录态与后端数据。
3. **平台侧换票（A04）与模型相关步骤**：对话内容、报表修改等需要真实模型（model-dependent: not configured in this environment）；本切片断言的是票据去向/隔离，不是平台签发票据本身。
4. **Windows / CI**：只在 Linux + Chromium 153.0.8010.12（playwright chromium v1243）验证过。
5. **真实 Nginx/网关的头部透传**：属部署验证（C05 的未验证项）。

## 门禁接线（建议，需主管在允许路径外落地）

ADR 0046 要求的 `scripts/check-e2e-smoke.mjs` + `.harness/verify.{sh,ps1}` + nightly workflow 不在本切片允许路径内。建议：

```bash
# scripts/check-e2e-smoke.mjs 的核心命令
cd 前端代码/basic-framework-admin
pnpm exec playwright install chromium
pnpm exec playwright test --config tests/playwright/playwright.config.ts
```

- PR 层不跑（ADR 0046 决策 3：PR 层预算 ≤10 分钟）；nightly/release 层跑，`aggregate` 保持唯一必需检查。
- 首次运行需要下载 Chromium（约 115 MB）与构建 web-ele（约 1–2 分钟）；可用 `PLAYWRIGHT_BROWSERS_PATH` 复用缓存、用 `Q06_FORCE_REBUILD` 控制产物重建。
- AT-067 的管理端用例当前**预期为红**（缺陷 6）；接线时不要用 skip/白名单掩盖，应先在允许路径内修掉图标外发或经评审后调整登记表口径。

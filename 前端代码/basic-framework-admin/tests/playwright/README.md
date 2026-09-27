# Q06/Q07 浏览器夹具与跨源/韧性用例（Playwright + Chromium）

本目录落实 ADR 0046 的"真实浏览器门禁"：用真实 Chromium、真实生产产物、真实跨源 `postMessage` 覆盖 AT-051/052/053/054/055/056/067（Q06），以及 AT-014/017/039/059 的**前端韧性**一段（Q07：慢消费者/断流、配额拒绝、部分结果与未知标注、断线重连按 seq 去重）。只做**结构断言**，不做视觉回归（ADR 0046 决策 2）。

## 运行

```bash
# 工作目录：前端代码/basic-framework-admin
pnpm install                 # 首次；锁文件已含 @playwright/test 1.63.0
pnpm exec playwright install chromium   # 首次；本机无 Chromium 时需要（不加 --with-deps 也不需要 sudo）

# 全量（globalSetup 会自动补齐产物：SDK 版本化产物、嵌入壳 bundle、Q07 韧性探针 bundle、Chat/管理端生产构建）
pnpm exec playwright test --config tests/playwright/playwright.config.ts

# 单卡
pnpm exec playwright test --config tests/playwright/playwright.config.ts at-051
pnpm exec playwright test --config tests/playwright/playwright.config.ts at-017-slow-consumer at-059-quota-rejection

# 强制重建全部产物（证据跑用；改了 packages/** 源码后必须重建，否则浏览器里跑的是旧包）
Q06_FORCE_REBUILD=1 pnpm exec playwright test --config tests/playwright/playwright.config.ts
```

产物（截图/录像/trace/报告）写在仓库根 `.local-state/q06-browser/`（已 gitignore，不提交二进制）；Q07 的探针 bundle 写在 `.local-state/q07-browser/probe/`。

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

### Q07 韧性夹具（同进程、随机端口，不进 `webServer`）

Q07 的用例需要"服务端按剧本回应"（慢速重复下发、非终态断流、连接重置、429、重放窗口过期），所以 `support/q07-resilience-api.mjs` 在**测试进程内**起一个只监听 `127.0.0.1` 随机端口的桩：

| 角色 | 提供 | 说明 |
| --- | --- | --- |
| 应用端 API 桩 | `/app-api/ai/v1/ai/{conversation/page,run/accept,run/events,run/get,run/cancel}` | 字段形状取自 `docs/contracts/ai/*`（RunEvent v1、错误码映射、CommonResult 信封）；**不是平台实现**，只用于构造"服务端剧本" |
| 探针页 | `/q07-probe.html` + `/q07-probe/probe-entry.js` | 页面挂载**真实组件**：`blocks.ts` 的 `parseMessageBlock` + `ResultTable.vue`（AT-039/060 的完整性标注）；并直接驱动**真实 SDK 客户端**做断线重连（AT-014） |
| 请求账本 | 进程内 `state.requests` | 断言"是否重复受理/是否重执行/重连是否带 afterSeq" |

Chat 产物的用例用 `page.route` 把编译期基址（`http://127.0.0.1:48080/app-api/ai/v1`）**只改 Origin** 重定向到这个桩（路径与查询串原样保留），因此客户端行为与真实部署一致（含 CORS 预检）。

## 用例与结论对照

| 用例 | 结论 | 备注 |
| --- | --- | --- |
| AT-014 断线重连按 seq 去重 + 窗口过期转快照（2 例） | pass | 重连带 `afterSeq`、服务端重放的事件被丢弃、终态即停、不重执行；窗口过期契约码 `1_003_004_006` 下客户端转读运行快照、不重执行（缺陷 8 已修，原 tripwire 转正） |
| AT-017 慢消费者与断流（3 例） | pass | 慢速重复/乱序回放按 seq 去重（每个事件只渲染一次、只追加同一条消息）；流中途不假完成；非终态断流停在"执行中"；连接重置给可见失败与重试入口 |
| AT-039/AT-060 部分结果与未知标注（1 例） | pass | `PARTIAL`/`UNKNOWN` 有"结果可能不完整"标注、`COMPLETE` 不误报、空值显示 `—` 不补 0、分页信息如实展示 |
| AT-059 配额拒绝可解释（2 例） | pass | 受理 429 与订阅 429 都显示服务端原因、不假成功、可重试恢复；重试幂等键每次新生成（见 Q07 报告"观察项"） |
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
8. **重放窗口过期错误码与契约不一致（Q07 发现，已修）**：前端常量原写成 `1003004009`（`packages/ai-embed-sdk/src/client.ts:68`、 `packages/ai-chat-ui/src/client/index.ts:39`），与冻结契约 `docs/contracts/ai/error-code-map.md`（`1_003_004_006`）及后端 `AiErrorCodeConstants.AI_RUN_EVENT_WINDOW_EXPIRED`（`1003004006`）不一致；真实错误码下"改读运行快照"分支不触发，客户端直接抛 409。**跨卡修复已应用**：两处常量与包内测试/README 均改为 `1003004006`， `at-014-sse-reconnect.pw.ts` 的原 tripwire（`test.fail`）已转为正常断言并通过（不再有预期失败）。
9. **传输层失败在运行链路上仍显示浏览器原始报文**（Q07 记录，未修）：事件流连接被重置时界面显示 `network error`（annotation 实证）；会话列表路径已归一为 `NETWORK_UNREACHABLE`，运行路径（`@vben/ai-embed-sdk` 的 `streamRunEvents`）没有同样的归一化。
10. **应用层没有自动重连循环**（Q07 记录，未修）：非终态断流后界面停在"执行中"（可重连、不假成功），但重连需要宿主/页面再触发；客户端 `afterSeq` 语义本身可用（AT-014 用例证明）。

## 本切片的未验证项

1. **AT-056（embed/admin 安全头）**：需要真实后端 + 应用允许域配置；本机 48080 不可达。用例已写好断言（401 + SAMEORIGIN、CSP frame-ancestors、浏览器拒绝非允许域嵌套），提供 `Q06_EMBED_APP_CODE`（和正向验证用的 `Q06_EMBED_ALLOWED_ORIGIN`）即可在有后端的环境跑。
2. **报表页（R07）的窄屏/主题/禁公网断言**：需要登录态与后端数据。
3. **平台侧换票（A04）与模型相关步骤**：对话内容、报表修改等需要真实模型（model-dependent: not configured in this environment）；本切片断言的是票据去向/隔离，不是平台签发票据本身。
4. **Windows / CI**：只在 Linux + Chromium 153.0.8010.12（playwright chromium v1243）验证过。
5. **真实 Nginx/网关的头部透传**：属部署验证（C05 的未验证项）。
6. **Q07 服务端容量/内存类结论**（有界队列、20 并发 P95、进程重启恢复）：属后端切片与压测，浏览器用例只判"界面在慢/断/被拒时的行为"；管理端用量页的 `UNKNOWN/ESTIMATED` 来源标签需要登录态与后端，浏览器级仍未验证（组件级见 Q03 证据）。

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

# Q06 浏览器验收与前端门禁 — 完成证据（**部分交付，含 2 个阻断项**）

| 项目 | 内容 |
|---|---|
| 任务卡 | [Q06](../tasks/Q06.md) |
| 状态 | **部分交付（BLOCKED）**：真实浏览器套件与用例已交付并实跑；3 条验收项因**已交付卡的真实缺陷**或**本机无后端**未通过/未验证（见 §4、§5），按要求不标 DONE |
| 需求 | FR-14、FR-15、FR-34、FR-40 |
| 依赖 | C10（已交付）、Q03（已交付）、Q05（本批交付）、F04（本批交付） |
| 工作副本 | `/home/ctyun/桌面/zhongtai/ai-platform` |
| 变更范围 | `前端代码/basic-framework-admin/tests/playwright/**`（新增）、根 `package.json`/`pnpm-lock.yaml`（`@playwright/test` 1.63.0） |

## 1. 交付内容

| 交付物 | 位置 | 说明 |
|---|---|---|
| Playwright 配置与夹具 | `前端代码/.../tests/playwright/{playwright.config.ts,tsconfig.json,README.md,fixtures/**,support/**}` | 6 个真实 Origin（端口 5290–5295：宿主页/允许域壳/攻击页/域外壳/管理端产物/Chat 产物）；桥本体用**真实** `apps/ai-chat/src/bridge/iframe-bridge.ts`（vite 打包），宿主侧用**版本化 SDK 产物**（与 C10 `host.js` 同法）；换票/票据由夹具提供（与 C10 `server.mjs` 同角色），断言对象是**票据去向与隔离**，不是平台签发 |
| 真实浏览器用例 | `tests/playwright/specs/at-051..at-067-*.pw.ts`（7 个文件 22 例） | AT-051/052/053/054/055/056/067 |
| 依赖 | `package.json` 增 `@playwright/test`（**workspace catalog 引用 `catalog:`**，目录里锁 1.63.0）；锁文件 +29 行 | `pnpm install --frozen-lockfile --ignore-scripts` exit 0 |
| 浏览器 | `pnpm exec playwright install chromium` | Chrome for Testing **153.0.8010.12**（chromium-1243）+ headless shell；**未用 `--with-deps`、无缺库报错** |

## 1.b 主管复核记录（2026-09-27）

- 本卡在独立 worktree（`分支 agent/q06`，基线 `agent/f04`）完成，主管用 `git cherry-pick -n 7a50529 6014c5c`
  合入主仓；本证据文件本身因批量暂存被并入了 F04 的提交 `88173e9`（bookkeeping 瑕疵，内容属本卡，随本卡生效）。
- **主管独立复跑**（主树，`pnpm install --frozen-lockfile --ignore-scripts` exit 0）：
  - 不带重建：22 passed / **2 failed** / 3 skipped —— 独立 Chat 产物页因**陈旧 dist** 出现假失败；
  - 带 `Q06_FORCE_REBUILD=1`：**23 passed / 1 failed（AT-067 管理端图标外发，真实缺陷）/ 3 skipped**，
    与执行代理报告一致。**结论：本套件必须在构建产物之后运行**（配置里已提供 `Q06_FORCE_REBUILD`），
    否则陈旧产物会造成假失败；这一点写入 `tests/playwright/README.md` 的运行前置。
- 主管另核：改动文件最大 500 行（未触 800 行上限）；`check-explicit-any` 0；`check-typecheck-contract` 0（38 包）。
- **第二轮（同一执行代理）**：`apps/ai-chat` 的失败态已在**卡内允许路径**修复（commit `33d7311`，
  `conversation-api.ts` 把传输层失败归一为 `AiChatApiError(status=0, code=NETWORK_UNREACHABLE)`、
  非法 JSON 归一为 `MALFORMED_RESPONSE` 且**不回显正文**，`HTTP_<status>` 只暴露状态码），并补 4 条失败路径用例；
  实测 `pnpm exec vitest run --dom apps/ai-chat` 27 passed / 5 files、`apps/ai-chat` typecheck 0、eslint 0，
  全量 `pnpm test:coverage` 368 files/1990 tests 通过且 `conversation-api.ts` 行覆盖 100%（基线 100）。
- **两个"待授权"补丁已就绪并验证**（存放在 gitignore 的 `.local-state/`，含 sha256）：
  - `packages/ai-chat-ui` 可见失败提示补丁（`.agent-worktrees/q06/.local-state/q06-task1-package-notice.patch`，
    sha256 `87e4130…19fc8`，427 行）：加 `ConversationLoadFailure` 归一化、列表与 CRUD 失败不再抛未处理 rejection、
    面板显示 `会话列表加载失败：<稳定码>` + 重试；临时应用后包测试 29 passed、Chromium 探针 `AGE ERRORS: []`
    （修复前 `["Failed to fetch"]`）、全量浏览器套件无新增回归（23/1/3），随后已回退。
  - 门禁接线补丁（`.agent-worktrees/q06/.local-state/q06-gate-wiring.patch`，sha256 `5e5b721…25a70`，434 行）：
    `scripts/check-e2e-smoke.mjs` + 同名拒绝测试（**空套件/空运行/覆盖不足/unexpected 全部拒绝绿灯**）+
    `.harness` 双 provider `smoke` 门禁 + `nightly-browser-smoke.yml` + 拓扑断言；探针实测：
    空套件 exit 1、去掉唯一红灯后 exit 0（21 passed/2 skipped）、接线态 `check-gate-wiring` 0、
    `check-harness-topology` 0；应用后 `sh .harness/verify.sh smoke` 的**唯一失败就是 AT-067**。

## 2. 实跑结果（`pnpm exec playwright test --config tests/playwright/playwright.config.ts`，强制重建产物）

**27 例：23 passed / 1 failed（真实缺陷）/ 3 skipped（环境）**，用时 1.8 分钟（含 web-ele 全量重建；无重建约 35s）。

| AT | 结论 | 关键断言（证据文件在 `.local-state/q06-browser/`，已 gitignore） |
|---|---|---|
| AT-051 错误 origin/source/frame | **pass 6/6** | 正向握手先跑通（AUTH/INIT + 票据）；伪造 READY 不触发换票（`ORIGIN_NOT_ALLOWED`）；域外真壳停在 `WAITING_READY`、`credential=null`；同域诱饵 frame → `SOURCE_MISMATCH`；非法 instanceId/协议版本/未知类型/伪造 AUTH 状态与票据不变；伪造 CONTEXT_UPDATE（越权字段）被拒且上下文保持；伪造 NAVIGATE_REQUEST → `MESSAGE_OUT_OF_ORDER`；攻击页跨源换票 403 |
| AT-052 一页两 Chat | **pass 4/4** | 双实例各自换票（token 含 appCode+序号）、各收 1 条 AUTH/INIT；"每条消息送所有桥"被 `SOURCE_MISMATCH` 拦；B 冒充 A 的 instanceId → A `SOURCE_MISMATCH` + B `INSTANCE_MISMATCH` 且无新增换票；上下文只落 A |
| AT-053 宿主切用户 | **pass 3/3** | 慢换票在途时 `resetSession`：旧代次票据被丢弃（新会话拿到第二次的票，旧票从未落地）；销毁旧实例后旧 frame 离开 frame 树、上下文清空 |
| AT-054 主题/窄屏/键盘 | **pass 5/5** | 375×812：面板 375×812、`data-narrow=true`、无横向滚动；Tab/Shift+Tab 不逃逸、Esc 关闭且焦点归还触发元素；宽屏高度=min(600,768)；`updateTheme` 令牌生效且 iframe **同一元素**（未重建）；管理端深浅色令牌值改变、Tab 顺序 username→password |
| AT-055 destroy/重复 mount | **pass 2 + 1 例 `test.fail`（已确认缺陷）** | 10 轮 mount/open/destroy：DOM 残留全 0、监听器净计数回基线；CDP 堆对比 2,242,756 B → 2,338,648 B（净增 ≈94 KB，阈值 6 MB 留 65 倍余量，方法写在用例注释）；**预期失败**：`destroy()` 后再 `open()` 会复活外壳（overlay/iframe 0→1） |
| AT-056 embed/admin 安全头 | **skipped 2/2（未验证）** | 本机 48080 无后端（`docker ps` 仅 `bf-redis`；`curl` exit 7）。断言已写全（admin 401 + XFO SAMEORIGIN；embed 无 XFO、CSP `frame-ancestors` 精确、非允许域嵌套不产生子 frame），提供 `Q06_EMBED_APP_CODE`/`Q06_EMBED_ALLOWED_ORIGIN` 即可在有后端环境跑 |
| AT-067 禁公共 CDN | **2 pass + 1 fail（真实缺陷）+ 1 skipped** | 部署期替换 `_app.config.js` 后管理端/Chat 页请求全在本地；探针拒绝测试 pass（注入 `cdn.jsdelivr.net` 能被检出，证明断言非空绿）；**fail**：管理端登录页真实请求 `https://api.iconify.design/lucide.json`（captcha 组件字符串图标回退）；报表页 skipped（无登录态/后端） |

## 3. 与卡片的对应（AT-061/062/064）

- **AT-061/062**（新模块/新表/权限未登记、新 workspace 漏 typecheck → 门禁变红）：由既有拒绝测试承担
  （`scripts/check-permission-catalog.test.mjs`、`scripts/check-typecheck-contract.test.ts`），本卡复核重跑 exit 0，
  未重复实现。
- **AT-064**（jar 与生产前端产物）：`PackagedJarBootSmokeIT` 已覆盖 jar 健康与迁移；"浏览器跑生产产物"需要
  jar + MySQL/Redis + 浏览器三件同时在位；本卡交付的浏览器套件用的是**真实构建产物**（web-ele/ai-chat dist），
  对后端的端到端属未验证（见 §5）。

## 3.b 工具链踩坑（门禁拦下，已修）

- 依赖声明一度写成**精确版本字面量** `"@playwright/test": "1.63.0"`，被仓库 lint 规则
  `pnpm/json-enforce-catalog` 拒绝（与 `pnpm/yaml-no-unused-catalog-item` 同时报错：目录里有条目而没人引用）。
  按仓库约定改为 `"catalog:"` 并在 `pnpm-workspace.yaml` 的 catalog 里锁 1.63.0 后 lint 通过。
- 该教训说明：workspace 依赖必须走 catalog，不能写字面版本（CI 与本地同一规则）。

## 4. 阻断项（**需要扩范围或改范围才能通过，按要求不标 DONE**）

浏览器用例在真实运行中发现 **4 个已交付卡的缺陷**，其中 2 个直接导致验收项不过；这些文件**不在 Q06 §2 允许路径**内
（`packages/**` 未授权），因此本卡只提供可复现用例与定位，不擅自修改：

| # | 位置 | 现象 | 影响的验收项 |
|---|---|---|---|
| 1 | `packages/ai-embed-sdk/src/display/mount.ts` | `ChatMount` 无 `receive` 入口（宿主无法把 message 事件喂进内部桥）；`open()` 未等 iframe `load` 即 `start()` | AT-050/053 的端到端（用例已用真实桥绕过演示） |
| 2 | `packages/ai-embed-sdk/src/display/mount.ts` | **`destroy()` 后 `open()` 复活外壳**（overlay/iframe 0→1，桥已 `DESTROYED`） | **AT-055（1 例 test.fail）** |
| 3 | `packages/ai-embed-sdk/src/bridge/host-bridge.ts` | `dispatch()` 对 `NAVIGATE_REQUEST`/`REPORT_CREATED` 走 default → `MESSAGE_NOT_SUPPORTED`，C08 宿主校验器拿不到输入 | AT-051 的宿主侧导航/上报分支 |
| 4 | `packages/effects/common-ui/src/components/captcha/verification/verify-slide.vue`（`lucide:x` 等） | 运行期请求 `https://api.iconify.design/...` | **AT-067（1 例 fail）** |
| 5 | `apps/ai-chat/src/conversation-api.ts` | 失败原因不稳定（原始 `TypeError: Failed to fetch`） | **已在卡内修复（`33d7311`）**：稳定错误码 + 不回显正文；可见提示的根因在 `packages/ai-chat-ui`，见补丁 |

另有两项与实现无关但影响"全绿"：**AT-057 无真实 N-1 产物**（C10 首发只有 `ai-embed-sdk-5.6.0.js`，
只能做基线重放）；**AT-056 需要真实后端 + 应用允许域配置**（本机无后端）。

## 5. 未验证项（如实列出，均不得当 pass）

1. AT-056 全部（无后端）；AT-067 报表页（无登录态/后端）；AT-057 真实双产物联调（无 N-1 产物）。
2. 依赖模型/凭据的步骤（对话内容、报表修改）：用例注释统一标 `model-dependent: not configured in this environment`。
3. Windows runner 与 CI 实跑：本机只有 Linux + Chromium 153.0.8010.12，未在 Windows/CI 上跑过。
4. 真实网关（Nginx 头部透传、`frame-ancestors` 经反代）。
5. 夹具票据非平台票据：断言的是票据去向与隔离；平台签发需真实后端凭据（A04）。

## 6. 门禁接线方案（**补丁已就绪并验证，待授权后原子落地**）

ADR 0046 的落地步骤已做成可一键应用的补丁（见 §1.b 第二个补丁，含 sha256）：`scripts/check-e2e-smoke.mjs`
（拒绝"没跑用例却绿灯"：空套件/报告缺失/`expected=0`/覆盖不到全部用例文件/存在 unexpected 失败，任一即失败）、
同名拒绝测试、`.harness/verify.sh` + `verify.ps1` 的 `smoke` 门禁（与 `verify.sh` 逐项对齐）、
`nightly-browser-smoke.yml`（nightly + 手动触发，PR 层不引用，保持 ≤10 分钟反馈预算）与拓扑断言。

**为什么不直接落地**：`check-gate-wiring.mjs` 要求每个 `check-*.mjs` 与 `*.test.mjs` 必须在双 provider 直接执行；
只提交脚本不接线会让 contracts 门禁立刻变红，接线又会因 AT-067（跨卡缺陷 ④）长期变红。
按卡片纪律"关键项未通过不得标 DONE、不得用 skip/白名单掩盖"，本卡选择：**补丁 + 证据先交付，待授权修完
缺陷 ①–④ 后一次性接线**，届时 `sh .harness/verify.sh smoke` 应为 0。

## 6.b 本卡门禁结果（提交前实测）

| 门禁 | 退出码 | 说明 |
|---|---|---|
| `sh .harness/verify.sh contracts` | 0 | 含源码质量（本卡 22 个新文件均 ≤500 行）、拒绝测试、字段/生命周期/权限目录 |
| `sh .harness/verify.sh lockfile` | 0 | `pnpm install --frozen-lockfile --ignore-scripts`（catalog 引用后的锁文件） |
| `sh .harness/verify.sh frontend` | 0 | 类型检查、lint（含 catalog 规则）、单测与覆盖率、生产构建、产物 no-undef |
| `node scripts/check-coverage-ratchet.mjs --update` / `all` | 0 / 0 | 登记新文件；未下调任何既有基线 |

浏览器套件本身的实跑结果见 §2 与 §1.b（23 passed / 1 failed=AT-067 真实缺陷 / 3 skipped）。

## 7. 实际执行的命令与结果

| 命令 | 退出码 | 结果 |
|---|---|---|
| `pnpm install --frozen-lockfile --ignore-scripts`（lockfile 门禁口径） | 0 | `Lockfile is up to date` |
| `pnpm exec playwright install chromium` | 0 | Chrome for Testing 153.0.8010.12 安装成功（无需 sudo） |
| `pnpm exec playwright test --config tests/playwright/playwright.config.ts` | 1（预期） | 23 passed / **1 failed（AT-067 图标外发，真实缺陷）** / 3 skipped |
| `pnpm exec tsc -p tests/playwright/tsconfig.json --noEmit`、`eslint tests/playwright`、`prettier --check tests/**`、`cspell lint tests/**` | 0 / 0 / 0 / 0 | 工具链干净 |
| `node scripts/check-explicit-any.mjs`、`node scripts/check-typecheck-contract.mjs` | 0 / 0 | 6/8、38 包 |
| `pnpm exec vitest run tests/playwright --dom` | — | `No test files found`（vitest 不抢收 Playwright 用例，已实测） |

## 8. 建议的后续动作（按优先级）

1. **授权修 §4 的缺陷 1–4**（`packages/ai-embed-sdk/**`、`packages/effects/common-ui/**`）：修完 AT-055/067 即可转绿；
2. 接 §6 的门禁接线（`.harness` 双 provider + nightly workflow + 拒绝测试），届时浏览器门禁设为阻断；
3. 在有后端/模型的环境补 AT-056 与 AT-057（真实 N-1 产物需 C10 之后的首个版本才能产生）。

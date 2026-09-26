# F04 前端包与图表集成验证：完成证据

本文件是 [F04 建立前端包与图表集成验证](../ai-platform/tasks/F04.md) 的交付证据，格式按
[09-model-handoff.md](../ai-platform/09-model-handoff.md) §6 交接模板与 §8 完成证据要求。
配套交付物：[ChartRenderer 与前端包验证报告](ai-platform-chart-renderer-verification.md)（选型/包体/CSP/生命周期）。

## 0. 主管复核记录（2026-09-27）

- 本卡在独立 worktree（`分支 agent/f04`，基线 `d4d14aa`）完成，主管复核后用 `git cherry-pick -n 1f27458` 合入主仓并提交。
- 主管**独立复跑**了两条产物检查：`node apps/ai-chat/scripts/check-public-cdn.mjs` → exit 0
  （313 文件扫描，公共 CDN 0 命中、外部域名 13 个全部登记）；`node apps/ai-chat/scripts/verify-built-chat.mjs` → exit 0
  （挂载真实 `dist` 入口，图表块经共享适配渲染、无占位文本）。改动文件的规模检查：主线源码最大 500 行（脚本），未触 800 行上限。
- **门禁（在含 Q05 与 F04 的合并树上实测）**：`contracts` 0、`frontend` 0、`lockfile` 0、
  `check-coverage-ratchet --update` 0、`all` 0 —— 因此 Q05 提交后的小幅改名（`:refresh-key`）也在本次门禁覆盖范围内。
- 本卡发现的真实缺陷（图表块曾被 tree-shaking 摇成 0 字节 chunk、会话面板丢弃事件 block）已在合并前修复；
  另有"两套 G2 适配组件并存（`components/ChartRenderer.vue` 与 `chart/AiChart.vue`）"的清理建议留给后续卡。

## 1. 任务ID与状态

- 任务ID：**F04**（阶段 P0；需求 FR-14 / FR-15 / FR-27；依赖 F02，F02 证据见
  `docs/ai-platform/verification/f02-upstream-dependency-freeze-evidence.md`）。
- 本会话状态：**待复核（REVIEW）**；`docs/ai-platform/tasks/index.json` 的 `status` 字段是规划事实源，不由本卡改写。
- 工作副本：`/home/ctyun/桌面/zhongtai/ai-platform/.agent-worktrees/f04`（git worktree，分支 `agent/f04`，基线 `d4d14aa`）。
- 结论：三条验收项（缺 typecheck 被拒绝 / Chat 生产构建真实渲染 / 图表包体记录且无公共 CDN 请求）
  均有可运行证据；**真实浏览器渲染**（G2 canvas 实绘、CSP 报错采集、窄屏/键盘/内存）属 Q06/G5，本卡不声称完成。

## 2. 现状核实：已有能力与本次补齐

### 2.1 已有能力（C03/C07/R02 等已交付，本卡不重复实现）

| 能力 | 位置 | 证据 |
|---|---|---|
| 自有契约（ChartSpec/Theme/ResultBlock，zod 校验、无厂商类型） | `packages/ai-contracts` | F04 报告 §2、R02 证据 |
| 会话与消息渲染（引用/附件/Markdown/表格/报表卡片） | `packages/ai-chat-ui/src/{conversation,message,citation,attachment,report}` | C02/C03 证据 |
| 展示形态与生命周期（inline/drawer/dialog、destroy 幂等） | `packages/ai-chat-ui/src/layout`、`packages/ai-embed-sdk/src/display` | C07 证据 |
| 图表适配组件（G2 懒加载、表格降级、主题令牌、destroy 幂等） | `packages/ai-chat-ui/src/chart/AiChart.vue` | R02 证据 |
| 纯 TS 开放 API 客户端 | `packages/ai-embed-sdk` | C01 证据 |
| 依赖锁定：`@antv/g2: 5.4.8`（catalog 注释注明升级须重跑本报告） | `pnpm-workspace.yaml` | F04 报告 §1/§6 |
| typecheck 契约门禁（缺/占位即拒绝） | `scripts/check-typecheck-contract.mjs` + `scripts/check-typecheck-contract.test.ts`，接入 `pnpm check:type` | C07 证据、本文件 §4.1 |

### 2.2 本次核实发现的真实缺口（可复现）

1. **图表没有进入 Chat 生产产物**：C02 之后的 `ConversationPanel` 对非 text/error 结果块只渲染占位文本
   `（chart 结果块）`，且不消费运行事件携带的 `block`。Rollup 因此把 `@antv/g2` 整棵摇掉——
   复核前 `pnpm -F @vben/ai-chat run build` 的产物里 `vendor-antv-*.js` 是 **0.00 kB 的空 chunk**。
   既有 F04 报告的 1.3 MB 包体数字与当前产物不一致（即"验收项 3 找不到可记录的图表包体"）。
2. **没有构建产物级渲染验证**：既有测试是组件级（厂商被 mock），构建产物"能打包但不能证明能渲染"。
3. **没有可运行的公共 CDN 扫描**：此前只是人工 grep，无退出码、无登记表。
4. 缺 typecheck 的拒绝只有单元契约测试，缺少"现场故意漏掉 → 失败 → 恢复 → 通过"的端到端演示。

### 2.3 本次动作（最小闭环）

- 会话消息渲染接到共享图表适配组件 `AiChart`（R02），并把运行事件携带的结果块落到当前助手消息，
  使图表真实进入生产产物（懒加载分包）并有端到端渲染路径。
- 新增构建产物验证脚本（真实渲染 + 图表分包 + 体积记录）与公共 CDN 扫描脚本（含域名登记表）。
- 实跑并记录 typecheck 契约的现场拒绝演示与全部验收命令、退出码。

## 3. 变更文件清单

| 文件 | 动作 | 说明 |
|---|---|---|
| `前端代码/basic-framework-admin/packages/ai-chat-ui/src/conversation/ConversationPanel.vue` | 修改 | 图表块改用共享适配组件 `AiChart` 渲染（原先只显示 `（chart 结果块）` 占位文本） |
| `前端代码/basic-framework-admin/packages/ai-chat-ui/src/conversation/use-conversation.ts` | 修改 | `ConversationRunApi.streamRunEvents.onEvent` 增加可选 `block?: ResultBlock`；事件结果块经代次过滤后追加到当前助手消息 |
| `前端代码/basic-framework-admin/packages/ai-chat-ui/src/conversation/README.md` | 修改 | 记录"结果块不丢/图表由共享适配组件渲染/无脚本渲染"行为约定 |
| `前端代码/basic-framework-admin/packages/ai-chat-ui/src/conversation/__tests__/use-conversation.test.ts` | 修改 | 新增 5 例：事件块（文本/图表）落消息、切用户丢弃、空闲取消空操作、非失败态重试空操作、幂等键无 `crypto.randomUUID` 回退 |
| `前端代码/basic-framework-admin/packages/ai-chat-ui/src/conversation/__tests__/conversation-panel.test.ts` | 修改 | 新增 1 例：图表块经共享适配组件渲染（`ai-chart-canvas`），不再是占位文本 |
| `前端代码/basic-framework-admin/packages/ai-chat-ui/src/conversation/__tests__/fixtures.ts` | 新增 | 会话测试共用的结果块夹具（文本/图表） |
| `前端代码/basic-framework-admin/apps/ai-chat/scripts/verify-built-chat.mjs` | 新增 | 构建产物验证：结构 → 图表分包/体积/懒加载 → DOM 真实渲染（挂载产物 + fetch 桩驱动新建/发送/SSE 文本+图表块） |
| `前端代码/basic-framework-admin/apps/ai-chat/scripts/check-public-cdn.mjs` | 新增 | 公共 CDN 扫描：CDN 域名清单 + HTML/CSS 外部资源引用 + 未登记外部域名（登记表是数据文件） |
| `前端代码/basic-framework-admin/apps/ai-chat/scripts/public-cdn-registry.json` | 新增 | 域名登记表（`publicCdnHosts` 禁止项 + `registeredHosts` 用途说明） |
| `前端代码/basic-framework-admin/apps/ai-chat/package.json` | 修改 | 新增 `verify:built` / `verify:cdn` 脚本；devDependencies 增加 `happy-dom`（catalog，仅用于构建产物渲染验证） |
| `前端代码/basic-framework-admin/pnpm-lock.yaml` | 修改 | 上述新增依赖的三行锁记录（`happy-dom@20.11.15`） |
| `docs/integrations/ai-platform-chart-renderer-verification.md` | 修改 | 复测体积（含"空 chunk 回归"修订说明）、脚本化 CDN 扫描结论、门禁接线（38 包）与命令结果 |
| `docs/integrations/f04-frontend-chart-integration-evidence.md` | 新增 | 本文件 |

未触碰：`后端代码/**`、`数据库文件/basic_framework.sql`、`docs/contracts/**`、`.harness/**`、任何迁移与
`PersistenceLifecycleIT`、`AiErrorCodeConstants.java`、`cspell.json`（词表在共享文件，见 §6）。

## 4. 逐项验收证据

工作目录除注明外均为 `/home/ctyun/桌面/zhongtai/ai-platform/.agent-worktrees/f04/前端代码/basic-framework-admin`；
依赖安装：首次 `pnpm install --frozen-lockfile` 退出码 0（15.3s）。新增 `happy-dom` 后
`pnpm install --offline` **失败**（`ERR_PNPM_NO_OFFLINE_META`：本地元数据缓存没有该包，如实记录），
改用 `pnpm install`（本地 store + 镜像元数据）退出码 0，锁文件仅 +3 行。

### 4.1 验收项 1：缺 typecheck 应被拒绝

门禁脚本与契约测试（已存在，本卡实跑）：

| # | 命令 | 退出码 | 关键输出 |
|---|---|---|---|
| 1 | `node scripts/check-typecheck-contract.mjs` | 0 | `工作区类型检查契约通过：38 个含 tsconfig 的包全部登记` |
| 2 | `pnpm exec vitest run --dom scripts/check-typecheck-contract.test.ts` | 0 | `Test Files 1 passed`，3 例（含"rejects missing and ineffective typecheck registrations"） |
| 3 | `pnpm check`（含 `check:type` → 契约脚本 + `turbo run typecheck`） | 0 | 循环依赖 0、依赖检查通过、显式 any 基线内、`38 successful, 38 total`、cspell 0 问题 |

现场演示（一次性探针包 `packages/typecheck-probe`，命令执行后立即删除，`git status` 无残留）：

| 步骤 | 命令 | 退出码 | 关键输出 |
|---|---|---|---|
| 故意漏掉 `typecheck` | `node scripts/check-typecheck-contract.mjs` | **1** | `@vben/typecheck-probe: typecheck 应为 "tsc --noEmit"，当前为 undefined` |
| 用占位命令绕过（`typecheck: "echo skipped"`） | 同上 | **1** | `@vben/typecheck-probe: typecheck 应为 "tsc --noEmit"，当前为 "echo skipped"` |
| 恢复（删除探针） | 同上 | **0** | `工作区类型检查契约通过：38 个含 tsconfig 的包全部登记` |

结论：新 workspace 包漏登记 `typecheck` 时门禁变红（AT-062），占位命令同样被拒。

### 4.2 验收项 2：Chat 生产构建真实渲染

| # | 命令 | 退出码 | 关键输出 |
|---|---|---|---|
| 1 | `pnpm -F @vben/ai-chat run build` | 0 | `✓ 1522 modules transformed`、`dist/assets/vendor-antv-1_Bud82i.js 1,309.38 kB │ gzip: 388.29 kB`、`index-Da3RjNlS.js 75.33 kB`（详见 4.3 体积表） |
| 2 | `pnpm -F @vben/ai-chat-ui run typecheck` | 0 | `vue-tsc --noEmit --skipLibCheck` 通过 |
| 3 | `pnpm exec vitest run --dom packages/ai-chat-ui/src/conversation apps/ai-chat/src` | 0 | `Test Files 8 passed`，**46 例通过**（会话目录 23 例：含图表块渲染、事件块落消息、切用户丢弃、空闲取消/非失败重试空操作、幂等键回退） |
| 4 | `node apps/ai-chat/scripts/verify-built-chat.mjs` | 0 | 见下方逐条断言 |
| 5 | `pnpm -F @vben/web-ele run typecheck` | 0 | 管理端包类型检查通过（web-ele 有意不引图表库，用自研 SVG 渲染器过"生产 JS 无未定义全局"门禁） |
| 6 | `node scripts/run-frontend-build.mjs`（门禁的生产构建步骤） | 0 | web-ele `✓ built in 1m 11s`（`Tasks: 11 successful, 11 total`）；`Production JavaScript: 232 files passed no-undef validation` |

`verify-built-chat.mjs` 的断言（happy-dom 级别真实渲染，不是截图、也不声称浏览器验收）：

```text
[1/3] 产物结构
  ☑ index.html 仅引用本地资源（3 条：/assets/index-Da3RjNlS.js, /assets/vendor-vue-DhXRoc6X.js, /assets/index-BEiigErc.css）
  ☑ 入口 chunk：assets/index-Da3RjNlS.js
[2/3] 图表分包与体积
  ☑ 图表分包 vendor-antv-1_Bud82i.js：1278.70 kB（gzip 379.19 kB）
  ☑ 图表分包由入口动态 import（懒加载），首屏不为图表付体积
[3/3] 构建产物真实渲染
  ☑ 应用外壳渲染：h1=AI 助手
  ☑ 开放 API 基址已配置（状态：就绪）
  ☑ 会话列表按接口数据渲染：销售分析 重命名  删除
  ☑ 运行事件携带的文本块渲染到消息区
  ☑ 图表结果块渲染：降级表格（当前 DOM 环境无 canvas，按适配层约定降级）
  ☑ 消息区无占位文本（图表/文本块都由共享组件渲染）
  ☑ 渲染期降级告警 5 条已汇总（G2 在无 canvas 环境按约定降级为表格）
  请求桩命中：GET /app-api/ai/v1/ai/conversation/page | POST /app-api/ai/v1/ai/conversation/create | GET /app-api/ai/v1/ai/conversation/page | POST /app-api/ai/v1/ai/run/accept | GET /app-api/ai/v1/ai/run/events
```

口径说明：脚本挂载的是真实构建入口（`dist/assets/index-*.js`），只把 **HTTP 外部边界**替换为 fetch 桩
（POST 会话/受理、GET SSE 返回契约样例事件：`block:{kind:'text'}` 与 `block:{kind:'chart'}`）；
DOM 环境没有 canvas，G2 按适配层约定降级为表格并说明原因，因此断言"图表实例或降级表格"二者之一。
语义等价的组件级证据（厂商 mock，断言实例创建/销毁/选项）在 `packages/ai-chat-ui/src/chart/__tests__` 与
`conversation-panel.test.ts`。

### 4.3 验收项 3：图表包体记录且无公共 CDN 请求

图表分包体积（`pnpm -F @vben/ai-chat run build` 输出 + 脚本 `stat`/`gzipSync` 复核）：

| 产物 | 字节 | vite 口径 | gzip |
|---|---|---|---|
| `vendor-antv-1_Bud82i.js` 图表库（动态分包） | 1,309,384 | 1,309.38 kB | 388.29 kB |
| `index-Da3RjNlS.js` 应用+适配层 | 75,332 | 75.33 kB | 20.84 kB |
| `vendor-vue-DhXRoc6X.js` | 62,670 | 62.67 kB | 24.94 kB |
| `index-BEiigErc.css` | 1,381 | 1.38 kB | 0.45 kB |
| `index.html` | 612 | 0.61 kB | 0.42 kB |

修复前后对比：修复前 `vendor-antv-*.js` = **0.00 kB（空 chunk，图表被 tree-shaking 摇掉）**；
修复后 1,309,384 字节且为懒加载分包（首屏不为图表付体积，`index.html` 不预加载图表分包）。

公共 CDN：

| # | 命令 | 退出码 | 关键输出 |
|---|---|---|---|
| 1 | `node apps/ai-chat/scripts/check-public-cdn.mjs` | 0 | `扫描文件：313 个`；`公共 CDN 域名：0 命中`；`外部资源引用（HTML/CSS）：0 命中`；13 个外部域名全部登记并打印用途 |

外部域名清单（脚本输出摘要）：`www.w3.org ×102`（XML/SVG 命名空间常量）、`ai.example.com ×1` /
`api.example.com ×2`（`.env.production` 接口基址占位）、`element-plus.org ×10`、`vxeui.com ×8`、
`github.com ×1`（第三方库内置链接）、`api.iconify.design ×2` 等（Iconify 运行时图标 API 回退地址，
真实请求有无由 Q06 网络断言确认）、`vuejs.org ×2`（Vue 文档链接）、`localhost ×3`、
`crm.example.com`/`your-host.example.com`（页面示例文案/占位符）。模板变量（`https://${host}`、
`https://host/app-api`）按非域名处理并单独列出。

### 4.4 其他门禁结果

| 命令 | 退出码 | 结果 |
|---|---|---|
| `pnpm install --frozen-lockfile`（首次） | 0 | `Done in 15.3s` |
| `pnpm install --offline`（新增依赖后） | **1** | `ERR_PNPM_NO_OFFLINE_META`（本地无 happy-dom 元数据），改用在线 install |
| `pnpm install`（新增依赖后） | 0 | 锁文件 +3 行 |
| `pnpm lint`（prettier/eslint/stylelint） | 0 | 全绿 |
| `pnpm check` | 0 | 循环依赖 0、依赖检查通过、契约 38 包、typecheck 38/38、cspell 0 |
| `pnpm test:coverage` | 0 | `Test Files 368 passed (368)`、`Tests 1986 passed (1986)`；All files：语句 91.24% / 分支 88.44% / 函数 85.84% / 行 91.24%（阈值 81/87.6/80.2） |
| `node scripts/check-coverage-ratchet.mjs frontend` | 0 | `coverage-ratchet: frontend 单文件基线通过`（`use-conversation.ts` 96.36% → 100%，无下调、无未登记文件） |

## 5. 未验证项、阻塞与剩余风险

1. **真实浏览器渲染（Q06/G5）**：G2 canvas 实绘、严格 CSP 报错采集、窄屏/键盘走查、
   destroy 后内存观察（AT-044/054/055 的浏览器部分）。本卡只给构建产物 DOM 级与组件级证据。
2. **运行事件 `block` 通道的后端端到端**：前端按冻结契约消费（`run-event.schema.json` 的
   `block?: resultBlockSchema`），本次用契约样例事件驱动；真实后端在什么状态下发什么块属 O/R 卡范围，未验证。
3. **Iconify 运行时回退地址**是否真的不发请求（离线环境）：需 Q06 的浏览器网络断言。
4. **iframe 嵌入场景下的图表渲染**（C05/C06 外壳、C07 destroy）：需 Q06/G5。
5. **两个 G2 适配组件并存**：`components/ChartRenderer.vue`（F04 旧版：静态 import G2、无降级）与
   `chart/AiChart.vue`（R02：懒加载+表格降级+主题）。本次把会话面板接到 `AiChart`（收敛方向），
   但 C03 的消息层（`MessageBlockView`/`AiChatPanel`）仍用旧组件，属既有重复；
   建议后续卡统一到 `AiChart` 并删除旧组件（需重跑 C03/R02/F04 的验证）。
6. 图表包体 1.3 MB（gzip 388 kB）仍是最大单包：当前策略是"锁定版本 + 懒加载 + 记录体积"，
   进一步压缩（G2 按需引入/更轻渲染层）需另开任务，且必须重跑本报告 3.1–3.5。

## 6. 需要主管串行处理的共享文件补丁

本卡**未修改**任何受限共享文件（迁移、SQL 快照、`docs/contracts/**`、`enums/AiErrorCode*`、
`PersistenceLifecycleIT`、`.harness/**`）。

可选（非阻塞，二选一）：

1. 保持现状：公共 CDN 域名登记表放在数据文件 `apps/ai-chat/scripts/public-cdn-registry.json`
   （`cspell` 只检查源码 glob，因此无需改共享词表）。
2. 若主管希望把域名清单内联回脚本，需要下列词表补丁（`cspell.json` 的 `words` 数组，与既有
   `antd/antdv` 同例）：

```diff
--- a/前端代码/basic-framework-admin/cspell.json
+++ b/前端代码/basic-framework-admin/cspell.json
@@
     "words": [
+      "bootcdn",
+      "fastly",
+      "gstatic",
+      "simplesvg",
+      "unisvg",
+      "vxeui",
```

## 7. 契约与影响差异

- **无契约文件改动**（未新增/修改 `docs/contracts/**`、无迁移、无权限码、无字段目录变化）。
- 前端 API 变化（属本卡允许范围）：
  - `ConversationRunApi.streamRunEvents(runKey, { onEvent })` 的 `onEvent` 参数新增可选
    `block?: ResultBlock`（结构上兼容既有实现：`@vben/ai-embed-sdk` 的 `RunEvent` 本就带 `block?`）。
  - `ConversationPanel` 的图表块渲染改用共享组件 `AiChart`（DOM 从占位文本变为 `<figure class="ai-chart">`，
    `data-testid="ai-conversation-chart"`）。
- 上游依赖：无版本变化；`@antv/g2: 5.4.8` 保持 catalog 锁定。新增 `happy-dom`（devDependency，仅验证脚本用）。

## 8. 下一张可领取任务及其前置证据

- 前置证据（本卡已提供）：构建产物验证脚本与 CDN 扫描脚本、图表进入产物的体积记录、
  typecheck 拒绝演示、`AiChart` 接入会话面板的行为约定与测试。
- 可领取：**Q06**（真实浏览器验收）——需要本卡脚本作为基线，把"未验证项 §5.1/5.2/5.3/5.4"补齐；
  以及"统一 G2 适配组件"的清理卡（依赖 C03/R02/F04 的测试基线）。

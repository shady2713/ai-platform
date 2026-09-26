# ChartRenderer 与前端包验证报告（F04）

本记录是 [F04 建立前端包与图表集成验证](../ai-platform/tasks/F04.md) 的交付物：包结构、图表适配选型、
CSP/恶意标题/生命周期/包体实测结果与未验证项。原始日志与产物统计归档在 `.local-state/f04-frontend/`（Git 忽略）。

## 1. 结论摘要

| 决策 | 结果 |
|---|---|
| 图表唯一默认适配 | **@antv/g2 5.4.8**（morn 固定版本，见 `pnpm-workspace.yaml` catalog 注释） |
| GPT-Vis 候选 | **不采用**：`@antv/gpt-vis@1.0.1` 传递依赖 `@antv/g6`（图关系渲染）+ `@zumer/snapdom`（截图）+ `measury`，且 1.0.x 发布仅 13 天；平台已有自有 ChartSpec 契约，无需再引入 LLM 友好的第二套 spec |
| 自有契约 | `@vben/ai-contracts`：ChartSpec / Theme / ResultBlock，zod 校验，无厂商类型 |
| 渲染适配 | `@vben/ai-chat-ui` 的 `ChartRenderer`：ChartSpec → G2 实例，厂商类型不出组件 |
| 宿主 SDK | `@vben/ai-embed-sdk`：纯 TS，无 Vue 运行时依赖，按开放 API 契约调用 /runs |
| 独立应用 | `@vben/ai-chat`：最小可运行 Chat，只消费开放 API，不加载管理端配置 |

## 2. 包结构

```text
packages/ai-contracts/   ChartSpec / Theme / ResultBlock 契约与 zod 校验（纯 TS）
packages/ai-chat-ui/     AiChatPanel + ChartRenderer（Vue 组件，G2 适配唯一落点）
packages/ai-embed-sdk/   开放 API 客户端（受理/查询/取消，注入 fetch，无 Vue 依赖）
apps/ai-chat/            独立 Chat 构建入口（消费上述三包，独立 vite 配置）
```

依赖方向：app → chat-ui → contracts；app → embed-sdk → contracts。三个包均为源码导出（`exports` 指向 `src/`），
与仓库既有包（`@vben/constants`、`@vben/common-ui`）一致。

独立 Chat 刻意**不使用** `@vben/vite-config`：该共享配置注入管理端公开运行时配置
（`VITE_GLOB_API_URL`、`window._VBEN_ADMIN_PRO_APP_CONF_`），而架构约束要求独立 Chat 不加载后台路由、
管理权限与 ADMIN refresh 逻辑；本应用只读 `VITE_AI_API_BASE_URL` 访问开放 API。

## 3. 图表适配验证

### 3.1 生产构建体积（`pnpm -F @vben/ai-chat run build`，2026-09-26 复测）

| 产物 | 字节 | 原始（vite 口径 kB=1000） | gzip |
|---|---|---|---|
| `vendor-antv-*.js`（图表库，**动态分包**） | 1,309,384 | 1,309.38 kB | 388.29 kB（脚本口径 1,278.70 KiB / 379.19 KiB） |
| `index-*.js`（应用代码 + 图表适配层） | 75,332 | 75.33 kB | 20.84 kB |
| `vendor-vue-*.js` | 62,670 | 62.67 kB | 24.94 kB |
| `index-*.css` | 1,381 | 1.38 kB | 0.45 kB |
| `index.html` | 612 | 0.61 kB | 0.42 kB |

复测结论与修订说明（本表替换 2026-09-23 记录的 1,305.32 kB / gzip 387.21 kB）：

- **发现并修复真实回归**：C02 把独立 Chat 外壳换成 `ConversationPanel` 后，会话面板对非 text/error 块
  只渲染占位文本 `（chart 结果块）`，且运行事件携带的 `block` 被丢弃；Rollup 因此把 `@antv/g2`
  整棵摇掉——本次复测前 `vendor-antv` 是 **0.00 kB 的空 chunk**（即"图表集成"只存在于源码，
  不在交付产物里）。本次把会话面板接到共享适配组件 `AiChart`（R02）并消费运行事件结果块后，
  图表重新进入产物。
- **懒加载**：`AiChart` 只在出现图表块时 `import('@antv/g2')`；图表分包不进首屏、也不被
  `index.html` 预加载（`verify-built-chat.mjs` 断言"动态 import + 未预加载"）。首屏应用代码因此为 75.33 kB。
- 体积口径：vite 报告 1,309.38 kB（1000 进制），验证脚本按 1024 进制报告 1,278.70 KiB；
  两者是同一文件（1,309,384 字节）。gzip 同理（388,290 字节）。

### 3.2 公共 CDN 请求

可运行扫描：`node apps/ai-chat/scripts/check-public-cdn.mjs`（退出码 0）。规则与域名登记表放在
数据文件 `apps/ai-chat/scripts/public-cdn-registry.json`，三层规则：

1. 公共 CDN 域名（清单在登记表）出现即失败；
2. HTML/CSS 里的外部资源引用（`script`/`link`/`@import`/`url()`）出现即失败——这类引用一定发网络请求；
3. 其他外部域名必须登记用途，未登记即失败（避免"新引入的远程地址"被默默放行）。

扫描范围：`apps/ai-chat/dist`（必需）、`apps/web-ele/dist`（存在即扫）与四个 AI 前端包的 `src`；
测试夹具（`__tests__`、`*.test.*`）不参与。本次结果：**313 个文件，公共 CDN 域名 0 命中、
HTML/CSS 外部资源引用 0 命中**；出现的非 CDN 域名全部登记并在输出中列出，例如
`www.w3.org`（XML/SVG 命名空间常量 ×102）、`vuejs.org`（Vue 警告里的文档链接 ×2）、
`ai.example.com`/`api.example.com`（`.env.production` 的接口基址占位）、`element-plus.org`、
`vxeui.com`、`github.com`（第三方库内置链接）、`api.iconify.design` 等（Iconify 运行时图标 API
回退地址，真实请求有无由 Q06 网络断言确认，见 §7）。`index.html` 只引用本地 `/assets/*`。

### 3.3 严格 CSP

- 产物中未出现 `eval(`；出现 **1 处 `new Function(`**，定位为 d3-dsv 的 DSV 行转换函数
  （`function $U(e){return new Function("d","return {"+...}`），由 G2 依赖链带入。
- 平台的 `ChartSpec` 只接受结构化数组（`categories: string[]`、`series[].data: number[]`），
  不提供 CSV/DSV 文本入口，因此该分支在平台数据路径上不可达；严格 CSP（无 `unsafe-eval`）
  下渲染不会触发它。
- **未验证**：浏览器内真实 CSP 报错采集需要真实浏览器，按任务卡约定留待 Q06 浏览器门禁；
  本卡只给出静态证据与数据路径论证。

### 3.4 恶意标题与注入面

- 标题与类目全部走 Vue 文本插值，不使用 `v-html`；组件测试断言：标题为
  `<img src=x onerror="...">` 时，DOM 中不出现 `img`/`script` 元素、不执行脚本、文本按原文展示。
- `ResultBlock` 为判别联合，未知 `kind` 在 zod 解析期拒绝，渲染器不会"尽力而为"渲染好奇数据。

### 3.5 生命周期（resize/destroy）

- `ChartRenderer` 对外只暴露 `resize()`/`destroy()`；`onBeforeUnmount` 与 spec 变更都会销毁旧实例
  （测试断言 `destroy` 调用次数与重建顺序），避免 canvas 与监听器泄漏。
- `resize()` 使用容器实际尺寸调用 G2 `changeSize`（测试以假实现断言入参为容器宽高）。

## 4. 门禁接线与拒绝演示

| 项 | 证据 |
|---|---|
| typecheck 契约 | `check-typecheck-contract.mjs`：38 个含 tsconfig 的包全部登记（2026-09-26 复测）；AI 四个包分别使用 `tsc --noEmit`（纯 TS）与 `vue-tsc --noEmit --skipLibCheck`（含 Vue） |
| 缺 typecheck 被拒绝（现场演示） | 一次性探针包 `packages/typecheck-probe`（创建后立即删除）：缺 `typecheck` → 退出码 1，`@vben/typecheck-probe: typecheck 应为 "tsc --noEmit"，当前为 undefined` |
| 错误命令被拒绝（现场演示） | 探针包 `typecheck: "echo skipped"` → 退出码 1，`当前为 "echo skipped"`；删除探针后 → 退出码 0 |
| 契约测试 | `scripts/check-typecheck-contract.test.ts`（`pnpm test:unit`/`test:coverage` 会执行）覆盖"缺失"与"占位绕过"两类拒绝 |
| 依赖完整性 | `vsh check-dep`：新增包依赖与使用一致（本次新增 `happy-dom` 仅用于构建产物渲染验证脚本） |
| 覆盖率范围 | `vitest.config.ts` 含 `apps/ai-chat/src/**`；入口 `main.ts` 排除 |
| 生产构建 | `pnpm -F @vben/web-ele run build`、`pnpm -F @vben/ai-chat run build` 退出码均为 0；`run-frontend-build.mjs` 对 web-ele 产物做 no-undef 校验 |
| 构建产物真实渲染 | `node apps/ai-chat/scripts/verify-built-chat.mjs` 退出码 0：挂载构建入口（happy-dom）、注入 fetch 桩驱动"新建会话→发送→SSE 文本+图表结果块"，断言 DOM 真实渲染与图表分包（懒加载、体积） |
| 无公共 CDN | `node apps/ai-chat/scripts/check-public-cdn.mjs` 退出码 0（规则见 3.2） |

## 5. 测试与检查结果

### 5.1 复测（2026-09-26，分支 `agent/f04`，工作副本 `.agent-worktrees/f04`）

| 命令 | 退出码 | 结果 |
|---|---|---|
| `pnpm install --frozen-lockfile` | 0 | 依赖安装一致（`Done in 15.3s`） |
| `pnpm check` | 0 | 循环依赖 0、依赖检查通过、typecheck 契约 38/38、`turbo run typecheck` 38 成功、cspell 0 |
| `pnpm lint` | 0 | prettier / eslint / stylelint 全部通过 |
| `pnpm test:coverage` | 0 | **368 个测试文件 / 1986 用例全部通过**；语句 91.24% / 分支 88.44% / 函数 85.84%（阈值 81/87.6/80.2） |
| `node scripts/check-coverage-ratchet.mjs frontend` | 0 | 单文件基线通过（`use-conversation.ts` 96.36% → 100%） |
| `pnpm -F @vben/ai-chat run build` | 0 | 产物如 3.1 |
| `node scripts/run-frontend-build.mjs` | 0 | web-ele 生产构建 + `Production JavaScript: 232 files passed no-undef validation` |
| `node apps/ai-chat/scripts/verify-built-chat.mjs` | 0 | 结构/图表分包/真实渲染全部通过（见 F04 证据 §4.2） |
| `node apps/ai-chat/scripts/check-public-cdn.mjs` | 0 | 313 个文件，公共 CDN 0 命中、外部资源引用 0 命中（见 3.2） |

### 5.2 原始记录（2026-09-23 首次交付会话，未在本次复跑）

| 命令 | 结果 |
|---|---|
| `sh .harness/verify.sh lockfile` | 退出码 0（新依赖与锁文件一致） |
| `sh .harness/verify.sh dependencies` | 退出码 0（后端 SBOM、前端锁文件、Dockerfile 配置、应用镜像四段扫描 0 命中） |
| `sh .harness/verify.sh frontend` | 退出码 0（check + lint + 覆盖率 + 生产构建 + 单文件覆盖率棘轮） |

本次会话按上述 harness 的**各步骤**逐项复跑（check / lint / test:coverage / run-frontend-build /
覆盖率棘轮，退出码均 0），但未把 `sh .harness/verify.sh frontend` 作为单条命令整体执行。

新增测试：ChartSpec/Theme/ResultBlock 校验（含恶意输入与越界值）、ChartRenderer（渲染桥接、标题转义、
销毁/重建、resize）、AiChatPanel（分块渲染、提交裁剪、禁用态）、SDK 客户端（幂等键长度、路径与头、
业务错误码→稳定错误、缺 data）、会话逻辑（未配置/成功/失败/并发进行中；2026-09-26 增补事件结果块落消息、
切用户丢弃、取消/重试空操作、幂等键回退，会话面板增补图表块经共享适配组件渲染）。

## 6. 变更与范围说明

- 共享文件改动（2026-09-23 首次交付，已在本报告记录）：`pnpm-workspace.yaml`（catalog 增加 `@antv/g2: 5.4.8`）、
  `pnpm-lock.yaml`、`vitest.config.ts`（覆盖率范围）、`cspell.json`（词表增加 `antv`，与既有 `antd/antdv` 同例）。
- 2026-09-26 复测的改动（分支 `agent/f04`，未触碰任何受限共享文件）：
  - `packages/ai-chat-ui/src/conversation/{ConversationPanel.vue,use-conversation.ts,README.md}`：图表块改用
    共享适配组件 `AiChart` 渲染；运行事件携带的 `block` 落到当前助手消息。
  - `apps/ai-chat/scripts/{verify-built-chat.mjs,check-public-cdn.mjs,public-cdn-registry.json}`：构建产物
    验证与公共 CDN 扫描；`apps/ai-chat/package.json` 增加 `verify:built`/`verify:cdn` 与 `happy-dom`（catalog）。
  - `pnpm-lock.yaml` +3 行（`happy-dom@20.11.15`）。
  - 公共 CDN 域名清单放在数据文件里，是为了不改共享词表 `cspell.json`（`cspell` 只检查源码 glob）；
    若希望内联回脚本，需给 `cspell.json` 的 `words` 补 6 个词（补丁见 F04 证据 §6）。
- 未新增数据库迁移；未改动后端。

## 7. 未验证项与后续条件

1. **真实浏览器渲染与 CSP 报错采集（Q06/G5）**：G2 canvas 实绘、严格 CSP 报错、窄屏/键盘走查、
   destroy 后内存观察；本卡只给构建产物 DOM 级（happy-dom，G2 按约定降级为表格）与组件级（厂商 mock）证据。
2. **运行事件 `block` 通道的后端端到端**：前端按冻结契约消费（`run-event.schema.json` 的 `block?`），
   本次以契约样例事件驱动；真实后端在何种状态下发块属 O/R 卡范围，未验证。
3. **Iconify 运行时图标 API 回退地址**在离线环境是否真的不发请求：需 Q06 浏览器网络断言。
4. **两个 G2 适配组件并存**：`components/ChartRenderer.vue`（静态 import、无降级）与 `chart/AiChart.vue`
   （懒加载 + 表格降级）。本次会话面板统一到 `AiChart`；C03 消息层仍用旧组件，建议后续卡收敛并删除旧组件。
5. 图表包体优化（按需引入/更轻渲染层）：需要时另开任务，升级 G2 或调整引入方式都必须重跑本报告的 3.1–3.5。
6. GPT-Vis 重新评估条件：若后续需要关系图/网络图能力（G6 场景）或官方发布进入稳定维护期，重跑候选对比。

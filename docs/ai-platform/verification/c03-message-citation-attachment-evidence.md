# C03 实现结构化消息与引用附件渲染 — 完成证据

| 项目 | 内容 |
|---|---|
| 任务卡 | [C03](../tasks/C03.md) |
| 状态 | DONE（本文件记录的命令均已实际执行；真实浏览器验收见"未验证项"） |
| 需求 | FR-12（会话与结果：附件/引用/图表/报表卡片）、FR-19（引用可核验）、FR-33（附件受控访问） |
| 依赖 | C02（会话状态与消息组件，证据 `c02-conversation-state-evidence.md`）、K08（检索问答与引用，证据 `k08-rag-evidence.md`）、R07（报表预览页，证据 `r07-report-page-evidence.md`） |
| 工作副本 | `/home/ctyun/桌面/zhongtai/ai-platform` |
| 变更范围 | 仅 `packages/ai-chat-ui/src/{message,citation,attachment}` 与该包公开出口 `src/index.ts`；未改后端、未改迁移、未改契约 Schema |

## 1. 变更文件清单

### 消息块与受控 Markdown（`packages/ai-chat-ui/src/message`）

- `blocks.ts`：**首期消息块目录**与判别校验。冻结 v1 的 text/chart/error 直接交给
  `@vben/ai-contracts` 的 `parseResultBlocks`（不在本层复制第二份校验器）；首期新增
  table/report/citation/file/action/clarification 在此逐字段校验（键白名单、长度/取值域、
  `reportId` 用平台契约的 `^rpt_` 正则）。`toRenderableBlock(s)` 把解析失败转成
  `unsupported`（带来源类型与原因）；`cellDisplay` 空值显示 `—`；`isActionExpired` 只认
  `EXPIRED` 与到期时间（"已处理"不是"过期"）。
- `markdown.ts`：受控 Markdown → **允许节点**。块级（标题/段落/引用/列表/围栏代码）与行内
  （强调/加粗/行内代码/链接）各成判别联合；原始 HTML **从不解析**；
  `isAllowedLinkUrl` 在**解析期**做协议 + Origin 校验；嵌套深度、行数、文本与代码长度都有上限。
- `MarkdownInlineNodes.vue` / `MarkdownBlockNodes.vue`：节点递归渲染；`<a>` 只出现在已校验的链接节点上
  （固定 `rel="noopener noreferrer nofollow"`），标题标签在 h1–h6 内收敛，其余一律文本插值。
- `ResultTable.vue`：表格块；取值原样展示（不做浮点再格式化，金额文本不损失精度），空值为 `—`，
  非 `COMPLETE` 显示完整性、`pageInfo` 显示分页。
- `ActionCard.vue`：待确认动作；展示工具名、参数摘要、到期时间；按钮**只 emit `actionId`**；
  已处理/已过期不再给按钮。
- `ClarificationCard.vue`：追问；候选项按钮 + 自由输入，提交后提示"已提交，等待新的结果"（不伪装成功）。
- `MessageBlockView.vue`：单块判别渲染；图表用共享 `ChartRenderer`、报表用共享 `AiReportView`；
  引用/附件卡片用宿主注入的端口。
- `MessageList.vue`：消息列表与**逐块降级**入口；交互副作用只往上 emit（answer / confirm / reject / openReport）。
- `README.md`：模块说明、契约来源与差异、行为约定。

### 引用（`packages/ai-chat-ui/src/citation`）

- `citation.ts`：`CitationApi` 端口（`readSnippet` / `readOriginal`）、`citationLabel` /
  `citationSnippetText` / `canOpenOriginal`、`openCitationSnippet` / `openCitationOriginal`
  与固定失败提示（不回显宿主异常文本）。
- `CitationCard.vue`：引用卡片；无片段说明"请打开原文核对"；无文档编号不渲染"打开原文"；
  打开失败清空内容并提示。
- `README.md`：端口实现示例（票据只在请求头）与行为约定。

### 附件（`packages/ai-chat-ui/src/attachment`）

- `attachment.ts`：`AttachmentApi` 端口（`read` / `download`）、`formatSize`、`sanitizeFileName`、
  `isPreviewable`、`attachmentSummary` 与受控读取流程。
- `AttachmentCard.vue`：附件卡片；文本类给"预览"，全部给"下载"；失败固定提示。
- `README.md`：端口实现示例（宿主用当前票据请求应用端文件接口）与行为约定。

### 包出口

- `packages/ai-chat-ui/src/index.ts`：新增 `MessageList` / `MessageBlockView` / `CitationCard` /
  `AttachmentCard` 与 4 个模块的公开函数与类型；`message` 的 `ReportBlock` 与报表层同名，
  导出时改名 `MessageReportBlock`。

### 测试

- `message/__tests__/markdown.test.ts`（12 例）、`message/__tests__/blocks.test.ts`（10 例）、
  `message/__tests__/message-list.test.ts`（25 例）、`citation/__tests__/citation.test.ts`（8 例）、
  `attachment/__tests__/attachment.test.ts`（8 例），合计 **63 例**。

**本卡不新增迁移、不改后端**（纯前端渲染与受控读取层）。

## 2. 与卡片逐步实施的对应

| 卡步骤 | 实现 | 验证 |
|---|---|---|
| 1. ResultBlock 判别渲染，Markdown AST 转允许节点 | `blocks.ts` 的判别联合 + `markdown.ts` 的允许节点 AST + `MarkdownBlockNodes`/`MarkdownInlineNodes` 渲染 | `markdown.test.ts`（结构、嵌套深度、上限、未闭合围栏）；`message-list.test.ts`（全部允许节点各自渲染） |
| 2. 引用/文件走受控 API | `citation.ts` / `attachment.ts` 的端口 + 卡片；块里**没有任何 URL 字段** | `citation.test.ts`（成功/失败/空响应）；`attachment.test.ts`（清洗文件名、预览条件、失败）；`message-list.test.ts`（缺权限时原文不可打开、附件下载失败提示） |
| 3. 图表/报表使用共享组件，action/clarification 有明确交互 | `MessageBlockView` 复用 `ChartRenderer` 与 `AiReportView`；`ActionCard` / `ClarificationCard` | `message-list.test.ts`（图表 canvas、报表内联规格、动作确认只 emit actionId、追问点选项 emit answer） |

### 卡片验收项

| 验收项 | 结论 | 证据 |
|---|---|---|
| AT-026 引用不被编造、可核验（前端侧） | 通过：引用标识只来自服务端（校验用 K06 应用端 VO 的取值形态），片段只取服务端内容，没有编号就不给"打开原文" | `citation.test.ts`（`canOpenOriginal` 的三态）、`blocks.test.ts`（`citationId` 非法形态被拒）、`message-list.test.ts`（未接端口时的只读提示） |
| AT-043 ReportSpec 非法结构/HTML → 拒绝，无脚本执行 | 通过：报表块内联规格复用 `parseReportSpec`（非法即拒）；文本里的 HTML 只作为文本；危险链接不是链接节点 | `blocks.test.ts`（非法 spec 抛"报表数据不合法"）、`markdown.test.ts`（HTML 全为文本）、`message-list.test.ts`（`<script>` 不产生元素、`globalThis.__xss` 未定义） |
| AT-048 保存后用户失权 → 按当前 ACL 处理 | 通过：引用片段/原文、附件预览/下载都只以**本次**响应为准，失败即清空内容并提示 | `citation.test.ts` + `attachment.test.ts`（失败分支）、`message-list.test.ts`（失权时无内容、提示固定文本） |
| script/危险 URL 不执行 | 通过 | `markdown.test.ts`（`javascript:`/`data:`/`file:`/协议相对/相对地址 全部不是链接）、`message-list.test.ts`（只渲染一个 `<a>`，其 `href` 为白名单地址） |
| 缺权限原文不可打开 | 通过 | `message-list.test.ts`（端口 reject → 固定提示且 `pre` 不渲染；断言 HTML 里不含异常文本里的 `secret-value`） |
| 未知类型明确降级 | 通过 | `blocks.test.ts`（未知 kind、平台草案 `type` 判别键、非对象输入）、`message-list.test.ts`（降级提示含来源类型，且同消息其它块照常渲染） |

## 3. 关键约束落地

- **契约先行**：块字段全部取自仓库内权威来源——冻结 v1 Schema（text/chart/error）、平台 API 契约
  `docs/ai-platform/contracts/openapi-core.json` 的 `ResultBlock`（clarification/report）、
  后端应用端 VO（`AiKnowledgeSearchRespVO.Citation`、`AiFileUploadRespVO`）与设计契约 §5 的块目录；
  本层不新增字段语义、不复制校验器（冻结三类直接调用 `ai-contracts`）。
- **无注入面**：无 `v-html` / `innerHTML` / `eval`；AST 只含允许节点；`<a>` 只由已校验的链接节点产生。
  代价是"链接地址含多层括号或空白"不会被解析成链接（保持文本），已在 README 写明。
- **不泄露凭据**：端口失败一律输出固定提示，宿主异常文本（可能含请求地址与票据参数）不进界面；
  用例显式断言输出里不含异常文本中的假凭据串。
- **不新增依赖**：只用 `vue` 与 `@vben/ai-contracts`（均为本包既有依赖）；未改 lockfile、父 POM、
  请求器或 Harness 拓扑；未新增 `any`、未新增 eslint/stylelint 白名单。
- **覆盖率为升不降**：13 个新文件登记单文件基线（最低 91.02% 行覆盖，`MessageBlockView.vue` 98.48%、
  `blocks.ts` 93.27%、`markdown.ts` 98.45%）；唯一变化的既有条目是后端 `AdminUserServiceImpl.java`
  87.63% → 87.77%（上一提交 `16d77ff` 修复后的真实提升），**没有任何条目下降**。

## 4. 实际执行的命令与结果

工作目录：`/home/ctyun/桌面/zhongtai/ai-platform`（前端命令在 `前端代码/basic-framework-admin`）。

| 命令 | 退出码 | 结果 |
|---|---|---|
| `pnpm exec vitest run --dom packages/ai-chat-ui/src/{message,citation,attachment}` | 0 | `Test Files 5 passed`，`Tests 63 passed`（markdown 12 / blocks 10 / message-list 25 / citation 8 / attachment 8） |
| `pnpm run check:type` | 0 | 37/37 任务通过（含 `@vben/ai-chat-ui` 的 `vue-tsc --noEmit`） |
| `pnpm run check` | 0 | 环形依赖、依赖声明、显式 any、typecheck、cspell（999 文件 0 问题）全部通过 |
| `pnpm run lint` | 0 | prettier + eslint + stylelint 全部通过（首轮 15 处报错已修：正则控制字符/超线性回溯、事件名大小写、SFC 格式） |
| `pnpm run test:coverage` | 0 | `Test Files 346 passed`、`Tests 1860 passed`，全局阈值通过 |
| `node scripts/check-coverage-ratchet.mjs --update` / `all` | 0 / 0 | 登记 13 个新文件基线；`all 单文件基线通过` |
| `sh .harness/verify.sh contracts` | 0 | 见第 6 节 |
| `sh .harness/verify.sh frontend` | 0 | 见第 6 节 |

新文件单文件覆盖率（`coverage/coverage-summary.json`，行 / 分支）：

| 文件 | 行 | 分支 |
|---|---|---|
| `message/blocks.ts` | 93.27 | 88.07 |
| `message/markdown.ts` | 98.45 | 81.13 |
| `message/MessageList.vue` | 100 | 100 |
| `message/MessageBlockView.vue` | 98.48 | 79.31 |
| `message/ResultTable.vue` | 100 | 100 |
| `message/ActionCard.vue` | 100 | 100 |
| `message/ClarificationCard.vue` | 100 | 92.30 |
| `message/MarkdownBlockNodes.vue` | 100 | 100 |
| `message/MarkdownInlineNodes.vue` | 100 | 100 |
| `citation/citation.ts` | 100 | 100 |
| `citation/CitationCard.vue` | 92.47 | 85.71 |
| `attachment/attachment.ts` | 100 | 90.90 |
| `attachment/AttachmentCard.vue` | 91.02 | 86.95 |

## 5. 新增/变化的对外契约与上游差异

- **前端 API**：`@vben/ai-chat-ui` 新增 `MessageList`、`MessageBlockView`、`CitationCard`、
  `AttachmentCard` 组件，以及 `parseMessageBlock` / `toRenderableBlock(s)` / `parseMarkdown` /
  `parseMarkdownInline` / `isAllowedLinkUrl` / `citation.ts` / `attachment.ts` 的公开函数与类型。
- **上游契约差异（需拥有方任务处理，未擅自扩散）**：
  1. 冻结 v1 的 `result-block.schema.json` 只有 text/chart/error；首期目录里的
     table/report/citation/file/action/clarification 目前只在**本包**按上述权威来源校验。
     按 `docs/contracts/ai/README.md` 的变更流程，把它们并入正式 Schema 需要**两侧实现同步**
     （Java `AiResultBlockDTO` + TS `@vben/ai-contracts`）并补跨语言夹具，而 Java 侧的夹具测试会
     枚举 `docs/contracts/ai/samples/` 全部文件——超出本卡允许路径，故留给拥有方任务（X01 已登记
     会扩展 ResultBlock 枚举）。
  2. 平台 API 草案用 `type` 作判别键、冻结 v1 用 `kind`。本层统一按 `kind` 接受，`type` 形态会被
     明确降级并在提示里写出来源类型，避免两套判别键在渲染层并存。
  3. 引用块新增**可选** `documentId`（用于"打开原文"）：K06 的应用端引用 VO 目前没有该字段，
     缺失时"打开原文"入口不渲染（不是给一个必然失败的按钮）。
- **字段/权限/迁移**：无新增（未改 DB、未改权限码、未改菜单）。

## 6. 门禁结果

| 命令 | 退出码 | 结论 |
|---|---|---|
| `sh .harness/verify.sh contracts` | 0 | 字段目录、生命周期、数据权限、权限目录、安全信号、异常期、覆盖率棘轮测试等全部通过 |
| `sh .harness/verify.sh frontend` | 0 | `check`（vue-tsc 37/37 + cspell）+ `lint` + `test:coverage`（346 文件 / 1860 例）+ 生产构建 `Production JavaScript: 228 files passed no-undef validation` + 前端棘轮 |

## 7. 顺带修复的依赖缺口（真实暴露）

1. **报表/图表块的形状在测试里写错了一次**：`chart` 块的 ChartSpec 是**嵌套**在 `spec` 下
   （`{ kind: 'chart', spec: {...} }`），首轮夹具按扁平写法导致"不符合冻结 v1"——说明本层对形状
   的严格拒绝确实生效；夹具已按冻结契约修正。
2. **正则的两类真实风险**：链接地址原本不允许多层括号，导致 `[x](javascript:alert(1))` 根本没被
   当作链接候选而留在正文里（安全但语义混乱）；改为"最多一层成对括号"后这类输入被捕获并在协议
   校验处拒绝。同时门禁的 `regexp/no-super-linear-backtracking` 指出围栏/标题/列表正则存在
   可交换量词（外部文本上的多项式回溯）——改为字符级判断与 `\S` 起头的内容组。
3. **控制字符正则被门禁拒绝**（`no-control-regex`）：改为字符码扫描的
   `stripControlCharacters` / `hasControlCharacter`，既满足门禁也让"为什么拒绝"直接可读。
4. **动作状态语义**：最初把 `CANCELLED` 也算作"过期"，界面会把"已处理"说成"已过期"。
   现改为"已处理优先于过期"，两种状态各有明确文案与用例。

## 8. 未验证项与已知边界

1. **真实浏览器验收未完成（关键项未验）**：卡片要求的"安全浏览器测试"在 Q06 建立浏览器门禁前
   不存在可执行命令，本卡证据是组件级（happy-dom）+ 单元级测试。跨源、真实 CSP、真实 EventSource
   与"点击危险链接后浏览器侧行为"必须在 Q06/G5 用真实浏览器补齐。
2. **宿主未接入**：本层尚未被 `apps/ai-chat`（C05/C09 的独立页与管理端）装配——C03 的允许路径只
   含 `ai-chat-ui` 的三个目录，宿主装配属于 C05/C09。当前 `ConversationPanel` 仍用自己的最小块渲染，
   接入 `MessageList` 时需一并注入 `CitationApi` / `AttachmentApi` 端口。
3. **web-ele 不得直接导入本层**：`MessageBlockView` 复用共享 `ChartRenderer`/`AiReportView`，
   会连带 `@antv/g2` 进入产物，而 `scripts/check-built-javascript.mjs` 对 `apps/web-ele/dist`
   全量 no-undef（已知门禁约束）。后台页面若需消息渲染，需按 C09 的路径规划处理依赖，不能直接引用。
4. **"最外层括号链接"与参考实现差异**：链接地址只支持最多一层成对括号且不跨空白；更复杂的地址
   （多层括号、含空格、含转义）保持为文本，不做"尽力解析"。
5. **二进制原文/附件**：`readOriginal` / `read` 端口契约返回**文本预览**；二进制下载由宿主实现
   `download(fileId, fileName)`（blob 与 objectURL 的生命周期属宿主），本层不生成任何直链。
6. **环境缺口（非本卡）**：`sh .harness/verify.sh dependencies` 因 Trivy 漏洞库镜像不可达仍失败
   （历次记录一致）；本卡未改依赖与镜像，未重跑该门禁。

# R07 交付报表预览与个人报表页面 — 完成证据

| 项目 | 内容 |
|---|---|
| 任务卡 | [R07](../tasks/R07.md) |
| 状态 | DONE（本文件记录的命令均已实际执行；浏览器验收见"未验证项"） |
| 需求 | FR-27, FR-28, FR-29 |
| 依赖 | R02（图表适配）、R05（对话修改）、R06（刷新）均已有证据文档 |
| 工作副本 | `/home/ctyun/桌面/zhongtai/ai-platform` |

## 1. 变更文件清单

### 报表渲染层（`packages/ai-chat-ui/src/report`，Chat 与报表页共用）

- `reportSpec.ts`：ReportSpec v1 与版本数据的前端契约（**手写校验器**，不引入新依赖）：
  键白名单、块判别联合（metric/text/table/chart）、12 列栅格、取值域、脚本片段检测；
  以及块数据 → 视图模型（图表块 → `ChartSpec`，不可用时给降级原因）、单元格文本化、指标格式化。
- `AiReportView.vue`：四类块的渲染（指标/文字/表格/图表）+ 元信息条（完整性、来源、失败原因、截至时间文本）；
  12 列 CSS grid（`minmax(0, 1fr)` + `min-width: 0` + 表格 `overflow-x: auto`）保证窄容器不溢出；
  全部内容文本插值（无 `v-html`）。
- `README.md`：模块说明与行为约定。
- `packages/ai-chat-ui/src/index.ts`：导出组件与解析函数/类型。

### 个人报表页面（`apps/web-ele`）

- `src/api/ai/report/index.ts`：报表域应用端 API（`/app-api` 通道 + 内存票据）：
  分页/详情/版本列表/当前版本/指定版本/保存/对话修改/刷新/刷新状态；类型与后端 VO 一一对应。
- `src/views/ai/report/data.ts`：权限码、模式与刷新/修订结果文案映射、预览组装（规格与数据都再解析一次）、
  版本选项、刷新可用性判定。
- `src/views/ai/report/ReportPreview.vue`：页面版预览（四类块 + 元信息条），与共享包 `AiReportView` **同口径**
  （解析、列白名单、缺失值不补 0、文本插值），但不引入图表库（见下）。
- `src/views/ai/report/ReportChartSvg.vue`：**零依赖** SVG 图表（柱状/折线/饼图 + 图例保留原始值文本）。
- `src/views/ai/report/index.vue`：换取票据 → 报表列表 → 预览（版本切换）+ 刷新 + 对话修改；
  失败状态（原因 + 时间）与澄清追问如实展示；**不提供**分享/发布按钮。

> **为什么不直接用共享包的 `AiReportView`**：web-ele 的生产产物要过"生产 JS 无未定义全局"门禁
> （`scripts/check-built-javascript.mjs` 对 dist 全量 `no-undef`），而 antv 的 canvas 渲染器里引用了全局
> `ImagePool`（上游写法，`typeof` 守卫也过不了该门禁）。把 antv 打进 web-ele 会拉红门禁；本卡**不放宽门禁**，
> 因此页面用零依赖 SVG 渲染图表，共享包的 `AiChart`/`AiReportView` 继续服务嵌入端宿主（apps/ai-chat，
> 其产物不在该门禁扫描范围内）。两处的解析、口径与降级语义完全一致（同一份 `reportSpec.ts` 与 `chartSeries`）。

### 数据库（仅新增迁移号）

- `basic-framework-server/src/main/resources/db/migration/V78__ai_report_menu.sql`：
  菜单与权限点 4100（`ai:report:preview`，type=2，挂在 4000 AI 中台目录下，component `ai/report/index`）。

### 契约台账

- `数据库文件/basic_framework.sql`：菜单种子同步；快照说明改为 `through V78`。
- `docs/contracts/permission-catalog.json`：`ai:report:preview` 登记进 `unusedCatalog`
  （菜单级权限：只用于菜单可见性，接口鉴权走应用端票据，与 `ai:open-platform:query` 同一口径）。

### 测试

- `packages/ai-chat-ui/src/report/__tests__/report-view.test.ts`（8）
- `apps/web-ele/src/api/ai/report/index.test.ts`（3）
- `apps/web-ele/src/views/ai/report/data.test.ts`（4）
- `apps/web-ele/src/views/ai/report/ReportPreview.test.ts`（6）
- `apps/web-ele/src/views/ai/report/index.test.ts`（7）

## 2. 与卡片逐步实施的对应

| 卡步骤 | 实现 | 验证 |
|---|---|---|
| 1. 用 Vue 组件渲染 ReportSpec，支持指标/文字/表格/图表 | `AiReportView` 按布局（行→列）渲染四类块：指标取绑定数据的真实取值（缺失显示 `—`，不补 0）、文字文本插值、表格只渲染声明的列、图表走 `AiChart`（`column` → `bar`） | `report-view.test.ts` 渲染四类块、表格列白名单（结果里多出来的列不进界面）、空数据/部分数据的提示 |
| 2. 显示来源/截至时间/完整性/失败状态 | 元信息条展示完整性结论（PARTIAL 明确写"不能当作完整统计"）、逐项来源（kind + 资源 + 版本 + 描述）、失败原因（刷新失败时含原因码与时间）；截至时间由服务端给出、界面只做格式化 | `report-view.test.ts`（完整性三态与来源）；页面测试（`report-as-of`、刷新失败文案含原因码与时间） |
| 3. 保存/修订/刷新与当前权限一致，不提供未实现分享按钮 | 页面用应用客户端凭据换**短期受限票据**后走 `/app-api`（与 O08 在线调试同一口径）；请求体不含归属与行范围（服务端会话 + 授权层决定）；刷新失败保留旧结果并显示原因；修订的澄清追问原样展示；页面无分享/发布入口 | `api/ai/report/index.test.ts`（请求键集合不含归属/行范围字段）；页面测试（票据换取、预览、刷新失败、澄清、无分享按钮、卸载清空票据） |

### 卡片验收项

| 验收项 | 结论 | 证据 |
|---|---|---|
| AT-043/044 报表渲染与图表降级 | 通过（组件级） | `report-view.test.ts`：四类块、图表降级为点数据表格（缺失取值不补 0）、空类目提示 |
| AT-045/046 对话修改与新版本/并发冲突 | 通过（页面只展示服务端结果：APPLIED 的差异清单、CLARIFICATION 的追问与候选；409 冲突由请求失败如实提示） | 页面测试（澄清文案）；R05 的证据覆盖服务端行为 |
| AT-047 刷新失败保留旧结果并标时间/失败 | 通过（页面级） | 页面测试：刷新失败后显示"已保留上一次结果 + 原因码"，且预览仍显示上一次结果 |
| AT-048 保存后失权 | 通过（页面把 409 作为失败提示，不假装成功；服务端按当前 ACL 复核） | 页面 `refresh()` 的失败分支；R05/R06 证据覆盖服务端行为 |
| AT-049 旧 ReportSpec 加载 | 通过（组件级） | 版本切换读取指定版本；`safeParseReportSpec` 对不支持的 `schemaVersion` 拒绝渲染并给提示 |
| 深浅主题一致 | 通过（组件级） | `theme` 令牌驱动颜色并传给 `AiChart`；`ai-report--dark` 类断言 |
| 窄容器布局不溢出 | 通过（组件级） | 12 列 grid + `min-width: 0` + 表格 `overflow-x: auto`；测试断言栅格位置与滚动容器存在 |
| 恶意内容无执行 | 通过（组件级） | 标题/正文里的 HTML 只作为文本渲染（断言无 `<script`/`<img`、出现转义文本）；未知块类型与未知键在渲染前被拒绝 |

## 3. 关键约束落地

- **不新增依赖**：`apps/web-ele` 未声明 `@vben/ai-chat-ui` 依赖，本卡不修改依赖声明（门禁锁文件冻结），
  页面按源码相对路径引入共享包的**解析与视图模型函数**（`packages/ai-chat-ui/src/report/reportSpec.ts`，
  该文件零依赖），依赖图与锁文件保持原样。
- **生产 JS 门禁不被放宽**：页面不引入图表库（antv canvas 渲染器引用全局 `ImagePool` 会触发
  `no-undef`），改用零依赖 SVG 图表；门禁脚本与既有阈值均未改动。
- **身份与范围不进请求体**：页面只提交"改哪张报表、以哪一版为基础、改什么"；归属来自服务端会话，
  行范围来自授权层（数据类修改/刷新在缺少行范围时由服务端按稳定原因失败并留痕）。
- **票据只驻留内存**：`appSecret` 只在换取票据的请求里使用，`token` 只保存在组件状态中，
  不写 storage；组件卸载时清空票据与预览数据（测试断言卸载后需重新换取）。
- **渲染期拒绝未知结构**：`reportSpec.ts` 的键白名单与块判别联合保证"未知块类型/未知键"不会进入界面；
  图表无法表达缺失值时降级为表格（不补 0）。
- **无 TODO/假数据/空实现**：所有分支都有真实实现与测试；页面不放未实现能力的按钮。

## 4. 实际执行的命令与结果

工作目录：`/home/ctyun/桌面/zhongtai/ai-platform`（前端命令在 `前端代码/basic-framework-admin`）。

| 命令 | 结果 |
|---|---|
| `pnpm exec vitest run --dom packages/ai-chat-ui/src/report apps/web-ele/src/api/ai/report apps/web-ele/src/views/ai/report` | exit 0；`Tests 28 passed`（8 + 3 + 4 + 7 + 6） |
| `pnpm run check:type` | exit 0（37/37 任务通过，含 `@vben/ai-chat-ui` 与 `@vben/web-ele` 的 vue-tsc） |
| `pnpm exec eslint <本卡新增文件>` | exit 0 |
| `node scripts/check-permission-catalog.mjs` | exit 0：`接口引用 128 个权限码，目录 129 个，不可授予登记 4 条，保留登记 5 条` |
| `sh .harness/verify.sh contracts` | exit 0（字段/生命周期/数据权限/权限目录/门禁接线全部通过） |
| `sh .harness/verify.sh frontend` | exit 0：`check`（含 vue-tsc 37/37）+ `lint`（prettier/eslint/stylelint/cspell）+ `test:coverage`（`Tests 334 files passed`，行覆盖 90.66% ≥ 阈值 81%）+ 生产构建 + `Production JavaScript: 228 files passed no-undef validation` + 棘轮（首次为"7 个新文件尚未登记"的预期状态，`--update` 后通过） |
| `sh .harness/verify.sh backend` | exit 0 |
| `sh .harness/verify.sh integration` | exit 1：`Tests run: 221, Failures: 0, Errors: 1`，唯一错误是**既有** `UserProfilePersistenceIT`（全量套件下确定性锁等待，非本卡引入；迁移 V78 由真实 MySQL/Flyway 链路验证通过） |
| `./mvnw -o -pl basic-framework-server verify -Pintegration -Dtest=None -Dsurefire.failIfNoSpecifiedTests=false -Dit.test='UserProfilePersistenceIT' -Dfailsafe.failIfNoSpecifiedTests=false` | exit 0：`Tests run: 8, Failures: 0, Errors: 0`（单独运行 8/8 通过，未被跳过或排除） |
| `./mvnw -q -o -Pintegration -pl basic-framework-coverage -am verify -DskipTests` | exit 0（重生成聚合覆盖率报告） |
| `node scripts/check-coverage-ratchet.mjs --update` / `all` | exit 0：登记 7 个新前端文件基线（`reportSpec.ts` 87.05%、`index.vue` 92.42%、`ReportPreview.vue` 95.81%、`data.ts` 96.38%、`AiReportView.vue` 96.62%、`ReportChartSvg.vue`/`index.ts` 100%）；`all 单文件基线通过` |

## 5. 新增/变化的对外契约

- **菜单与权限码**：`ai:report:preview`（菜单 4100「个人报表」，type=2，component `ai/report/index`）；
  该权限码只用于菜单可见性，接口鉴权走应用端票据（已登记进权限目录台账的 `unusedCatalog`）。
- **前端 API 绑定**：报表域应用端接口（R04/R05/R06 已交付的 `/app-api/ai/report/*`）在 `apps/web-ele` 侧的类型化客户端；
  不新增后端端点、不改后端契约。
- **字段/权限**：不新增数据库表与列；快照同步至 V78。

## 6. 未验证项与已知边界

1. **真实浏览器验收未完成**：卡片的"组件及真实浏览器验收"中，浏览器测试命令在 Q06 建立前**不存在**
   （任务卡明确"浏览器测试命令在 Q06 建立前不得声称已经存在"），因此本卡只交付组件级与页面级测试
   （happy-dom 挂载 + 真实 Element Plus/组件），跨源/渲染/权限的浏览器验收由 Q06 与 G5 补齐。
   深浅色、窄容器、恶意内容三项在本卡以组件级断言覆盖，最终仍需浏览器复验。
2. **数据类修订/刷新的行范围上下文**：与 R05/R06 同一平台前置条件（缺少"主体范围 → 行范围列"的解析器），
   页面会把服务端返回的稳定原因码如实展示（例如 `1_003_007_018`），不伪造成功。
3. **预览数据来源**：可刷新版本的版本行不带数据（R04/R06 口径），页面从刷新状态接口取"上一次成功结果"；
   从未刷新过的可刷新报表在页面上显示"无数据行/—"（不显示编造内容）。
4. **环境缺口（非本卡）**：`sh .harness/verify.sh dependencies` 因 Trivy DB 镜像不可达仍失败（见前序卡记录）。

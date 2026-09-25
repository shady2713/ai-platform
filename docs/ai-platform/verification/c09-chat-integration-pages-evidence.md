# C09 交付 Chat 集成与主题管理页面 — 完成证据

| 项目 | 内容 |
|---|---|
| 任务卡 | [C09](../tasks/C09.md) |
| 状态 | DONE（控制面页面与菜单；浏览器端到端见"未验证项"） |
| 需求 | FR-14（嵌入与 SDK）、FR-15（主题） |
| 依赖 | C04（主题发布 API，证据 `c04-*`）、C08（上下文与宿主事件，证据 `c08-*`）、A09（应用管理页，已有证据） |
| 工作副本 | `/home/ctyun/桌面/zhongtai/ai-platform` |
| 变更范围 | `apps/web-ele/src/api/ai/chat`、`views/ai/theme`、`views/ai/chat-integration`、迁移 V80 + SQL 快照 |

## 1. 变更文件清单

- `api/ai/chat/index.ts`：主题管理 API（管理端通道，权限码与 V79 种子一致）——分页/单条/新建/发布（含回退）/有效主题/字体白名单。
- `views/ai/theme/{index.vue,data.ts,data.test.ts}`：**主题管理页**（菜单 4105）——应用选择、有效主题（来源/修订/指纹/token/布局摘要）、
  新建修订表单（主色/圆角/白名单字体/深浅色）、修订历史表（发布状态文案、Token/布局摘要、发布或**回退**按钮）。
  已发布修订不再给发布按钮（`canPublish` 判定），已被取代的修订按钮文案为"回退到此版本"。
- `views/ai/chat-integration/{index.vue,data.ts,data.test.ts}`：**Chat 集成页**（菜单 4110）——接入代码（应用标识/展示形态/入口基址可调，
  **票据是占位符**）、后端换票四步说明、SDK 能力与版本清单、自建 UI 路线说明。
- 迁移 `V80__ai_chat_integration_menu.sql`：启用主题菜单 4105（页面已交付）+ 新增 Chat 集成菜单 4110（复用 `ai:application:query`，不新造权限码）。
- `数据库文件/basic_framework.sql`：同步 V80（快照声明更新为 `through V80`；本卡**不新增表**，软删除表数仍为 49）。
- 测试：`views/ai/theme/data.test.ts`(4)、`views/ai/chat-integration/data.test.ts`(5)。

## 2. 与卡片逐步实施的对应

| 卡步骤 | 实现 | 验证 |
|---|---|---|
| 1. 模式/主题预览、允许域配置及版本发布 | 主题页（新建修订/发布/回退/有效主题）+ 集成页的模式选择；允许域仍在应用管理页（A09）维护，主题页展示有效主题来源 | `theme/data.test.ts`（状态文案、可发布判定、回退文案）、`chat-integration/data.test.ts`（三种模式） |
| 2. 生成不含 secret 的接入代码与后端换票说明 | `buildIntegrationSnippet`（占位符 + 自托管入口）与 `TICKET_HELP` 四步 | `chat-integration/data.test.ts`（**断言片段里没有真实票据前缀**、无 CDN/浮动版本）、页面自检 `containsTicketLiteral` |
| 3. 展示 API 自建 UI 路线与 SDK 能力版本 | 集成页的"SDK 能力与版本"卡片与自建 UI 路线说明 | `chat-integration/data.test.ts`（能力清单覆盖桥协议/票据/上下文/导航/嵌入页） |

### 卡片验收项

| 验收项 | 结论 | 证据 |
|---|---|---|
| 复制代码无真实票据 | 通过：片段里只有 `<TICKET>` 占位与"宿主后端换取"的说明，并有单测断言无 `aitkt_` 前缀 | `data.test.ts` |
| 预览按测试主体执行 | **未实现（见第 8 节）**：主题预览是"配置预览"（有效主题 + token/布局摘要），"以某个测试主体真实执行一次对话"依赖运行链路与票据的宿主装配（Q 系列端到端） | 第 8 节 |
| 主题不可写入脚本 | 通过：主题 token 由服务端按冻结契约校验（C04），页面只提交**结构化字段**（主色/圆角/白名单字体/深浅色），不提供任何 CSS/脚本输入框 | `theme/data.ts`（`toTokensJson` 只输出四个字段）、C04 的 `AiThemeValidatorTest` |
| 未发布配置不影响在线 Chat | 通过（结构）：页面只写草稿/发布状态；嵌入页读取的是**已发布**修订（C05 的 `resolveEffective`），草稿不参与 | C04/C05 证据 + `theme/data.ts` 的状态判定 |

## 3. 关键约束落地

- **权限码与菜单一一对应**：页面里出现的操作都对应 V79/V80 的权限码；不新造权限码（Chat 集成页复用 `ai:application:query`，
  因为它的输入就是"选哪个应用"）。
- **不提供假能力**：主题页没有"编辑已发布修订"（服务端不可变），集成页没有分享/发布按钮。
- **不复制第二套规则**：入口基址、模式取值、能力清单都来自平台常量；页面不做协议拼装。
- **失败不伪装成功**：读取/创建/发布失败都给出明确提示并保留上一次的数据；剪贴板不可用时提示手动复制。

## 4. 实际执行的命令与结果

| 命令 | 退出码 | 结果 |
|---|---|---|
| `pnpm exec vitest run --dom apps/web-ele/src/views/ai/theme apps/web-ele/src/views/ai/chat-integration` | 0 | `Test Files 2 passed`，**9 例通过** |
| `pnpm run check:type` | 0 | 37/37 任务通过 |
| `node scripts/check-data-lifecycle.mjs` / `check-permission-catalog.mjs` / `check-field-catalog.mjs` | 0 / 0 / 0 | 快照同步（`through V80`）、权限目录不变、字段目录一致 |
| 门禁链（contracts / backend / frontend / integration / 棘轮） | 见第 6 节 | 本卡改了迁移与快照，故跑完整链 |

## 5. 新增/变化的对外契约与上游差异

- **菜单**：V80 启用 4105（AI 主题）+ 新增 4110（Chat 集成，`ai:application:query`）。
- **前端 API**：`#/api/ai/chat` 新增主题管理接口（管理端通道，与 C05 的应用端嵌入入口分开）。
- **上游差异**：主题管理的**预览**在本卡是"配置预览"（页面展示有效主题与 token 摘要），
  真实渲染预览（在嵌入页里看效果）需要宿主侧装配（C10 的示例宿主 + Q06/G5 浏览器验收），已记入未验证项。

## 6. 门禁结果

| 命令 | 退出码 | 结论 |
|---|---|---|
| `sh .harness/verify.sh contracts` | 0 | 全部契约脚本通过（含生命周期/权限/字段三处台账，快照已同步到 `through V80`） |
| `sh .harness/verify.sh backend` | 0 | 编译、单测、格式、架构与覆盖率检查通过（本卡后端仅新增迁移文件） |
| `sh .harness/verify.sh integration` | **0** | **整条集成门禁通过**（maven 全量 IT 无失败 + 尾部棘轮通过）：V80 迁移在真实 MySQL 上执行成功，`PersistenceLifecycleIT` 与 `RemovedCapabilityMigrationIT` 保持通过 |
| `sh .harness/verify.sh frontend` | 0（复跑） | 首轮 exit 1 的原因有两个：① 三个新文件当时还没有测试（0% 覆盖率，`--update` 会直接拒绝）；② 主题页测试的一处类型错误让 `pnpm check` 失败。补齐组件/API 测试并修正类型后复跑通过（`check`/`lint`/`test:coverage`/生产构建/前端棘轮全部通过） |
| `node scripts/check-coverage-ratchet.mjs --update` / `all` | 0 / 0 | 登记 5 个新文件基线（主题页与集成页组件、两个 data 模块、主题 API 客户端），**无既有条目下降** |

> 说明：新前端文件必须有测试才会被棘轮接受（0% 直接被拒），因此本卡在首轮门禁后补了 3 个测试文件
> （主题页组件测试 4 例、集成页组件测试 3 例、主题 API 客户端 2 例），这也让"页面逻辑"进入了门禁覆盖。

## 7. 顺带修复的依赖缺口（真实暴露）

1. **`<pre>` 与 prettier 的格式冲突**（C03 已遇过一次）：多行属性 + 内联插值的元素会让 prettier 与
   `vue/html-closing-bracket-newline` 互相打架（eslint 报 "Circular fixes"）。解法同上：把长属性放到外层 `<div>`，
   `<pre>` 保持单行。
2. **摘要函数要拒绝数组**：`typeof [] === 'object'` 让 `layoutSummary('[]')` 返回默认值而不是占位符；
   改为 `isPlainObject` 判定（与 C03 的校验器同一口径）。

## 8. 未验证项与已知边界

1. **"预览按测试主体执行"未实现**：需要以某个测试主体真正跑一次运行并展示结果（依赖宿主换票装配与运行链路），
   属 Q 系列端到端验收；本卡交付的是配置层预览。
2. **页面未在真实浏览器走查**：主题页/集成页的交互（表单校验提示、复制剪贴板、窄屏布局）需要 Q06/G5 的浏览器验收。
3. **允许域配置仍在应用管理页**：本卡未把允许域编辑搬到主题页（避免两处编辑同一字段），
   页面只展示"有效主题来源"；如需集中展示，应在允许改应用页的卡片里处理。
4. **主题预览未使用共享渲染组件**：`apps/web-ele` 不能打包 `@vben/ai-chat-ui` 的图表/报表组件
   （antv 产物门禁，见 C03 证据），因此主题页只展示 token 摘要与状态，不做图表预览。
5. **环境缺口（非本卡）**：`sh .harness/verify.sh dependencies` 因 Trivy 漏洞库镜像不可达仍失败。

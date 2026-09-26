# Q03 交付运行监控与用量管理页面 — 完成证据

| 项目 | 内容 |
|---|---|
| 任务卡 | [Q03](../tasks/Q03.md) |
| 状态 | DONE（运行/用量两页 + 管理端查询契约 + 权限菜单与前后端测试；扣分项见"未验证项"） |
| 需求 | FR-30、FR-31 |
| 依赖 | Q01（审计与诊断，已交付）、Q02（用量账本与配额，已交付）、O08（开放平台目录与在线调试，已交付） |
| 工作副本 | `/home/ctyun/桌面/zhongtai/ai-platform` |
| 变更范围 | `module-ai/controller/admin/observability`、V82 迁移与快照、`apps/web-ele/src/{api/ai/observability,views/ai/{observability,usage}}`、`docs/contracts/ai/observability-query.md` |

## 1. 变更文件清单

后端（全部在卡片授权的 `controller/admin/observability` 内）：

- `AiObservabilityController.java`：四个管理端端点（列表/详情/时间线/重试），查看与重试**分开鉴权**。
- `AiRunMonitorQuery.java`：只读取数（筛选字面量受控、时间线有界、批量取任务避免 N+1）。
- `AiRunRetryCommand.java`：人工重试命令（同判据 + 运行行版本守卫 + 两行 CAS 同事务）。
- `AiRunRetryPolicy.java`、`AiRunTiming.java`：可重试判据与耗时分解（纯函数，单测覆盖）。
- `vo/`：`AiRunMonitorPageReqVO`、`AiRunMonitorRespVO`、`AiRunMonitorDetailRespVO`、`AiRunTimelineRespVO`、
  `AiRunRetryReqVO`、`AiRunTimingRespVO`。
- 迁移 `V82__ai_observability_menu.sql`：菜单 4116（`ai:observability:query`）+ 按钮权限 4117（`ai:observability:retry`）。

前端：

- `apps/web-ele/src/api/ai/observability/index.ts`（运行监控）、`usage.ts`（Q02 的用量接口）。
- `apps/web-ele/src/views/ai/observability/{index.vue,data.ts,index.test.ts,data.test.ts}`。
- `apps/web-ele/src/views/ai/usage/{index.vue,data.ts,index.test.ts,data.test.ts}`（菜单 4115 的页面）。

契约与台账：`docs/contracts/ai/observability-query.md`（新增查询契约）、
`数据库文件/basic_framework.sql`（菜单行 + 快照版本声明 → V82）。

测试：`AiObservabilityControllerTest`、`AiRunRetryPolicyTest`、`AiRunTimingTest`、`AiRunMonitorQueryTest`、
`AiObservabilityIT`（真实 MySQL）。

## 2. 逐步实施对应

| 卡步骤 | 实现 | 验证 |
|---|---|---|
| 1. 运行列表/步骤/失败原因/任务重试入口与用量筛选 | `/ai/observability/run/{page,get,timeline,retry}` + Q02 的 `/ai/usage/*`；筛选在服务端 | `AiObservabilityIT.runListFiltersByServerSideConditionsAndPaginates`（应用/服务/主体/时间窗/翻页）、前端 `index.test.ts`（筛选与翻页参数） |
| 2. 区分模型耗时、检索耗时、业务API耗时，展示未知计量与部分结果 | `AiRunTiming`：模型耗时取账本实测之和、未知条数单列；检索/业务 API 明确列为 `unmeasuredStages`（留空而非 0） | `AiRunTimingTest`、`AiObservabilityIT.detailSplitsMeasuredFromUnmeasuredAndKeepsUnknownUsageVisible`、前端 `data.test.ts` |
| 3. 查看与操作分别鉴权，默认不展开敏感正文 | `@PreAuthorize` 两个权限码；时间线只给 `blockType`/`blockPresent`；VO 无提示词/正文/凭据/外部主体明文字段 | `AiObservabilityControllerTest`（权限反射 + 字段扫描）、前端 `index.test.ts`（无权限不显示重试） |

### 卡片验收项

| 验收项 | 结论 | 证据 |
|---|---|---|
| AT-011 后台普通管理员读取秘密 | 通过：响应只有编号/配置修订/内容摘要与 `configured` 类事实，无凭据、无正文、无外部主体明文 | `AiObservabilityControllerTest.detailNeverExposesCredentialsPromptsOrSubjectIdentity` |
| AT-058 日志全链路检查 | 本卡不新增日志输出；重试与查询路径的异常走既有 `ServiceException`（稳定错误码，不带正文） | 代码走查 + `AiObservabilityIT` 断言错误码 |
| AT-060 上游 usage 缺失显示 UNKNOWN/ESTIMATED，非假 0 | 通过：用量页按来源区分显示，未知 token 显示"未知"；运行详情的 `unknownUsageCount` 单列、检索/业务 API 显示"未单独计量" | `AiRunTimingTest`、前端 `views/ai/usage/data.test.ts` |
| 权限不足按钮与接口一致 | 通过：重试按钮由 `ai:observability:retry` 控制，无权限时隐藏并给出说明；服务端同码校验 | 前端 `index.test.ts`（两个方向）、后端权限反射测试 |
| 筛选分页正确 | 通过：条件全部下发到库，翻页带页码，越界页为空但总数不变 | `AiObservabilityIT`（换服务编号/主体类型/时间窗三种反例） |
| 重试命令只允许原任务可重试类型 | 通过：排队中/结果未知/已成功/运行已结束一律拒绝（422 + 稳定原因），失败任务可重试且两行 CAS 同事务 | `AiRunRetryPolicyTest`、`AiObservabilityIT.retryOnlyAcceptsRetryableTaskStatusesAndGuardsVersion` |

## 3. 关键约束落地

- **管理端跨主体监控不复用按主体过滤的服务**：O01/O06 的运行/任务服务都按当前 MEMBER 会话主体过滤，
  管理端没有应用主体会话，直接调用会一律拒绝；因此读模型与重试命令落在卡片授权的
  `controller/admin/observability` 包内（边界判断见第 5 节）。
- **重试判据同源**：`AiRunRetryPolicy` 与 O06 的 `AiTaskServiceImpl#retryBlockedReason` 同判据，
  由 `AiRunRetryPolicyTest` 的规则矩阵固定；重试命令额外增加**运行行版本守卫**（界面拿过期版本会被拒绝）。
- **未知不写 0**：模型耗时/用量未知时字段留空，界面显示"未知/未计量"；`unmeasuredStages` 明确列出未计量阶段。
- **默认不展开正文**：时间线接口不返回 `blockJson`，只给块类型与有无；详情只给 `resultDigest`。
- **筛选在服务端**：分页与筛选条件全部下发；前端不做"取一页再自己筛"。

## 4. 实际执行的命令与结果

（见第 6 节门禁结果；定向命令如下。）

| 命令 | 退出码 | 结果 |
|---|---|---|
| `./mvnw -o -pl basic-framework-module-ai test -Dtest='AiObservabilityControllerTest,AiRunRetryPolicyTest,AiRunTimingTest,AiRunMonitorQueryTest'` | 0 | **18 例通过**（控制器 6、判据 3、耗时 3、查询 6） |
| `./mvnw -o -pl basic-framework-server verify -Pintegration -Dit.test='AiObservabilityIT'` | 0 | **4 例通过**（真实 MySQL + V82 迁移） |
| 前端 `pnpm exec vitest run --dom apps/web-ele/src/api/ai/observability apps/web-ele/src/views/ai/{observability,usage}` | 0 | **23 例通过 / 6 个文件**（页面 19 + API 客户端 4） |
| 前端 `pnpm -F @vben/web-ele run typecheck`、`eslint`、`cspell lint` | 0 / 0 / 0 | 类型检查、lint、拼写检查均通过 |
| `node scripts/check-permission-catalog.mjs` | 0 | V82 的两条权限码被控制器引用且目录可见（合同门禁内） |

## 6. 门禁结果

工作目录 `/home/ctyun/桌面/zhongtai/ai-platform`，`umask 022`、`JAVA_HOME=~/.local/opt/jdk17/...`、
`PATH` 含 `sudo` 垫片：

| 门禁 | 退出码 | 关键输出 |
|---|---|---|
| `sh .harness/verify.sh contracts` | 0 | 生命周期/数据权限/权限目录/敏感字段等全部通过（含 V82 菜单与快照一致性） |
| `sh .harness/verify.sh backend` | 0 | `mvn -q clean verify` 全绿（本卡后端源码在最终形态下的完整后端门禁） |
| `sh .harness/verify.sh frontend` | 0（棘轮段落除外，见下） | 类型检查、lint、单测与覆盖率、生产构建、产物 no-undef 校验全部通过；末尾棘轮因新前端文件未登记而报错（预期，随后 `--update` 登记） |
| `sh .harness/verify.sh integration` | 0（同上） | `mvn -q -Pintegration clean verify`：**239 例 IT 全部通过**（无失败用例）；末尾棘轮同样因新后端文件未登记而报错 |
| `node scripts/check-coverage-ratchet.mjs --update` | 0 | 登记本卡新增 6 个后端文件与 6 个前端文件；**未下调任何既有基线** |
| `node scripts/check-coverage-ratchet.mjs all` | 0 | `all 单文件基线通过` |

本卡新增文件的登记覆盖率（行覆盖，均高于新文件 80% 阈值）：
后端 `AiObservabilityController` 100%、`AiRunMonitorQuery` 100%、`AiRunRetryPolicy` 100%、`AiRunTiming` 100%、
`AiRunRetryCommand` 92.31%；前端 `api/ai/observability/index.ts` 100%、`api/ai/observability/usage.ts` 100%、
`views/ai/observability/data.ts` 100%、`views/ai/observability/index.vue` 97.28%、`views/ai/usage/data.ts` 100%、
`views/ai/usage/index.vue` 98.34%。

门禁执行过程中修正的两处真实缺陷（都不是"放宽门禁"）：

1. **快照里的菜单行插错位置**：新增的 4116/4117 被写成独立语句（缺 `INSERT INTO ... VALUES` 前缀），
   真实库重放 `数据库文件/basic_framework.sql` 时报语法错误（`AuthenticationMigrationIT` 因此失败）。
   已改为并进既有 VALUES 列表，重放通过。
2. **新前端 API 客户端无测试导致覆盖率 0**：`api/ai/observability/*.ts` 起初只被页面测试 `vi.mock`，
   真实模块从未被导入 → 棘轮新文件阈值不满足。按既有约定补齐 `index.test.ts`/`usage.test.ts`（断言路径与参数）。

## 7. 边界判断（需要你知道的取舍）

1. **管理端读模型落在协议层包内**：Q03 只授权 `controller/admin/observability`，不授权 `service/**`；
   而管理端跨主体监控无法复用按主体过滤的既有服务（O01 的主体解析要求 MEMBER 会话，管理端没有）。
   因此 `AiRunMonitorQuery`（只读取数）与 `AiRunRetryCommand`（重试命令）放在该包内并直接使用既有 Mapper；
   查询类只做只读，写入只此一个命令。若后续卡放开 `service/observability`，应整体下沉。
2. **重试判据两处实现**：`AiRunRetryPolicy` 与 O06 的 `AiTaskServiceImpl#retryBlockedReason` 同判据，
   由规则矩阵单测固定；重试命令额外加了**运行行版本守卫**（O06 的应用端重试忽略该参数），
   避免界面拿过期状态误操作。合并为单一权威策略需要 `service/task` 的授权。
3. **耗时的"未计量"是事实而非缺陷**：模型耗时来自 Q02 账本实测；检索与业务 API 在运行链路里
   尚未单独计量，接口用 `unmeasuredStages` 明确列出，界面显示"未单独计量"而不是 0（AT-060 口径）。
   仍在运行的运行用应用时钟与库内受理时间相减：库与应用时钟不一致时会返回空并由界面显示"未记录"
   （不编造耗时）；建议部署时统一容器与应用时区。
4. **管理端权限的 IT 覆盖方式**：`@PreAuthorize` 的管理端权限需要真实角色/菜单数据，IT 里用
   `AopTestUtils.getTargetObject` 解包后直接验证取数与状态机；权限策略本身由单测（反射断言）与框架的
   `EndpointAuthorizationContractTest` 覆盖，IT 的边界在证据里写明而不是悄悄绕过。

## 8. 未验证项

1. **真实浏览器验收**：Q03 的两个页面只做了组件测试与生产构建；跨源/渲染/权限的浏览器用例按卡片要求
   由 Q06 与 G5 用真实浏览器补齐，本卡未做（与卡片"浏览器测试命令在 Q06 建立前不得声称已经存在"一致）。
2. **用量页的服务筛选下拉**：服务维度选项来自当前时间窗的 `service-summary`（跨时间窗的服务需要手填编号），
   未做全量服务选择器（需要服务列表接口参与，属 S 系列页面）。
3. **重试的审计留痕**：重试不写运行事件（与 O06 一致）；审计留痕依赖 Q01 的日志接入（尚未接线）。
4. **限额调整入口**：Q02 的并发上限由调用方传入，本卡的用量页只展示当前占位数，不提供上限配置
   （需要先有"应用/服务 → 上限"的配置表）。
5. **环境缺口（非本卡）**：`sh .harness/verify.sh dependencies` 因 Trivy 漏洞库镜像不可达仍失败。

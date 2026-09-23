# R05 实现报表对话修改 — 完成证据

| 项目 | 内容 |
|---|---|
| 任务卡 | [R05](../tasks/R05.md) |
| 状态 | DONE（本文件记录的命令均已实际执行） |
| 需求 | FR-28 |
| 依赖 | R04（报表保存与版本存储）、D05（查询计划生成）、D06（SQL 编译执行）、R01（ReportSpec 校验与绑定）均已有证据文档 |
| 工作副本 | `/home/ctyun/桌面/zhongtai/ai-platform` |

## 1. 变更文件清单

### 对话修改（`basic-framework-module-ai`，`service/report/revision`）

- `AiReportRevisionOp.java`：修订操作白名单（展示类 12 个 / 数据类 4 个）+ 每类操作的必填参数校验。
- `AiReportRevisionPlan.java`：模型输出契约 `{"schemaVersion":"1.0","operations":[...]}`（键白名单、操作数上限 20、
  未知操作码拒绝），`requiresQuery()` 是"要不要重新查库"的**服务端唯一结论**。
- `AiReportRevisionDiff.java`：基础版本与候选版本的**结构性差异**（标题/主题/布局/块增删改/数据集引用增删）。
- `AiReportSpecPatcher.java`：确定性落结构——展示类操作只改已存在的块（类型不符即拒绝）；
  数据类操作只用受控查询结果（数据集引用 + 查询引用 + 来源三件套），新增口径**新建引用**（不把已有块换到新数据上），
  改粒度/加过滤**原地替换引用**（块绑定不变）。
- `AiReportSpecPatch.java`、`AiReportRevisionData.java`：补丁产物与版本数据读写口径
  （`kind/title/specJson/datasets/data/sources/notes`；`datasets` 存结果行，使展示类修订可**不查库**重新绑定）。
- `AiReportRevisionModel.java`（端口）、`AiReportRevisionService.java`（接口）、`AiReportRevisionServiceImpl.java`（编排）。
- `AiReportRevisionConfiguration.java`：补丁器装配（纯逻辑不依赖 Spring，便于单测直接构造）。
- `service/report/revision/dto/AiReportRevisionRequestDTO.java`、`AiReportRevisionResultDTO.java`。

### 受控查询执行（`service/run`）

- `AiRunQueryExecutionService.java` + `AiRunQueryExecutionServiceImpl.java`：D05 计划 → 版本可执行性复核 →
  **执行前再校验**（规范化计划还原成模型形态后重判，并用计划哈希证明还原等价）→ D06 编译 → D03 只读执行 → 结果投影。
- `service/run/dto/AiRunQueryExecutionRequestDTO.java`、`AiRunQueryExecutionResultDTO.java`
  （结果行不进 `toString()`；澄清作为正常结果返回，不执行）。

### 协议层（应用端）

- `controller/app/v1/report/AiReportController.java`：新增 `POST /ai/report/revise`（`@AuthenticatedOnly`），
  端点总数 6 → 7，每个端点仍有且仅有一条授权策略。
- `controller/app/v1/report/vo/AiReportReviseReqVO.java`、`AiReportRevisionRespVO.java`
  （请求体无归属、无行范围、无允许数据集；响应无范围指纹与结果行）。

### 契约台账（错误码两侧同步 + 端点登记）

- `docs/contracts/ai/error-code-map.md`：新增 `1_003_007_013`–`016`（与 `AiErrorCodeConstants` 两侧一致）。
- `docs/contracts/ai/scope-catalog.md`：登记 `AiReportController#revise` 的归属与范围判定口径。
- `basic-framework-server/src/test/java/com/basicframework/server/AiAppEndpointScopeContractTest.java`：
  `REVIEWED_AUTHENTICATED_ENDPOINTS` 登记 `#revise`。
- `docs/integrations/open-api/ai-open-api.json`：开放端点新增 `/app-api/ai/report/revise`（含 409 语义说明）。

### 测试

- 单测（`basic-framework-module-ai`）：`AiReportRevisionPlanTest`(9)、`AiReportSpecPatcherTest`(11)、
  `AiReportRevisionServiceImplTest`(11)、`AiReportRevisionEvaluationTest`(6)、`AiRunQueryExecutionServiceImplTest`(8)，
  并扩展 `AiReportControllerTest`(6)。
- 集成测试（`basic-framework-server`）：`AiReportRevisionIT`(5，真实 MySQL 8.4 + 真实 D03/D04/D05/D06 链路)。
- 夹具：`basic-framework-module-ai/src/test/.../testfixture/AiReportRevisionFixture`（基础版本规格/数据/修订计划/受控查询结果）。

**本卡不新增迁移、不新增表/列**，因此四处台账（`basic_framework.sql`、`data-lifecycle.json`、
`data-permission-exemptions.json`、`PersistenceLifecycleIT`）无需改动；也不新增管理端菜单与权限码
（报表是应用端私人数据，管理端无跨主体入口）。

## 2. 与卡片逐步实施的对应

| 卡步骤 | 实现 | 验证 |
|---|---|---|
| 1. 区分仅展示修改与需重新查询的修改 | 操作码分类由服务端判定（模型不能自己声明"这次不用查库"）：展示类 12 个操作码只引用已存在的块与结果列；数据类 4 个（新增指标/改粒度/加过滤/换数据集）必然触发受控查询 | `AiReportRevisionPlanTest#presentationPlanDoesNotRequireQuery`、`#dataPlanRequiresQueryAndCarriesDatasetId`；`AiReportRevisionEvaluationTest#evaluationCasesMatchExpectedClassificationAndQueryBehaviour`（展示类 4 条指令受控查询调用次数 0，数据类 2 条各调用 1 次） |
| 2. 以 baseVersion 生成候选 Spec，验证来源和权限 | 基础版本经 R04 `getVersion`（归属 + 范围指纹复核）；数据类操作先做 A03 `DATASET` READ 判定**再**查询；候选规格过 R01 结构 + 语义校验，并与**真实结果**绑定（列/行数/完整性不符即拒绝）；行范围只能来自授权层 | `AiReportRevisionServiceImplTest#dataRevisionRefusesDatasetWithoutCurrentGrant`（授权判定在前，未发起查询）、`#projectionChangeWithoutStoredRowsIsRefused`；IT `dataRevisionExecutesControlledQueryWithRowScopeAndKeepsHistory`（行范围真的拼进 WHERE：只返回 C001 的 290.00） |
| 3. 成功创建新版本，原版保留 | 只调用 R04 的"新增版本"入口（乐观锁 + 版本号唯一索引兜底）；本类不存在"更新版本内容"的方法 | IT `presentationRevisionReusesStoredRowsWithoutQuerying`（第 2 版 chartType=line，第 1 版 `spec_json`/`data_json` 逐字节不变）、`concurrentRevisionIsRejectedByOptimisticLock`（409 + 版本数不变） |

### 卡片验收项

| 验收项 | 结论 | 证据 |
|---|---|---|
| AT-045 报表对话修改 → 新版本，原版本可追溯 | 通过（服务层与 IT） | IT 两个用例：新版本号 = 基础 + 1，`listVersions` 2 条，第 1 版内容不变 |
| AT-046 并发修改 → 409，数据不覆盖 | 通过 | IT `concurrentRevisionIsRejectedByOptimisticLock`；`AiReportRevisionServiceImplTest#concurrentModificationBubblesUpAsConflict` |
| 换图不重复查库 | 通过 | `AiReportRevisionEvaluationTest`（展示类 4 条指令 `verify(queryExecutionService, never())`）；IT 在**断开数据源**（关闭连接池 + 删除只读账号 + 停用数据集）后展示类修订仍成功 |
| 新增指标需要受控查询 | 通过 | IT `dataRevisionExecutesControlledQueryWithRowScopeAndKeepsHistory`（真实 MySQL 查询结果落进新版本）；`AiReportSpecPatcherTest#addMetricRejectsEmptyResultInsteadOfFabricatingNumbers`（结果为空即拒绝，不编造数字） |
| 原版本不被改写 | 通过 | IT 断言第 1 版 `spec_json`/`data_json` 与创建时一致；`AiReportRevisionEvaluationTest#evaluationKeepsOriginalVersionUntouchedAndAlwaysAppendsVersion` |

## 3. 关键约束落地

- **分类不可被模型绕过**：`requiresQuery()` 只看操作码；未知操作码、类型不符的块操作、缺参数一律拒绝
  （`AI_REPORT_REVISION_PLAN_INVALID`），不存在"跳过该操作继续改别的"。
- **受控查询四道门**：① 数据集 READ 的 A03 判定（在查询之前）；② 规划器按授权目录产出已校验计划（澄清不猜）；
  ③ 版本必须启用 + 已发布 + 已验证 + 未漂移；④ 执行前把规范化计划还原成模型形态**再校验一次**，
  并用计划哈希证明还原等价（不一致即 `AI_QUERY_PLAN_INVALID`，绝不执行"另一份计划"）。
- **行范围只能来自授权层**：`QueryScope` 是服务层 DTO 字段，HTTP 请求体没有它；空范围直接拒绝（`AI_QUERY_SCOPE_REQUIRED`），
  不退回全库。应用端直连的数据类修订因此在缺少行范围上下文时**失败而不是查全库**（见"未验证项"）。
- **数据来源不可编造**：新增指标块绑定受控查询结果的数字列（列不存在即拒绝、无数据即拒绝）；
  数据集引用/查询引用/来源全部来自真实执行结果，模型给不出数字。
- **版本不可变**：只新增版本；`AiReportRevisionData.reuse` 会丢弃被删块的数据，不残留孤儿数据。
- **依赖清单随修订扩展**：新增数据集写入 `sourcesJson`，保存时逐项 A03 再鉴权（失权后拒绝展示，AT-048 口径不变）。
- **日志与响应无凭据/正文**：受控查询结果 `toString()` 排除行数据；修订响应只含结构差异与澄清候选；
  目录（给模型的输入）只含块/列元信息与授权数据集编号，不含数据行。
- **无 TODO/假数据/空实现**：所有分支都有真实实现与测试；无注释掉的测试。
- **上游契约差异**：R04 的 `data_json` 为不透明字符串，本卡首次固化其形状
  （`kind/title/specJson/datasets/data/sources/notes`，与 R03 结果块同形并多一份结果行）；
  R07 渲染按此形状，若要对外契约化由 R07/Q 系列补 schema。

## 4. 实际执行的命令与结果

工作目录：`/home/ctyun/桌面/zhongtai/ai-platform`（后端命令在 `后端代码/basic-framework-boot`）。
Maven 统一前缀：`umask 022 && export JAVA_HOME=$HOME/.local/opt/jdk17/usr/lib/jvm/java-17-openjdk-amd64`。

| 命令 | 结果 |
|---|---|
| `./mvnw -q -o -pl basic-framework-module-ai spotless:apply` | exit 0 |
| `./mvnw -o -pl basic-framework-module-ai test -Dtest='AiReportRevisionPlanTest,AiReportSpecPatcherTest,AiReportRevisionServiceImplTest,AiReportRevisionEvaluationTest,AiRunQueryExecutionServiceImplTest,AiReportControllerTest' -DfailIfNoSpecifiedTests=false` | exit 0；`Tests run: 52, Failures: 0, Errors: 0, Skipped: 0` |
| `./mvnw -o -pl basic-framework-module-ai test` | exit 0；`Tests run: 689, Failures: 0, Errors: 0, Skipped: 0`（模块全量） |
| `./mvnw -q -o -pl basic-framework-server -am -DskipTests -Djacoco.skip=true install` | exit 0（`AiOpenApiContractTest` 读已安装 jar，控制器变更后必须重装） |
| `./mvnw -o -pl basic-framework-server test -Dtest='AiOpenApiContractTest,AiAppEndpointScopeContractTest' -DfailIfNoSpecifiedTests=false` | exit 0；`Tests run: 10, Failures: 0, Errors: 0`（开放 API 双向一致 + 应用端端点登记） |
| `./mvnw -o -pl basic-framework-server test -Dtest='AiReportRevisionIT' -DfailIfNoSpecifiedTests=false` | exit 0；`Tests run: 5, Failures: 0, Errors: 0, Skipped: 0`；真实 MySQL 8.4.11 Testcontainers + 真实只读账号 |
| `sh .harness/verify.sh contracts` | exit 0（生命周期 74 张表/47 逻辑删除列/57 外键、数据权限分类、字段目录、权限目录、门禁接线均通过） |

### 门禁链

| 门禁 | 结果 |
|---|---|
| `sh .harness/verify.sh contracts` | exit 0 |
| `sh .harness/verify.sh backend` | exit 0（编译、模块单测、格式、覆盖率、架构边界全通过） |
| `sh .harness/verify.sh frontend` | exit 0（本卡未改前端文件，工作树与门禁一致） |
| `sh .harness/verify.sh integration` | exit 1：`Tests run: 214, Failures: 0, Errors: 1`，唯一错误是**既有** `UserProfilePersistenceIT`（全量套件下确定性锁等待，非本卡引入，处理方式见下） |
| `./mvnw -o -pl basic-framework-server verify -Pintegration -Dtest=None -Dsurefire.failIfNoSpecifiedTests=false -Dit.test='UserProfilePersistenceIT' -Dfailsafe.failIfNoSpecifiedTests=false` | exit 0：`Tests run: 8, Failures: 0, Errors: 0`（单独运行 8/8 通过，未被跳过或排除） |
| `./mvnw -q -o -Pintegration -pl basic-framework-coverage -am verify -DskipTests` | exit 0（重生成聚合覆盖率报告） |
| `node scripts/check-coverage-ratchet.mjs backend` | 失败项仅为 11 个新文件"尚未登记单文件覆盖率基线"（新增文件的预期状态）；`AiReportController` 在补一条澄清分支用例后回到 100%，未降低既有基线 |
| `node scripts/check-coverage-ratchet.mjs --update` | exit 0：登记 11 个新文件基线（`AiReportSpecPatcher` 84.49%、`AiRunQueryExecutionServiceImpl` 88.95%、`AiReportRevisionOp` 92.98%、`AiReportRevisionData` 93.84%、`AiReportRevisionPlan` 94.44%、`AiReportRevisionDiff` 96.67%、`AiReportRevisionServiceImpl` 97.33%，配置类/记录类 100%；均高于新文件 80% 下限） |
| `node scripts/check-coverage-ratchet.mjs all` | exit 0：`all 单文件基线通过` |

## 5. 新增/变化的对外契约

- **API**：`POST /app-api/ai/report/revise`（应用端，仅要求登录）。请求：`id`、`baseVersionNo`、`version`（乐观锁）、
  `instruction`、`endpointId`、可选 `datasetId`/`datasetVersionId`/`createdByRun`。
  响应：`outcome`（`APPLIED`/`CLARIFICATION`）、`newVersionNo`、`queryPerformed`、`diff`、
  澄清追问与有限候选、`notes`。
- **错误码**（`AiErrorCodeConstants` 与 `error-code-map.md` 两侧同步）：

| 错误码 | 常量 | HTTP | 含义 |
|---|---|---|---|
| 1_003_007_013 | `AI_REPORT_REVISION_PLAN_INVALID` | 400 | 修订计划不合规（操作码白名单/必填参数/块引用） |
| 1_003_007_014 | `AI_REPORT_REVISION_UNSUPPORTED` | 400 | 修订操作无法按当前数据完成（如查询结果为空仍要新增指标） |
| 1_003_007_015 | `AI_REPORT_REVISION_QUERY_SCOPE_REQUIRED` | 409 | 数据类修订缺少行范围上下文（拒绝而非查全库） |
| 1_003_007_016 | `AI_REPORT_REVISION_MODEL_UNAVAILABLE` | 503 | 报表修订模型未装配（fail-closed） |

- **字段/权限**：本卡不新增表、列、菜单与权限码。
- **上游依赖差异**：数据类修订要求"数据集 READ 授权 + 授权层给出的行范围"同时具备；
  数据集资源键沿用 A03 词表 `dset_` + 数据集标识（与 D05 计划里的 `datasetId` 同形）。

## 6. 未验证项与已知边界

1. **应用端 HTTP 数据类修订受"行范围策略"平台前置条件限制**：本平台尚无"主体范围 → 行范围列"的解析器
   （架构 §5.2 把可执行行范围策略列为发布数据集给 USER 的前置条件），因此应用端直连的**数据类**修订会以
   `AI_REPORT_REVISION_QUERY_SCOPE_REQUIRED` 失败（fail-closed，不查全库、不编造）。
   受控查询链路本身已在服务层与真实 MySQL 集成测试中端到端验证（含行范围强制生效）；
   行范围解析器落地后，应用端数据类修订只需把授权层的 `QueryScope` 接进 `AiReportRevisionRequestDTO`，无需改动本卡其余逻辑。
2. **真实模型端到端未验证**：本机无可用模型端点，修订链路用固定夹具模型（`AiReportRevisionFixture` + 脚本化模型）
   与真实数据链路验证；`AiReportRevisionModel` 走 M05 编排的真实网络路径未在本卡验证。
   真实模型效果评测由 Q04/Q10 承接，确定性替身评测不冒充模型效果。
3. **`createdByRun` 归属链**：修订会把来源运行透传给 R04 保存（跨主体引用仍被拒绝），
   其归属判定由 R04 的 IT 与单测覆盖，本卡 IT 未重复构造运行行。
4. **可刷新（REFRESHABLE）报表的对话修改**：展示类修改不查库（候选版本同样不带数据，符合 R04 口径）；
   数据类修改的"按当前权限重新执行"由 R06 的刷新任务承接（本卡只保证新增版本与依赖清单正确）。
5. **环境缺口（非本卡）**：`sh .harness/verify.sh dependencies` 因 Trivy DB 镜像不可达仍失败（见前序卡记录）。

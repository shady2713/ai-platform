# R06 实现刷新任务与结果原子切换 — 完成证据

| 项目 | 内容 |
|---|---|
| 任务卡 | [R06](../tasks/R06.md) |
| 状态 | DONE（本文件记录的命令均已实际执行） |
| 需求 | FR-29 |
| 依赖 | R04（报表保存与版本存储）、O06（任务查询与清理）、D11（黄金集验收）、D05/D06（查询计划与编译执行）、R05（受控查询接缝）均已有证据文档 |
| 工作副本 | `/home/ctyun/桌面/zhongtai/ai-platform` |

## 1. 变更文件清单

### 数据库（仅新增迁移号）

- `basic-framework-server/src/main/resources/db/migration/V77__ai_report_refresh.sql`：新增
  `ai_report_refresh`（刷新尝试：状态/原因/尝试时间/完整性/绑定数据/逐项范围指纹 + 乐观锁），
  并注册刷新 Job（`infra_job` id 37，`aiReportRefreshJob`，`0 0/30 * * * ?`）。

### 服务与持久化（`basic-framework-module-ai`）

- `dal/dataobject/report/AiReportRefreshDO.java`、`dal/mysql/report/AiReportRefreshMapper.java`
  （最近一次尝试 / 最近一次成功 / **到期扫描** `selectDueReportIds`）。
- `service/report/refresh/AiReportRefreshService.java`（接口：`refresh`、`lastState`）+
  `AiReportRefreshServiceImpl.java`（来源可执行性复核 → 受控查询 → 候选规格与绑定 → 幂等判定 →
  CAS 推进生效版本 + 版本行 → 尝试留痕）。
- `service/report/refresh/dto/`：`AiReportRefreshRequestDTO`、`AiReportRefreshResultDTO`、`AiReportRefreshStateDTO`。
- `service/run/AiRunQueryExecutionService(+Impl)`：**扩展 R05 建立的受控查询接缝**，新增
  `executeFixed(...)`（执行**已固定的规范化计划**：不规划、不调模型；仍要求授权层给出的行范围）+
  `AiRunQueryExecutionFixedRequestDTO`。既有 `execute` 语义未改动。
- `job/AiReportRefreshJob.java`：按节奏扫描到期报表逐张刷新，单张失败不中断其余报表。

### 协议层（应用端）

- `controller/app/v1/report/AiReportController.java`：新增 `POST /ai/report/refresh`、
  `GET /ai/report/refresh/last`（端点总数 7 → 9，每个端点仍有且仅有一条授权策略 `@AuthenticatedOnly`）。
- `controller/app/v1/report/vo/`：`AiReportRefreshReqVO`、`AiReportRefreshRespVO`、`AiReportRefreshStateRespVO`
  （请求体无归属与行范围；响应无范围指纹）。

### 契约台账（四处同步 + 端点登记 + 错误码两侧同步）

- `数据库文件/basic_framework.sql`：新增表定义与刷新 Job 行；快照说明改为 `through V77`，逻辑删除表 47 → 48。
- `docs/contracts/data-lifecycle.json`：`ai_report_refresh` 进软删除策略表；新增物理外键 `fk_ai_report_refresh_report`。
- `docs/contracts/data-permission-exemptions.json`：`ai_report_refresh` 同时出现在 `ai-report-private` 的
  `tables` 与 `subject-bound` 控制的 `tables`，并补两条逐表证据（Mapper 按报表过滤、服务层范围复核）。
- `PersistenceLifecycleIT`：期望表清单加入 `ai_report_refresh`；种子 Job 清单加入 `aiReportRefreshJob`。
- `RemovedCapabilityMigrationIT`：内置任务数 12 → 13。
- `docs/contracts/ai/error-code-map.md`：新增 `1_003_007_017`/`018`（与 `AiErrorCodeConstants` 两侧一致）。
- `docs/contracts/ai/scope-catalog.md`：登记 `AiReportController#refresh`、`#refreshLast`。
- `AiAppEndpointScopeContractTest.REVIEWED_AUTHENTICATED_ENDPOINTS`：登记两个新端点。
- `docs/integrations/open-api/ai-open-api.json`：开放端点新增 `/app-api/ai/report/refresh`、`/refresh/last`。
- `scripts/secret-scan.mjs`：登记本卡集成测试的一次性只读账号口令字面量（publicFixtures）。

### 测试

- 单测（`basic-framework-module-ai`）：`AiReportRefreshServiceImplTest`(20)、`AiReportRefreshJobTest`(3)，
  并扩展 `AiReportControllerTest`(8)。
- 集成测试（`basic-framework-server`）：`AiReportRefreshIT`(7，真实 MySQL 8.4 + 真实 D03/D04/D05/D06 链路)。

## 2. 与卡片逐步实施的对应

| 卡步骤 | 实现 | 验证 |
|---|---|---|
| 1. REFRESHABLE 按当前权限执行固定查询版本 | 计划来自版本里保存的**已校验规范化计划**（不重新规划、不调模型）；执行前再校验一次并用计划哈希证明等价；行范围来自授权层 | IT `refreshExecutesFixedPlanAtomicallyAndIsIdempotent`（真实 MySQL 查询结果落进版本与尝试记录，只返回 C001 的 290.00） |
| 2. 新结果成功后原子切 active，失败保留旧结果 | 全部查询与绑定成功后才 CAS 推进 `latest/published_version_no` 并插入版本行；任一步失败只留痕、不写版本 | 单测 `queryFailureIsRecordedAndOldResultStaysPublished`、`bindingMismatchIsRecordedInsteadOfWritingHalfBakedResult`、`concurrentModificationIsRecordedAsConflict`；IT `failedRefreshKeepsOldResultAndMarksTimeAndReason` |
| 3. schema 漂移/失权时停止并给稳定原因 | 数据集停用/版本未发布/未验证/漂移 → 留痕失败并停止（不继续执行、不跳过来源）；范围指纹不再覆盖 → 409 且刷新与读取都停止 | 单测 `disabledOrDriftedSourceStopsBeforeAnyQuery`、`datasetVersionNotPublishedStopsRefresh`、`revokedScopeStopsRefreshAndRefusesReadingOldResult`；IT 同名用例 |
| 4. 刷新先取当前范围并校验语义版本，再创建候选结果，CAS 成功才切 active | 顺序即安全语义：归属 → 范围复核 → 来源可执行性 → 受控查询 → 候选规格与绑定 → 幂等 → CAS + 版本行（同一事务） | IT `refreshExecutesFixedPlanAtomicallyAndIsIdempotent`（版本 2 生效、第 1 版不变）；单测 `refreshExecutesFixedPlanAndSwitchesPublishedVersionAtomically` |
| 5. 候选失败保留旧结果，但旧结果本身也必须满足当前权限 | 上一次成功结果存在尝试记录上，读取前逐项 `reauthorizeHistorical` 复核；失权即拒绝（不为"页面还能看"跳过再鉴权） | 单测/IT `revokedScopeStopsRefreshAndRefusesReadingOldResult`（刷新与 `lastState` 都 409） |

### 卡片验收项

| 验收项 | 结论 | 证据 |
|---|---|---|
| AT-047 报表刷新失败 → 保留旧结果并标时间/失败 | 通过 | IT `failedRefreshKeepsOldResultAndMarksTimeAndReason`（尝试记录含 `status=FAILED` + 稳定原因 + 时间；`lastState` 仍返回上一次成功的数据与版本号） |
| AT-048 保存后用户失权 → 快照和刷新均按当前 ACL 处理 | 通过 | IT `revokedScopeStopsRefreshAndRefusesReadingOldResult`；快照侧沿用 R04 的读取复核（未改动） |
| 重复刷新幂等 | 通过 | IT（第二次刷新 `UNCHANGED`，版本数不变）；单测 `unchangedUpstreamDataDoesNotCreateAnotherVersion`（`verify(insert, times(1))`） |
| 刷新过程中可读旧结果 | 通过 | 版本行与尝试记录都在刷新失败时保持不变；IT 断言失败后 `readCurrent` 与 `lastState` 都能读到旧结果 |
| 停用源后不继续 | 通过 | 单测/IT：停用数据集 → `AI_DATASET_DISABLED` 留痕失败，`verifyNoInteractions(queryExecutionService)` |
| 并发与失败保持测试（必须产出） | 通过 | 单测 `concurrentModificationIsRecordedAsConflict`（CAS 失败 → 冲突留痕、无新版本）；IT 覆盖失败保持与幂等 |

## 3. 关键约束落地

- **归属只认服务端事实**：有会话时按会话身份校验归属（越权与不存在同语义 404）；作业没有会话时以
  **报表自身的归属列**为主体，任何调用方都不能指定身份。
- **行范围只能来自授权层**：`QueryScope` 是服务层 DTO 字段，HTTP 请求体没有它；缺失时刷新按
  `AI_REPORT_REFRESH_SCOPE_REQUIRED` 留痕失败并保留旧结果（不查全库）。
- **原子切换是事务性的**：CAS 推进生效版本与版本行插入在同一事务；`(report_id, version_no)` 唯一索引兜底。
- **可刷新版本仍不存数据**：沿用 R04 口径（数据存在刷新尝试记录上），快照/可刷新的语义边界不混。
- **失败留痕不含异常正文**：只写稳定原因码（门禁 `check-safe-exception-handling` 要求，不读取 `getMessage()`）。
- **作业幂等且不制造失败风暴**：到期判定基于尝试记录时间（成功、未变化、失败都算"近期尝试"），
  同一报表按间隔节奏重试一次。
- **无 TODO/假数据/空实现**：所有分支都有真实实现与测试；无注释掉的测试。

## 4. 实际执行的命令与结果

工作目录：`/home/ctyun/桌面/zhongtai/ai-platform`（后端命令在 `后端代码/basic-framework-boot`）。
Maven 统一前缀：`umask 022 && export JAVA_HOME=$HOME/.local/opt/jdk17/usr/lib/jvm/java-17-openjdk-amd64`。

| 命令 | 结果 |
|---|---|
| `./mvnw -q -o -pl basic-framework-module-ai spotless:apply` | exit 0 |
| `./mvnw -o -pl basic-framework-module-ai test -Dtest='AiReportRefreshServiceImplTest,AiReportRefreshJobTest,AiReportControllerTest' -DfailIfNoSpecifiedTests=false` | exit 0；`Tests run: 31, Failures: 0, Errors: 0, Skipped: 0` |
| `./mvnw -q -o -pl basic-framework-server -am -DskipTests -Djacoco.skip=true install` | exit 0（开放 API 契约测试读已安装 jar） |
| `./mvnw -o -pl basic-framework-server test -Dtest='AiOpenApiContractTest,AiAppEndpointScopeContractTest' -DfailIfNoSpecifiedTests=false` | exit 0；`Tests run: 10, Failures: 0, Errors: 0` |
| `./mvnw -o -pl basic-framework-server test -Dtest='AiReportRefreshIT' -DfailIfNoSpecifiedTests=false` | exit 0；`Tests run: 7, Failures: 0, Errors: 0, Skipped: 0`（真实 MySQL 8.4.11 Testcontainers） |
| `node scripts/check-data-lifecycle.mjs` | exit 0：`最终表 75 张，策略登记 75 张，逻辑删除列 48 张，物理外键 58 条；快照同步` |
| `node scripts/check-data-permission.mjs` | exit 0：`应用表 64 张，运行时保护 2 张，显式豁免 62 张，平台托管 11 张` |

### 门禁链

| 门禁 | 结果 |
|---|---|
| `sh .harness/verify.sh contracts` | exit 0 |
| `sh .harness/verify.sh backend` | exit 0 |
| `sh .harness/verify.sh frontend` | exit 0（本卡未改前端文件） |
| `sh .harness/verify.sh integration`（首轮） | exit 1：`Tests run: 221, Failures: 1, Errors: 1`。失败项之一是**本卡遗漏的台账同步**（`PersistenceLifecycleIT` 的种子 Job 清单未加 `aiReportRefreshJob`），已补；错误项是既有 `UserProfilePersistenceIT`（全量套件下确定性锁等待） |
| `sh .harness/verify.sh integration`（补台账后重跑） | exit 1：`Tests run: 221, Failures: 0, Errors: 1`，唯一错误为既有 `UserProfilePersistenceIT`（非本卡引入，处理方式见下） |
| `./mvnw -o -pl basic-framework-server verify -Pintegration -Dtest=None -Dsurefire.failIfNoSpecifiedTests=false -Dit.test='UserProfilePersistenceIT' -Dfailsafe.failIfNoSpecifiedTests=false` | exit 0：`Tests run: 8, Failures: 0, Errors: 0`（单独运行 8/8 通过，未被跳过或排除） |
| `./mvnw -q -o -Pintegration -pl basic-framework-coverage -am verify -DskipTests` | exit 0（重生成聚合覆盖率报告） |
| `node scripts/check-coverage-ratchet.mjs backend` | 失败项仅为 3 个新文件"尚未登记单文件覆盖率基线"（新增文件的预期状态）；既有基线未被下调 |
| `node scripts/check-coverage-ratchet.mjs --update` | exit 0：登记 `AiReportRefreshServiceImpl` 99.5%、`AiReportRefreshJob` 100%、`AiReportRefreshMapper` 100%（均高于新文件 80% 下限；扩展的 `AiRunQueryExecutionServiceImpl` 为 89.12%，高于其 88.95% 的既有基线） |
| `node scripts/check-coverage-ratchet.mjs all` | exit 0：`all 单文件基线通过` |

## 5. 新增/变化的对外契约

- **API**：`POST /app-api/ai/report/refresh`（刷新；`status=OK/UNCHANGED/FAILED` 都是正常结果）、
  `GET /app-api/ai/report/refresh/last`（上次刷新状态与上一次结果，读取前按当前权限复核）。
- **错误码**（两侧同步）：

| 错误码 | 常量 | HTTP | 含义 |
|---|---|---|---|
| 1_003_007_017 | `AI_REPORT_REFRESH_NOT_SUPPORTED` | 400 | 该报表不支持刷新（仅可刷新报表） |
| 1_003_007_018 | `AI_REPORT_REFRESH_SCOPE_REQUIRED` | 409 | 刷新需要行范围上下文（留痕并保留旧结果） |

- **字段/权限**：本卡不新增菜单与权限码（应用端私人报表，管理端无跨主体入口）；新增表 1 张（`ai_report_refresh`）。
- **上游依赖差异**：本卡**扩展** R05 建立的 `AiRunQueryExecutionService`，新增"执行已固定计划"入口
  （`executeFixed`）——刷新链路不重新规划、不调用模型；既有 `execute`（规划式）语义未变。

## 6. 未验证项与已知边界

1. **行范围策略仍是平台前置条件**：本平台尚无"主体范围 → 行范围列"的解析器（架构 §5.2 的前置条件），
   因此**接口与作业**在缺少行范围上下文时，数据类刷新会按 `AI_REPORT_REFRESH_SCOPE_REQUIRED` 留痕失败
   （fail-closed：不查全库、不编造）。刷新链路本身已在服务层与真实 MySQL 集成测试中端到端验证
   （含行范围强制生效）；行范围解析器落地后，只需把授权层给出的 `QueryScope` 注入
   `AiReportRefreshRequestDTO`，本卡其余逻辑无需改动。
2. **真实模型无关**：刷新不调用模型（计划是创建时固定的），因此不存在模型不可复现的问题；
   版本里的计划由真实规划器（脚本化模型）产出。
3. **浏览器验收未在本卡**：刷新状态/失败提示的界面呈现属于 R07，且浏览器测试命令在 Q06 建立前不存在，
   本卡不声称已有浏览器验收。
4. **刷新调度节奏**：作业默认每 30 分钟扫描一次、单批 50 张、到期间隔 1800 秒（可通过
   `basic-framework.ai.report.refresh.*` 覆盖）；未做"按报表自定义间隔"（卡未要求，避免新增列）。
5. **环境缺口（非本卡）**：`sh .harness/verify.sh dependencies` 因 Trivy DB 镜像不可达仍失败（见前序卡记录）。

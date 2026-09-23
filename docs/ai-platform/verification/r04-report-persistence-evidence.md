# R04 实现报表保存与版本存储 — 完成证据

| 项目 | 内容 |
|---|---|
| 任务卡 | [R04](../tasks/R04.md) |
| 状态 | DONE（本文件记录的命令均已实际执行） |
| 需求 | FR-28 |
| 依赖 | R03（报表生成步骤）、A07（业务文件/资源授权 SPI）、F08（错误码区间） |
| 工作副本 | `/home/ctyun/桌面/zhongtai/ai-platform` |

## 1. 变更文件清单

### 数据库（仅新增迁移号）

- `后端代码/basic-framework-boot/basic-framework-server/src/main/resources/db/migration/V76__ai_report.sql`：新增
  `ai_report`（可刷新定义 + 私有归属 + 模式 + 乐观锁）与 `ai_report_version`（不可变版本：规格/快照数据/来源/逐项范围指纹/截至时间/完整性）。

### 服务与持久化（`basic-framework-module-ai`）

- `dal/dataobject/report/AiReportDO.java`、`dal/dataobject/report/AiReportVersionDO.java`
- `dal/mysql/report/AiReportMapper.java`（应用内按 code 定位、按归属分页、CAS 更新）、`dal/mysql/report/AiReportVersionMapper.java`
- `service/report/persistence/AiReportService.java`（接口：`create`/`saveVersion`/`getReport`/`getReportPage`/`getVersion`/`listVersions`/`readCurrent`）
- `service/report/persistence/AiReportServiceImpl.java`（归属判定、模式白名单、快照元信息、乐观锁、引用资源再鉴权、范围指纹复核）
- `service/report/persistence/AiReportScopeRef.java`、`service/report/persistence/AiReportScopeRefs.java`（依赖解析 + 逐项/整体指纹）
- `service/report/persistence/dto/AiReportSaveDTO.java`

### 协议层（应用端）

- `controller/app/v1/report/AiReportController.java`：`POST /ai/report/save`、`GET /ai/report/get`、`GET /ai/report/page`、
  `GET /ai/report/versions`、`GET /ai/report/current`、`GET /ai/report/version`（六个端点各**有且仅有一条**授权策略 `@AuthenticatedOnly`）
- `controller/app/v1/report/vo/AiReportSaveReqVO.java`、`AiReportRespVO.java`、`AiReportVersionRespVO.java`、
  `AiReportVersionBriefVO.java`、`AiReportPageReqVO.java`

### 契约台账（四处同步 + 端点登记）

- `docs/contracts/data-lifecycle.json`：`ai_report`、`ai_report_version` 登记为 soft-delete；新增物理外键 `fk_ai_report_application`、`fk_ai_report_version_report`
- `docs/contracts/data-permission-exemptions.json`：`ai-report-private`（`subject-bound`，逐表证据指向 Mapper 归属条件、服务层 `requireOwnedReport(`、`reauthorizeHistorical(` 与控制器 `@AuthenticatedOnly`）
- `docs/contracts/ai/error-code-map.md`：新增 `1_003_007_007`–`1_003_007_012`
- `docs/contracts/ai/scope-catalog.md`：登记 6 个"登录即可访问"端点及其归属/范围判定口径
- `docs/integrations/open-api/ai-open-api.json`：开放端点新增报表 6 条路径（含 409 语义说明）
- `数据库文件/basic_framework.sql`：表定义与 V76 同步（快照说明 `through V76`、逻辑删除表计数 47）
- `basic-framework-server/src/test/java/com/basicframework/server/integration/PersistenceLifecycleIT.java`：期望表清单加入两张新表
- `basic-framework-server/src/test/java/com/basicframework/server/AiAppEndpointScopeContractTest.java`：登记 6 个已审查端点

### 错误码（两侧同步）

`AiErrorCodeConstants` / `docs/contracts/ai/error-code-map.md`：

| 错误码 | 常量 | HTTP | 含义 |
|---|---|---|---|
| 1_003_007_007 | `AI_REPORT_NOT_FOUND` | 404 | 报表不存在（越权同语义，不借错误码枚举他人编号） |
| 1_003_007_008 | `AI_REPORT_VERSION_NOT_FOUND` | 404 | 版本不存在 |
| 1_003_007_009 | `AI_REPORT_CODE_DUPLICATE` | 409 | 同一应用内标识重复 |
| 1_003_007_010 | `AI_REPORT_SNAPSHOT_DATA_REQUIRED` | 400 | 快照模式缺数据 |
| 1_003_007_011 | `AI_REPORT_SCOPE_CHANGED` | 409 | 范围无法证明覆盖原范围，需重新生成 |
| 1_003_007_012 | `AI_REPORT_SOURCE_RUN_NOT_FOUND` | 404 | 来源运行不存在或不属于当前主体 |

## 2. 与任务卡步骤的对应

| 卡步骤 | 实现 | 验证 |
|---|---|---|
| 1. 建立 report/version 并支持 SNAPSHOT/REFRESHABLE | `V76__ai_report.sql` 两表 + `mode` 白名单校验；快照写 `as_of`，可刷新不写数据 | `AiReportPersistenceIT#refreshableModeStoresNoSnapshotDataAndRejectsModeChange`（列级断言 `data_json IS NULL`、`as_of IS NULL`）；`AiReportServiceImplTest#createRecordsSnapshotMetadataAndRejectsDeniedOrUnknownDependency` |
| 2. 保存来源/查询/服务/主题/Schema 版本及私有归属 | `sources_json`、`service_id`、`release_id`、`theme_id`/`theme_revision`、`schema_version` 全量落库；归属三列由**服务端会话**写入，请求体无归属字段 | IT 直接查库断言 `application_id/subject_type/external_user_id`；VO 无归属字段（协议层证据） |
| 3. 乐观锁防并发覆盖，引用资源再鉴权 | `ai_report.version` + CAS（失败 409）；`sources_json` 声明的每个依赖按 A03 `authorize(READ)` 判定，拒绝即拒绝保存；`created_by_run` 必须是本人的运行 | `AiReportServiceImplTest#saveVersionAppendsVersionAndRejectsStaleOptimisticLock`、`#createRecordsSnapshot..`（依赖被拒 403）；IT `newVersionAppendsAndHistoryStaysImmutable`（409 + 版本数不变）、`savingFromAnotherUsersRunIsRejected` |
| 4. 保存资源依赖与 scope 指纹，读取时不能覆盖则拒绝 | `scope_refs_json`（逐项指纹）+ `scope_fingerprint`（整体摘要）；读取（含旧版本编号）逐项 `reauthorizeHistorical` 比对，任一项不成立即 409 | IT `revokingGrantMakesSavedSnapshotUnreadable`（真实撤销授权后 `readCurrent` 与 `getVersion(1)` 都 409）；`AiReportServiceImplTest#readRefusesWhenScopeFingerprintChangedOrStorageInconsistent` |
| 5. 失权后必须按当前权限重新生成，不能用 reportId/旧 version/预览入口绕过 | 读取入口只有服务层 `readCurrent`/`getVersion`/`listVersions`，前两者都强制范围复核；旧版本编号同样复核；错误码文案明确"请在当前权限下重新生成报表" | IT 同上（两个入口）；`AiReportServiceImplTest#readChecksEveryVersionEntryNotOnlyCurrent` |

## 3. 关键约束落地

- **归属来自服务端会话**：`AiConversationSubjectResolver` 解析 MEMBER 会话（应用 + 主体类型 + 外部用户标识），请求体不提供归属字段；越权与不存在同语义（404），跨用户保存他人报表或他人运行都拒绝。
- **版本不可变是结构性的**：服务层不存在"更新版本内容"的方法；表上 `(report_id, version_no)` 在存活行内的唯一索引兜底（IT 用重复插入证明被拒）。
- **范围指纹不对外回显**：`AiReportVersionRespVO` 不含 `scopeFingerprint`，读取是否放行由服务端每次重新判定。
- **依赖只认 A03 词表**：`sourcesJson` 的 `resourceType` 必须是 `AiResourceType` 取值，未知类型 400（不静默跳过）；不新增第二套资源类型/权限模型。
- **快照元信息完整**：`as_of`（快照）+ `completeness` + `created_by_run` + `sources_json` 逐项可追溯。
- **无 TODO/假数据/空实现**：所有分支都有真实实现与测试；无注释掉的测试。
- **日志与响应不含凭据/模型输入正文**：响应只有规格、数据与来源引用（受控业务数据），无凭据、摘要或连接串字段（`AiOpenApiContractTest` 的公开文档红线同时守住这条）。

## 4. 实际执行的命令与结果

工作目录：`/home/ctyun/桌面/zhongtai/ai-platform`（后端命令在 `后端代码/basic-framework-boot`）。
Maven 统一前缀：`umask 022 && export JAVA_HOME=$HOME/.local/opt/jdk17/usr/lib/jvm/java-17-openjdk-amd64`。

| 命令 | 结果 |
|---|---|
| `./mvnw -q -o -pl basic-framework-module-ai spotless:apply` | exit 0 |
| `./mvnw -q -o -pl basic-framework-module-ai -DskipTests -Djacoco.skip=true compile` | exit 0 |
| `./mvnw -o -pl basic-framework-module-ai test -Dtest='AiReportScopeRefsTest,AiReportServiceImplTest' -DfailIfNoSpecifiedTests=false` | exit 0；`Tests run: 16, Failures: 0, Errors: 0, Skipped: 0`（`AiReportScopeRefsTest` 5 + `AiReportServiceImplTest` 11） |
| `./mvnw -q -o -pl basic-framework-server -am -DskipTests -Djacoco.skip=true install` | exit 0（`AiOpenApiContractTest` 读取已安装 jar，控制器变更后必须重装） |
| `./mvnw -o -pl basic-framework-server test -Dtest='AiReportPersistenceIT' -DfailIfNoSpecifiedTests=false` | exit 0；`Tests run: 5, Failures: 0, Errors: 0, Skipped: 0`；真实 MySQL 8.4.11 Testcontainers |
| `node scripts/check-data-lifecycle.mjs` | exit 0：`最终表 74 张，策略登记 74 张，逻辑删除列 47 张，物理外键 57 条；快照同步` |
| `node scripts/check-data-permission.mjs` | exit 0：`应用表 63 张，运行时保护 2 张，显式豁免 61 张，平台托管 11 张` |

## 5. 新增/变化的对外契约

- **API**：`/app-api/ai/report/{save,get,page,versions,current,version}`（应用端，仅要求登录；归属与范围由服务端判定）。
  `save` 不带 `id` 为新建（需 `code`），带 `id` 与 `version` 为保存新版本（乐观锁冲突 409；模式不可变更 400）。
- **字段**：`ai_report`（12 列 + 审计/逻辑删除）、`ai_report_version`（13 列 + 审计/逻辑删除）。
- **权限/菜单**：本卡**不新增**管理端权限码与菜单（报表是应用端私人数据，管理端无跨主体读取入口），
  因此 `permission-catalog.json` 无新增项。
- **上游依赖差异**：`sourcesJson` 的依赖项需要 `resourceType` + `resourceKey` 与 A03 授权目录中的 `resource_key` 一致；
  由数据集/知识库产生的报表应在生成侧（R05/R07 接线）把对应的资源键写入 `sourcesJson`。

## 6. 未验证项与已知边界

- **未验证：浏览器端报表页面**。R04 只交付服务端保存/读取；报表页渲染与"失权提示 + 重新生成"的界面引导在 R05/R07 与 Q06 真实浏览器验收中闭环。
  本卡不声称已存在浏览器测试命令。
- **未验证：真实模型生成的报表规格落库**。R03 的生成链路已就绪，但真实模型端到端（含 R04 保存）需在 M 系列模型接入后补齐；本卡的证据全部基于构造的合法 ReportSpec。
- **边界：未声明依赖的报表只按归属控制**。`sourcesJson` 为空时逐项复核为空（整体指纹为固定空集合摘要），
  此时 ACL 上只剩"私人报表归属"。生成侧必须把数据来源写进 `sourcesJson`，否则失权后无法阻止旧快照显示；
  该口径已写入 `scope-catalog.md` 与 `data-permission-exemptions.json` 的理由字段。
- **环境缺口（非本卡）：`sh .harness/verify.sh dependencies`** 因 Trivy DB 镜像不可达仍失败（见前序卡记录），不影响本卡门禁链。

## 7. 阶段门禁

| 门禁 | 结果 |
|---|---|
| `sh .harness/verify.sh contracts` | exit 0 |
| `sh .harness/verify.sh backend` | exit 0 |
| `sh .harness/verify.sh frontend` | exit 0（本卡未改前端文件，该结果与本卡最终工作树一致） |
| `sh .harness/verify.sh integration` | exit 1：`Tests run: 209, Failures: 0, Errors: 1`，唯一错误是**既有** `UserProfilePersistenceIT`（全量套件下确定性锁等待，非本卡引入，见下） |
| `./mvnw -o -pl basic-framework-server verify -Pintegration -Dtest=None -Dit.test='UserProfilePersistenceIT'` | exit 0：`Tests run: 8, Failures: 0, Errors: 0`（单独运行 8/8 通过，未被跳过或排除） |
| `./mvnw -q -o -Pintegration -pl basic-framework-coverage -am verify -DskipTests` | exit 0（按既有 exec 数据重生成聚合报告） |
| `node scripts/check-coverage-ratchet.mjs backend` | 失败项仅为 5 个新文件"尚未登记单文件覆盖率基线"（新增文件的预期状态） |
| `node scripts/check-coverage-ratchet.mjs --update` | exit 0：登记 5 个新文件基线（`AiReportController` 100%、`AiReportMapper` 100%、`AiReportVersionMapper` 100%、`AiReportScopeRefs` 93.88%、`AiReportServiceImpl` 96.58%，均高于新文件 80% 下限） |
| `node scripts/check-coverage-ratchet.mjs all` | exit 0：`all 单文件基线通过` |

集成门禁的 `UserProfilePersistenceIT` 是**前序卡已记录的既有偶发/确定性失败**（全量套件下与其它用例争锁，`Lock wait timeout exceeded`），
处理方式与既有记录一致：跑完整套件 → 单独重跑该类确认通过 → 重生成聚合报告 → 更新棘轮。**没有**跳过、排除或注释该用例，也未降低任何基线。

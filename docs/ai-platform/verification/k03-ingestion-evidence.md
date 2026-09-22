# K03 文档上传与入库任务证据（2026-09-22）

本记录是 [K03 实现文档上传和入库任务](../tasks/K03.md) 的验收证据。
依赖 [K02](../tasks/K02.md)（知识库与文档版本数据模型）、[O03](../tasks/O03.md)（任务租约与恢复）、
[A07](../tasks/A07.md)（文件业务授权）均已有证据文档（`k02-knowledge-model-evidence.md`、
`o03-task-lease-evidence.md`、`a07-file-authorization-evidence.md`）。

## 1. 交付内容

| 交付物 | 位置 |
|---|---|
| 迁移（入库任务表 + 两个 Job 种子） | `basic-framework-server/src/main/resources/db/migration/V73__ai_knowledge_ingestion_task.sql` |
| 任务持久化对象与 Mapper（含 CAS 领取/续租/终态/恢复/重排 SQL） | `service/knowledge/ingestion/AiKnowledgeIngestionTaskDO.java`、`AiKnowledgeIngestionTaskMapper.java` |
| 文件策略（类型/大小/指纹，纯函数） | `service/knowledge/ingestion/AiKnowledgeIngestionFilePolicy.java` |
| 入库步骤端口（K04/K05 接入点） | `service/knowledge/ingestion/AiKnowledgeIngestionStep.java` |
| 入库服务与事务写入器 | `service/knowledge/ingestion/AiKnowledgeIngestionService(+Impl)`、`AiKnowledgeIngestionWriter` |
| 服务层 DTO | `service/knowledge/ingestion/dto/`：请求、结论、租约、步骤结论 |
| 入库 Job 与恢复 Job | `job/AiKnowledgeIngestionJob.java`、`job/AiKnowledgeIngestionRecoveryJob.java` |
| 管理端接口（上传入库 + 任务查询/重试） | `controller/admin/knowledge/AiKnowledgeDocumentController`（+3 端点）、`vo/AiKnowledgeUploadRespVO`、`vo/AiKnowledgeIngestionTaskRespVO`、`vo/AiKnowledgeIngestionTaskPageReqVO` |
| 错误码 | `1_003_005_014`–`018`（文件类型/大小/归属、任务不存在/状态冲突），与 `docs/contracts/ai/error-code-map.md` 两侧同步 |
| 台账同步 | SQL 快照（V73、45 张软删除表、Job 种子）、`data-lifecycle.json`（策略 + 3 外键）、`data-permission-exemptions.json`、`PersistenceLifecycleIT`、`docs/data-lifecycle.md` |
| 测试 | 单测 15 例（文件策略 3、入库服务 7、入库 Job 5）；集成 `AiKnowledgeIngestionIT` 7 例（真实 MySQL） |

对外方法：`AiKnowledgeIngestionService`（ingest / claim / heartbeat / finish / recoverExpiredLeases / retry / getTask / getTaskPage）；
管理端 `POST /ai/knowledge-document/upload`（multipart）、`GET /task/get`、`GET /task/page`、`POST /task/retry`。

## 2. 与卡片逐步实施的对应

1. **校验文件 purpose/归属/类型/大小后创建版本**：
   - 类型与大小在**读取之前**判定（扩展名白名单 TXT/Markdown/文本型 PDF/DOCX、单文件上限 32MB、空文件拒绝），
     content-type 只作交叉校验（请求头可伪造，不作为放行依据）；
   - 归属用 A07 文件绑定行判定：业务类型必须是 `ai_knowledge_document`、业务键必须是该知识库标识——
     上传到 A 库的文件不能挂到 B 库，会话附件也不能当知识原文；
   - 指纹（sha256）由**服务端**计算，不接受调用方自报：它是幂等与"内容是否变化"的唯一依据。
2. **事务内写索引任务，后台处理文件引用**：
   - 版本创建（K02 的 sourceKey 幂等）与任务创建在**同一事务**（独立 `AiKnowledgeIngestionWriter` bean，
     避免同类自调用绕过事务代理）；失败整笔回滚；
   - 文件在事务之外（A07 独立资源）：任何失败都触发**补偿**——解除文件引用（无悬空文件）；
     补偿失败只记录不掩盖原始异常；
   - 后台由 `aiKnowledgeIngestionJob`（每 20 秒）领取任务：校验文件引用仍有效 → 推进文档状态 → 调用
     `AiKnowledgeIngestionStep` → 写终态；`aiKnowledgeIngestionRecoveryJob`（每分钟）恢复过期租约。
3. **返回 documentId/taskId，失败不回显原始解析异常**：
   - 上传返回 `documentId/versionId/versionNo/taskId/reused/createdVersion`；
   - 任务只落脱敏稳定原因码（`last_error_code`，单行截断 64 字符），文档/版本只落原因码；
     步骤抛出的异常只记录**异常类型**，正文不外泄。

## 3. 关键约束与安全语义

- **没有实现就不假装成功**：当前没有 `AiKnowledgeIngestionStep` 实现（解析在 K04、索引在 K05），
  入库 Job 以稳定原因码 `parser-unavailable` 结束任务并把版本/文档标失败；
  旧可用版本不受影响（active 指针不动，AT-024 语义）。集成用例专门断言这一行为。
- **租约栅栏**：领取是 CAS（QUEUED → RUNNING + owner + epoch + 到期时间 + 尝试次数递增）；
  续租与终态写入都要求 (owner, epoch) 且租约未过期；租约过期被接管后旧 worker 命中 0 行，
  其"成功"不会被写入（单测 + 集成各断言一次）。
- **重试上限与人工重试**：过期恢复未达上限回队列、达上限置 FAILED（原因码 `lease-expired`）；
  人工重试只接受 FAILED/UNKNOWN 并重置尝试计数（执行中/已成功/排队中的任务一律拒绝）。
- **同版本同类型只有一条任务**（函数唯一索引）：重复入库复用版本时也复用任务，不产生重复处理与重复可见版本。
- **时间按秒取整**：`datetime` 列是秒级精度，写入前统一截断到秒，避免"写进去的时间比现在晚"导致刚入队
  的任务暂时不可领取（该缺陷由集成用例暴露并修复，见第 6 节）。
- **权限**：上传与重试要求 `ai:knowledge:ingest`（K02 的菜单种子），任务查询要求 `ai:knowledge:query`；
  控制器测试断言每个端点恰好一种鉴权策略。

## 4. 验收用例对照

| 验收项 | 结论 | 证据 |
|---|---|---|
| AT-023（超大/损坏/压缩炸弹） | 超出单文件上限、空文件、不支持类型一律拒绝；类型判定在读取前 | `AiKnowledgeIngestionFilePolicyTest`（3 例）；魔数与压缩展开量由 infra 受控文件接口负责（F06/A07） |
| AT-022（TXT/PDF/DOCX 解析） | **部分**：上传与任务编排就绪，正文解析与来源位置由 K04 提供（未验证，见第 9 节） | 上传/任务用例通过；解析断言待 K04 |
| 上传完成和任务创建故障无悬空文件 | 版本+任务同事务；失败触发文件引用补偿 | `AiKnowledgeIngestionServiceImplTest.ingestCompensatesFileWhenVersionOrTaskCreationFails`、`ingestRejectsFileOwnedByAnotherKnowledgeBaseOrUnknownBase`（补偿也被断言） |
| 重复入库不产生重复可见版本 | 同指纹复用版本与任务；任务表存活行唯一 | `AiKnowledgeIngestionIT.repeatedIngestReusesVersionAndTaskWithoutDuplicates` |
| 失败不回显原始解析异常 | 任务/版本/文档只落稳定原因码；步骤异常只记类型 | `AiKnowledgeIngestionJobTest.successfulStepFinishesTheTaskAndFailedStepMarksTheVersion`、`AiKnowledgeIngestionIT.jobFailsHonestlyWhenNoParserImplementationIsWired` |
| 租约与恢复 | CAS 领取、栅栏、过期恢复、上限置 FAILED、人工重试 | `AiKnowledgeIngestionIT.claimHeartbeatAndFinishRespectTheLeaseFence`、`expiredLeaseIsRecoveredAndRetriedUntilMaxAttempts`、`manualRetryRequeuesFailedTaskAndRejectsRunningOne` |

## 5. 验证结果

工作目录：`/home/ctyun/桌面/zhongtai/ai-platform`（授权副本）。
命令前统一 `umask 022; export JAVA_HOME=$HOME/.local/opt/jdk17/usr/lib/jvm/java-17-openjdk-amd64`。

| 命令 | 退出码 | 结论 |
|---|---|---|
| `./mvnw -q -o -pl basic-framework-module-ai spotless:apply` | 0 | 格式已应用 |
| `./mvnw -o -pl basic-framework-module-ai test -Dtest='AiKnowledge*Test' -DfailIfNoTests=false` | 0 | **57 例通过**（K02 42 + K03 15） |
| `./mvnw -o -Pintegration -pl basic-framework-server verify -Dit.test=AiKnowledgeIngestionIT -Dtest=AiKnowledgeIngestionIT -DfailIfNoTests=false -Djacoco.skip=true` | 0 | **7 例通过**（真实 MySQL 8.4.11 容器） |
| `node scripts/check-data-lifecycle.mjs` | 0 | 最终表 72 张、策略登记 72 张、逻辑删除列 45 张、物理外键 55 条；快照同步 |
| `node scripts/check-data-permission.mjs` | 0 | 应用表 61、运行时保护 2、显式豁免 59、平台托管 11；分类检查通过 |

## 6. 顺带修复的依赖缺口（真实暴露）

1. **恢复延迟下限把"立即可重试"变成"1 秒后"**：`recoverExpiredLeases` 最初把延迟按 `Math.max(1, …)` 处理，
   配置 0 也至少等 1 秒；集成用例连续"过期→恢复→立即领取"时随机失败（同秒内领取不到任务）。
   处置：延迟允许为 0（0 = 立即可再领取），负值按 0 处理；同时把服务内所有时间写入统一**按秒截断**，
   避免秒级取整把写入时间推到"未来"。
2. **同类自调用会让 `@Transactional` 失效**：入库的"版本 + 任务同事务"最初写在实现类的 protected 方法里，
   自调用不经过代理 → 事务并不存在。处置：抽出 `AiKnowledgeIngestionWriter`（独立 bean）承载事务边界
   （与 D04 的漂移写入器同一思路）。
3. **测试构造器参数顺序**：新增依赖后 K02 的控制器测试与入库服务测试需要同步构造参数（编译期暴露）。

## 7. 门禁结果

| 命令 | 退出码 | 结论 |
|---|---|---|
| `sh .harness/verify.sh contracts` | 0 | 契约台账、字段目录、权限目录、生命周期、安全检查全部通过 |
| `sh .harness/verify.sh backend` | 0 | 编译、单测（模块 545 例，含本卡 22 例）、格式、架构与覆盖率检查通过 |
| `./mvnw -o -Pintegration -pl basic-framework-server verify -Dit.test=AiKnowledgeIngestionIT -Dtest=AiKnowledgeIngestionIT -DfailIfNoTests=false -Djacoco.skip=true` | 0 | **7 例通过**（真实 MySQL） |
| `./mvnw -o -Pintegration -pl basic-framework-server verify -Dit.test='PersistenceLifecycleIT,RemovedCapabilityMigrationIT' -Dtest='PersistenceLifecycleIT,RemovedCapabilityMigrationIT' -DfailIfNoTests=false -Djacoco.skip=true` | 0 | **6 例通过**（生命周期表清单与内置任务计数随 V73 的 Job 种子同步后） |
| `./mvnw -q -Pintegration clean verify`（全量 IT，产出覆盖率数据） | 1 | **198 例中 1 例既有负载敏感用例失败**（`UserProfilePersistenceIT`，D09/D10/D11/K02 证据均已记录）；本卡新增 7 例与受新 Job 影响的两个用例全部通过 |
| `./mvnw -q -Pintegration -pl basic-framework-server verify -Dit.test=UserProfilePersistenceIT -Dtest=UserProfilePersistenceIT -DfailIfNoTests=false` | 0 | 该既有用例单独跑 8/8 通过（干净容器），确认与本卡改动无关 |
| `./mvnw -q -Pintegration -pl basic-framework-coverage -am verify -DskipTests` | 0 | 重算聚合覆盖率报告 |
| `node scripts/check-coverage-ratchet.mjs --update` / `all` | 0 / 0 | 登记本卡新增文件（入库服务/写入器/策略/端口/Job/Mapper/DTO 与控制器新增端点）；**无下调、无删除** |

### 门禁偏差说明（如实记录）

- 集成门禁的红色来自**既有**用例与套件环境的相互作用（同一现象在 D09、D10、D11、K02 证据中均有记录），
  修复点不在本卡允许路径内。
- 本卡的新迁移新增了两个 `infra_job` 种子，因此同步更新了**两处既有断言**（生命周期用例的 Job 清单、
  内置任务计数 9 → 11）：这是新迁移的合法期望更新，不是放宽校验；两处都已重新运行通过。
- 未跳过、未排除、未注释、未放宽任何用例或基线。

## 8. 覆盖率

本卡新增主源码文件（DO 1、Mapper 1、策略 1、端口 1、服务 3、Job 2、VO 3、控制器端点）在完整覆盖率数据下由
`node scripts/check-coverage-ratchet.mjs --update` 登记单文件基线；登记只新增条目或上调既有值，
不下调任何既有基线（`--update` 在数据不完整时直接拒绝写入）。

## 9. 未验证项

1. **解析与索引**：`AiKnowledgeIngestionStep` 尚无实现（K04 解析、K05 切分与向量化）；
   因此"上传后自动可用"的端到端路径**未验证**，AT-022 的正文/来源位置断言与"扫描件提示 OCR"待 K04。
2. **补偿的真实文件效果**：集成用例直接插入文件绑定行（`ai_file_binding.file_id` 是逻辑引用），
   补偿路径在真实文件上会调用 A07 的"释放 + 最后引用删除"；该行为由 A07 的集成用例覆盖，
   本卡用单测断言"补偿被调用"，未在真实文件上端到端验证（无安全上下文时 A07 的释放会按所有者判定拒绝，
   本卡只记录告警）。
3. **浏览器验收**：上传界面（K09）未建立；本卡只有服务端接口与契约测试。
4. **`controller/app/v1/knowledge`（应用端入口）未使用**：入库是控制面操作（`ai:knowledge:ingest`），
   A03 的动作白名单只有 READ/EXECUTE/EXPORT，没有"写入"动作，因此应用端不应有入库入口；
   应用侧的知识能力是检索与引用读取（K06，按 READ 动作判定）。

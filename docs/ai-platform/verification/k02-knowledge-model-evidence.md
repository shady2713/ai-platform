# K02 知识库与文档版本数据模型证据（2026-09-22）

本记录是 [K02 实现知识库和文档版本数据模型](../tasks/K02.md) 的验收证据。
依赖 [K01](../tasks/K01.md)（向量索引 Go/No-Go）、[A07](../tasks/A07.md)（文件业务授权）、
[F08](../tasks/F08.md)（错误码区间）均已有证据文档（`k01-knowledge-index-evidence.md`、
`a07-file-authorization-evidence.md`、`f08-error-code-migration-evidence.md`）。

## 1. 交付内容

| 交付物 | 位置 |
|---|---|
| 迁移（5 张表 + 菜单权限点 4090-4095） | `basic-framework-server/src/main/resources/db/migration/V72__ai_knowledge_base.sql` |
| 持久化对象 | `dal/dataobject/knowledge/`：`AiKnowledgeBaseDO`、`AiKnowledgeDocumentDO`、`AiKnowledgeDocumentVersionDO`、`AiKnowledgeChunkDO`、`AiKnowledgeIndexGenerationDO` |
| Mapper | `dal/mysql/knowledge/`：知识库、文档、文档版本、切片（含物理删除/统计）、索引代 |
| 状态与取值约束 | `service/knowledge/AiKnowledgeStates`：白名单、标识/指纹/维度校验、文档与索引代状态机 |
| 知识库服务 | `service/knowledge/AiKnowledgeBaseService(+Impl)` + `dto/AiKnowledgeBaseSaveDTO` |
| 文档与版本服务 | `service/knowledge/AiKnowledgeDocumentService(+Impl)` + `dto/AiKnowledgeDocumentSaveDTO`、`dto/AiKnowledgeDocumentUpsertResultDTO` |
| 索引代服务 | `service/knowledge/AiKnowledgeIndexGenerationService(+Impl)` |
| 切片服务 | `service/knowledge/AiKnowledgeChunkService(+Impl)` + `dto/AiKnowledgeChunkDTO` |
| 管理端接口 | `controller/admin/knowledge/`：`AiKnowledgeBaseController`（6 端点）、`AiKnowledgeDocumentController`（7 端点，含入库/版本/索引代只读视图）与 10 个 VO |
| 错误码 | `1_003_005_000`–`013`（14 个）：子区间 `1_003_005_xxx` 由 F08 在 `AiErrorCodeRanges.DOMAIN_KNOWLEDGE` 预留，本卡**不新领区间**，只在该区间内取号；与 `docs/contracts/ai/error-code-map.md` 的 HTTP 映射表两侧同步 |
| 台账同步 | `数据库文件/basic_framework.sql`（快照 V72、44 张软删除表）、`docs/contracts/data-lifecycle.json`（策略与 7 条外键）、`docs/contracts/data-permission-exemptions.json`（`ai-knowledge-config`）、`PersistenceLifecycleIT`（软删除列清单）、`docs/data-lifecycle.md`（知识库表策略） |
| 测试 | 单测 36 例（`AiKnowledgeStatesTest` 5、`AiKnowledgeBaseServiceImplTest` 6、`AiKnowledgeDocumentServiceImplTest` 10、`AiKnowledgeChunkServiceImplTest` 4、`AiKnowledgeIndexGenerationServiceImplTest` 6、`AiKnowledgeControllerTest` 5）；集成 `AiKnowledgePersistenceIT` 7 例（真实 MySQL） |

对外方法：`AiKnowledgeBaseService`（create/update/updateStatus/delete/get/getByCode/requireEnabled/page）、
`AiKnowledgeDocumentService`（upsert/markParsing/markIndexing/markVersionReady/markVersionFailed/deleteDocument/get/page/版本查询）、
`AiKnowledgeIndexGenerationService`（startGeneration/activate/fail/retire/get/list）、
`AiKnowledgeChunkService`（replaceVersionChunks/list/count/delete）。

## 2. 与卡片逐步实施的对应

1. **建立 KB/document/version/chunk/index-generation 表与状态**：V72 建 5 张表；
   文档状态 `PENDING → PARSING → INDEXING → READY/FAILED → DELETING`、
   版本状态 `INDEXING → READY/FAILED`、`READY → SUPERSEDED`、
   索引代状态 `BUILDING → ACTIVE/FAILED`、`ACTIVE → RETIRED`，全部由
   `AiKnowledgeStates` 的转移表约束（未知状态一律拒绝，不默认回退）。
2. **实现 KB 管理、应用授权引用及文档 sourceKey 幂等**：
   - KB 管理：创建/修改/启停/删除 + 分页；**标识、可见性、所属应用、嵌入模型与维度创建后不可修改**
     （更新时忽略而非报错，避免查询回来的对象再提交被无谓拒绝）；
   - 应用授权引用：不在本表复制授权事实——应用可见性走 A03 授权目录
     （资源类型 `KNOWLEDGE_BASE`，资源标识 = 知识库 `code`），本表只声明"共享/应用专用"与所属应用；
   - sourceKey 幂等：同库同 `sourceKey` 在**存活行**中唯一（函数唯一索引），
     入库结论三态可区分（复用 / 指纹变化生成新版本 / 新建文档），重放不产生第二次副作用；
     失败版本重试**复用同一版本行**（不制造版本膨胀）。
3. **绑定私有文件和当前 active 版本，禁止直接修改已发布版本**：
   - 版本必须绑定 A07 业务文件（业务类型 `ai_knowledge_document`，业务键 = 知识库标识），
     文件读取仍按当前归属判定（授权回收后立即读不到，A07 已证）；
   - `active_version_no` 只在索引成功后切换；失败保留旧可用版本（AT-024），
     旧版本切换时置 `SUPERSEDED` 但行与切片保留；
   - `READY`/`SUPERSEDED` 版本不可再改：标记失败、重写切片、再次发布都被
     `AI_KNOWLEDGE_VERSION_IMMUTABLE` 拒绝（单测 + 集成各断言一次）。

## 3. 关键约束与安全语义

- **维度是物理约束**：嵌入模型与维度落在知识库上（创建后不可改），索引代的维度/模型必须与知识库声明一致，
  否则拒绝激活（`AI_KNOWLEDGE_GENERATION_CONFLICT`）——这正是 K01 结论（集合维度不可变、不同模型向量不得混用，AT-029）
  在数据模型上的落点；集成用例用"直接改库把维度改成 768"模拟配置漂移并断言拒绝。
- **切片不存正文**：正文只进向量服务（K01 的 upsert/search），库内保存哈希、长度、token 估算、
  向量点标识、来源位置与索引代——引用（AT-026）可回到"哪个版本、哪个片段位置"。
- **切片整版本替换**：只允许对 `INDEXING` 版本写入，写入前先删旧切片；
  序号重复、哈希非 sha256、向量标识缺失、空切片列表一律拒绝（空切片应由流水线标记失败，而不是"可用但空"）。
- **派生数据物理清理**：切片与索引代没有 `deleted` 列（生命周期台账 hard-delete），
  退役代与删除版本的切片由 K07 显式回收；`deleteByVersion`/`deleteByGeneration` 已就位。
- **删除保护**：知识库被服务绑定引用（`ai_service_resource`，资源类型 `KNOWLEDGE_BASE`）→ `AI_KNOWLEDGE_BASE_REFERENCED`；
  仍有存活文档 → `AI_KNOWLEDGE_BASE_NOT_EMPTY`；两者都**拒绝**而不是级联。
- **失败原因脱敏**：只写稳定原因码并单行截断到 128 字符（单测断言换行被替换、超长被截断）。
- **响应无秘密**：管理端 VO 不含文件内容、向量与任何凭据；控制器测试断言每个端点恰好一种鉴权策略，
  入库端点必须 `ai:knowledge:ingest`（卡片验收项"无 KB 管理权不可入库"）。

## 4. 验收用例对照

| 验收项 | 结论 | 证据 |
|---|---|---|
| 重复 sourceKey 正确复用/更新 | 同指纹复用（不产生新版本）、指纹变化生成新版本、另一知识库同名 sourceKey 是另一文档 | `AiKnowledgeDocumentServiceImplTest.sameSourceKeyAndSameHashReusesExistingVersion`、`changedHashCreatesNextVersionAndKeepsActivePointer`；`AiKnowledgePersistenceIT.ingestIsIdempotentBySourceKeyAndHash` |
| 无 KB 管理权不可入库 | 入库端点唯一鉴权策略为 `@PreAuthorize("@ss.hasPermission('ai:knowledge:ingest')")`；停用知识库拒绝入库 | `AiKnowledgeControllerTest.documentEndpointsRequireIngestPermissionForIngestion`；`AiKnowledgePersistenceIT.deleteGuardsAndDisabledKnowledgeBaseRejectNewIngest` |
| 有服务引用不能删除 KB | 存在 `ai_service_resource(KNOWLEDGE_BASE, code)` 即拒绝 | `AiKnowledgeBaseServiceImplTest.deleteRefusesReferencedOrNonEmptyKnowledgeBase`（IT 覆盖"仍有文档"分支） |
| 版本发布后不可修改 | READY/SUPERSEDED 版本拒绝标记失败与重写切片 | `AiKnowledgeDocumentServiceImplTest.markReadyRejectsImmutableVersionAndUnknownGeneration`、`AiKnowledgeChunkServiceImplTest.replaceRefusesImmutableVersion`、`AiKnowledgePersistenceIT.publishedVersionIsImmutableAndActivePointerSwitchesOnlyOnSuccess` |
| active 版本只在成功后切换（AT-024） | 失败不改指针、旧版本仍可用；成功后旧版本置 SUPERSEDED | 同上集成用例 |
| 索引代单一构建与原子换代 | 同时只允许一个 BUILDING；激活即退役上一代并切换指针；退役清指针 | `AiKnowledgeIndexGenerationServiceImplTest`（6 例）、`AiKnowledgePersistenceIT.indexGenerationLifecycleKeepsOneBuildAndSwitchesAtomically` |
| 维度/模型一致（AT-029 前置） | 不一致拒绝激活 | `AiKnowledgeIndexGenerationServiceImplTest.activateRefusesDimensionOrModelMismatch`、`AiKnowledgePersistenceIT.activateRejectsDimensionDriftAndUnknownGenerationIsRejected` |
| 存活行唯一（软删除后可重建） | 软删除后同一标识可重建，历史行保留 | `AiKnowledgePersistenceIT.knowledgeBaseCodeIsUniqueAmongLiveRowsAndReusableAfterDelete` |
| 状态机与取值白名单 | 未知状态/越界维度/非法指纹一律拒绝 | `AiKnowledgeStatesTest`（5 例） |

## 5. 验证结果

工作目录：`/home/ctyun/桌面/zhongtai/ai-platform`（授权副本）。
命令前统一 `umask 022; export JAVA_HOME=$HOME/.local/opt/jdk17/usr/lib/jvm/java-17-openjdk-amd64`。

| 命令 | 退出码 | 结论 |
|---|---|---|
| `./mvnw -q -o -pl basic-framework-module-ai spotless:apply` | 0 | 格式已应用 |
| `./mvnw -o -pl basic-framework-module-ai compile -DskipTests` | 0 | 编译通过 |
| `./mvnw -o -pl basic-framework-module-ai test -Dtest='AiKnowledge*Test' -DfailIfNoTests=false` | 0 | **36 例通过 / 0 失败** |
| `./mvnw -o -Pintegration -pl basic-framework-server verify -Dit.test=AiKnowledgePersistenceIT -Dtest=AiKnowledgePersistenceIT -DfailIfNoTests=false -Djacoco.skip=true` | 0 | **7 例通过 / 0 失败**（真实 MySQL 8.4.11 容器） |
| `node scripts/check-data-lifecycle.mjs` | 0 | 最终表 71 张、策略登记 71 张、逻辑删除列 44 张、物理外键 52 条；快照同步 |
| `node scripts/check-data-permission.mjs` | 0 | 应用表 60、运行时保护 2、显式豁免 58、平台托管 11；分类检查通过 |
| `sh .harness/verify.sh contracts` | 0 | 契约台账、字段目录、权限目录、生命周期、安全检查全部通过 |

## 6. 顺带修复的依赖缺口（真实暴露）

1. **切片表缺审计列（集成用例暴露）**：`AiKnowledgeChunkDO` 继承框架 `BaseDO`（含 creator/updater），
   但 V72 初稿的 `ai_knowledge_chunk` 只建了 create_time/update_time → 插入报
   `Unknown column 'creator' in 'field list'`。处置：迁移补 `creator`/`updater` 两列（与其余表一致），
   快照同步；不采用"让 DO 不继承 BaseDO"的绕法（会破坏框架审计填充约定）。
2. **敏感字段 toString 门禁（contracts 门禁暴露）**：`tokenCount` 命中敏感字段命名规则，
   `AiKnowledgeChunkDO`/`AiKnowledgeChunkDTO` 需显式 `@ToString.Exclude`——已加（token 计数不进日志）。
3. **状态机与取值模式的两处自查修正**：
   - `sourceKey` 模式最初不允许 `/`，导致 `drive:handbook/v2.pdf` 这类同步键被误拒 →
     改为允许 `/`（sourceKey 只做等值比较与存储，不参与路径解析）；
   - 文档状态机最初不允许 `PENDING → INDEXING`（跳过解析）→ 放开（纯文本无需解析阶段），
     并把"允许/禁止"两类转移都写进单测。

## 7. 门禁结果

| 命令 | 退出码 | 结论 |
|---|---|---|
| `sh .harness/verify.sh contracts` | 0 | 契约台账、字段目录、权限目录、生命周期、安全检查全部通过 |
| `sh .harness/verify.sh backend` | 0 | 编译、单测（模块 522 例，含本卡 42 例）、格式、架构与覆盖率检查通过 |
| `sh .harness/verify.sh frontend` | 0 | 依赖/类型/拼写、lint、覆盖率与生产构建通过（本卡未改前端） |
| `sh .harness/verify.sh integration` | 1 | `mvn clean verify` 在**既有负载敏感用例**（`UserProfilePersistenceIT.olderTransactionSnapshotCannotPreserveSupersededPostsOrIntermediateSession`）失败处中断：**191 例中 190 例通过，仅该 1 例失败**；本卡新增 7 例全部通过 |
| `./mvnw -q -Pintegration clean verify`（全量 IT，产出覆盖率数据） | 1 | 同上（191 例 / 1 例既有失败） |
| `./mvnw -q -Pintegration -pl basic-framework-server verify -Dit.test=UserProfilePersistenceIT -Dtest=UserProfilePersistenceIT -DfailIfNoTests=false` | 0 | 该既有用例单独跑 8/8 通过（干净容器），确认与本卡改动无关（同 D11 证据第 7 节的定位） |
| `./mvnw -q -Pintegration -pl basic-framework-coverage -am verify -DskipTests` | 0 | 基于完整执行数据重算聚合覆盖率报告 |
| `node scripts/check-coverage-ratchet.mjs --update` / `all` | 0 / 0 | 登记 13 个新文件（2 控制器、5 Mapper、5 服务/状态类、1 DO）；**无下调、无删除** |

### 门禁偏差说明（如实记录，未跳过、未放宽）

- 集成门禁的红色来自**既有**用例与套件环境的相互作用（同一现象在 D09、D10、D11 证据中均有记录）：
  该用例位于 `basic-framework-server/src/test/java/com/basicframework/server/integration/UserProfilePersistenceIT.java`，
  **不在本卡允许修改范围**（本卡允许的 server 测试路径只有 `db/migration` 与同变更的 src/test 新增文件）。
- 本卡未跳过、未排除、未注释、未放宽任何用例或基线；K02 的 7 个集成用例在完整套件中全部通过。

## 8. 覆盖率

登记结果（`docs/contracts/coverage-baseline.json`，只增不改）：

| 文件 | 基线 |
|---|---|
| `controller/admin/knowledge/AiKnowledgeBaseController.java` | 84.62 |
| `controller/admin/knowledge/AiKnowledgeDocumentController.java` | 100 |
| `dal/mysql/knowledge/`（5 个 Mapper） | 均 100 |
| `service/knowledge/AiKnowledgeStates.java` | 90.91 |
| `service/knowledge/AiKnowledgeBaseServiceImpl.java` | 89.89 |
| `service/knowledge/AiKnowledgeDocumentServiceImpl.java` | 83.9 |
| `service/knowledge/AiKnowledgeIndexGenerationServiceImpl.java` | 93.2 |
| `service/knowledge/AiKnowledgeChunkServiceImpl.java` | 94.55 |

纯 Lombok 的 DO/VO（除含手写方法的 `AiKnowledgeDocumentVersionDO`）在聚合报告中**没有 sourcefile 行数据**
（构建配置对 Lombok 生成代码加 `@Generated`，JaCoCo 按约定排除），因此不在单文件棘轮范围内——
这是构建约定而非豁免：它们的行为由服务层与集成用例间接断言。

## 9. 未验证项

1. **入库流水线**：解析（K04）、切片与嵌入（K05）、检索与引用（K06）、同步/撤销/清理（K07）未实现；
   本卡只保证"入库结论、版本状态与索引代状态"的正确落库，`markParsing/markIndexing/markVersionReady/markVersionFailed`
   由后续卡片按同一状态机调用（未接入真实流水线，因此"上传即自动可用"的端到端路径未验证）。
2. **服务引用保护的上游**：删除保护只查询 `ai_service_resource`；服务草稿/发布版本解除绑定的交互由 S 系列与 K07/K08 覆盖。
3. **保留策略执行**：`retention_days` 只落库与展示，到期清理由 K07 的清理作业执行（未验证）。
4. **真实向量服务联调**：本卡不调用 Qdrant；索引代与集合名只是声明（`kb_<code>_g<no>`），
   实际集合创建/写入/切换由 K05 在真实容器上验证。
5. **文档 ACL 与检索期过滤**：库权限与文档 ACL 的"检索前过滤 + 引用再鉴权"属 K06；
   本卡只提供检索过滤所需的冗余列（knowledge_base_id、index_generation、document_version_id）。
6. **`docs/contracts/field-catalog.yaml` 与 `docs/security`**：本卡新增字段没有跨栈共享校验规则
   （无前端表单，K09 才建页面），因此未登记字段目录；安全分类沿用 `docs/data-lifecycle.md` 的知识库表策略，
   未新增独立安全文档（如需分级细则由 K06/K09 补充）。

# K07 文档同步、撤销与清理证据（2026-09-23）

本记录是 [K07 实现文档同步、撤销和清理](../tasks/K07.md) 的验收证据。
依赖 [K06](../tasks/K06.md)、[O06](../tasks/O06.md) 均已有证据文档（`k06-retrieval-evidence.md`、`o06-task-query-retry-cleanup-evidence.md`）。

## 1. 交付内容

| 交付物 | 位置 |
|---|---|
| 生命周期服务 | `service/knowledge/lifecycle/AiKnowledgeLifecycleService(+Impl)`：`sync` / `revoke` / `cleanup` / `processPendingCleanups` / `rebuildGeneration` / `auditOrphans` |
| 服务层 DTO | `service/knowledge/lifecycle/dto/AiKnowledgeSyncResultDTO`、`AiKnowledgeCleanupReportDTO`（含 `orphans`、`pendingDocuments`、`hasPending()`） |
| 清理 Job | `job/AiKnowledgeCleanupJob` + 迁移 `V74__ai_knowledge_cleanup_job.sql`（`infra_job` id 36） |
| 台账同步 | SQL 快照（V74 + Job 种子）、`PersistenceLifecycleIT`（Job 清单）、`RemovedCapabilityMigrationIT`（内置任务计数 12）、`docs/data-lifecycle.md`（两段式删除与重建回退窗） |
| 测试 | 单测 `AiKnowledgeLifecycleServiceImplTest` 7 例；集成 `AiKnowledgeLifecycleIT` 3 例（真实 MySQL + 真实 Qdrant） |

对外方法：`sync(knowledgeBaseId, sourceKey, title, sourceRef, fileId, contentHash)`、
`revoke(documentId)`、`cleanup(documentId)`、`processPendingCleanups(limit)`、
`rebuildGeneration(knowledgeBaseId)`、`auditOrphans(knowledgeBaseId, cleanup)`。

本卡**不新增业务表**（清理是状态驱动地推进既有表与向量服务），因此四处台账只需同步 Job 种子与快照版本。

## 2. 与卡片逐步实施的对应

1. **按 sourceKey 更新版本并记录来源**：`sync` 复用 K03 的入库链路（sourceKey 幂等、指纹决定复用还是新版本），
   并把来源写成 `sourceType=API_SYNC` + `sourceRef`（单测断言请求里的来源字段）。
2. **删除先撤可见性后清理 chunk/向量/文件引用**：`revoke` 把文档置 `DELETING`（检索侧 K06 的候选复核要求
   READY + active 版本，因此立即不可见）；`cleanup` 依次删切片行 → 删向量点（按 `document_version_id` 过滤）
   → 释放文件引用（A07 的共享语义）→ 软删除文档/版本行。单测用 `InOrder` 断言**顺序**，
   集成用例断言"撤销后向量与切片被回收、行软删除、知识库随即可删"。
3. **重建 index generation 并保留回退窗，失败任务可重试**：`rebuildGeneration` 先开新一代，
   再对当前 READY 文档逐个重新索引；单文档失败不影响其余（记录并继续），
   失败版本可由 K03 的人工重试入口重新排队；旧代与其切片/向量**保留**作为回退窗，
   由 `auditOrphans` 报告残留（退役代仍有切片会被点名）。

## 3. 关键约束与安全语义

- **顺序即安全**：先撤可见性再清理——反过来会出现"内容已删但引用仍可见"的窗口；
  单测用 `InOrder` 固定该顺序，任何人改动顺序都会让用例失败。
- **分步幂等、状态驱动**：每步按当前状态判断（切片按版本删、向量按版本过滤删、行按编号软删），
  中断后重跑从当前状态继续；集成用例用"limit=1 连跑两轮"证明断点续跑。
- **共享文件不误删**：文件只通过 A07 的 `release` 处理，是否真正删文件由 A07 按"是否最后一个引用"决定；
  本卡不直接调用删除文件接口。集成上下文没有主体，释放会 fail-closed 跳过（清理继续），
  因此这条保证由单测（释放调用）与 A07 的用例（最后引用语义）共同覆盖。
- **失败不中断清理**：单个文件释放失败只记录跳过，不阻断其余资源回收（单测断言报告仍为已处理）。
- **清理不绕过两段式**：`cleanup` 对未撤可见性的文档会先 `revoke`（单测断言），
  保证任何清理路径都先关可见性。

## 4. 验收用例对照

| 验收项 | 结论 | 证据 |
|---|---|---|
| AT-024（更新失败保留旧版本） | 清理只作用于被撤销的文档；重建失败不影响既有可用版本 | `rebuildIndexesActiveVersionsIntoANewGenerationAndKeepsOldOne`（单文档失败仍返回 1 成功）+ K05 的 AT-024 用例 |
| AT-028（删除后索引残留不可访问） | 撤销即不可见；清理删除切片与向量（集成断言检索为空） | `AiKnowledgeLifecycleIT.revokeThenCleanupRemovesChunksVectorsAndRowsSoKnowledgeBaseBecomesDeletable`、`cleanupRevokesVisibilityBeforeDeletingResources` |
| AT-029（换模型不混索引） | 重建走新一代（旧代保留回退窗），维度约束由 K02/K05 把关 | `rebuildIndexesActiveVersionsIntoANewGenerationAndKeepsOldOne`（`startGeneration` 断言）+ K05 证据 |
| AT-030（索引快照恢复） | 旧代切片与向量在换代后保留（回退窗），孤儿检查报告残留 | `orphanAuditDetectsAndReclaimsResidue`（退役代残留被点名并可回收） |
| 共享文件不被误删 | 只调用 A07 的释放语义；释放失败不阻断清理 | `cleanupIsIdempotentAndToleratesFileReleaseFailures` + A07 证据（最后引用语义） |
| 旧索引残留不可读取 | 向量按版本过滤删除；集成断言 `search` 为空 | `AiKnowledgeLifecycleIT`（`search` 断言）+ K06 的候选复核 |
| 重启继续清理 | 状态驱动 + 分步幂等；两轮 limit=1 处理完两个文档，第三轮无操作 | `pendingCleanupsAreResumableAcrossRuns`、`AiKnowledgeLifecycleIT.cleanupResumesAcrossRunsAndIsIdempotent` |
| 孤儿检查和恢复报告 | `auditOrphans(cleanup=false/true)` 只报告或顺带回收，报告含孤儿描述与待处理数 | `orphanAuditDetectsAndReclaimsResidue`、`AiKnowledgeLifecycleIT.orphanAuditIsCleanAfterFullCleanup` |

## 5. 验证结果

工作目录：`/home/ctyun/桌面/zhongtai/ai-platform`（授权副本）。
命令前统一 `umask 022; export JAVA_HOME=$HOME/.local/opt/jdk17/usr/lib/jvm/java-17-openjdk-amd64`。

| 命令 | 退出码 | 结论 |
|---|---|---|
| `./mvnw -q -o -pl basic-framework-module-ai spotless:apply` | 0 | 格式已应用 |
| `./mvnw -o -pl basic-framework-module-ai test -Dtest=AiKnowledgeLifecycleServiceImplTest` | 0 | **7 例通过** |
| `./mvnw -o -Pintegration -pl basic-framework-server verify -Dit.test=AiKnowledgeLifecycleIT -Dtest=AiKnowledgeLifecycleIT -DfailIfNoTests=false -Djacoco.skip=true` | 0 | **3 例通过**（真实 MySQL 8.4.11 + 真实 Qdrant v1.19.1） |
| `node scripts/check-data-lifecycle.mjs` | 0 | 表 72 / 策略 72 / 逻辑删除列 45 / 物理外键 55；快照同步（V74） |

## 6. 顺带修复的依赖缺口（真实暴露）

1. **Job 种子影响既有断言**：V74 新增一个 `infra_job` 种子，`RemovedCapabilityMigrationIT` 的内置任务计数
   与 `PersistenceLifecycleIT` 的 Job 清单需同步（11 → 12、新增 `aiKnowledgeCleanupJob`）——
   与新迁移一致的合法期望更新，两处都已重跑通过。
2. **测试辅助方法把待处理项清空**：集成用例最初先跑 Job 再统计报告，导致报告恒为 0；
   改为直接调用 `cleanup(documentId)` 取报告、并单独断言 Job 在"无待处理项"时也不报错。

## 7. 门禁结果

| 命令 | 退出码 | 结论 |
|---|---|---|
| `sh .harness/verify.sh contracts` | 0 | 契约台账、字段目录、权限目录、生命周期、安全检查全部通过 |
| `sh .harness/verify.sh backend` | 0 | 编译、单测（模块含本卡 9 例）、格式、架构与覆盖率检查通过 |
| `sh .harness/verify.sh frontend` | 0 | 本卡未改前端，门禁保持通过 |
| `./mvnw -q -Pintegration clean verify`（全量 IT） | 1 | **204 例中 1 例既有负载敏感用例失败**（`UserProfilePersistenceIT`，多份证据已记录）；本卡新增 3 例全部通过 |
| `./mvnw -q -Pintegration -pl basic-framework-server verify -Dit.test=UserProfilePersistenceIT -Dtest=UserProfilePersistenceIT -DfailIfNoTests=false` | 0 | 该既有用例单独跑通过（干净容器） |
| `./mvnw -q -Pintegration -pl basic-framework-coverage -am verify -DskipTests` | 0 | 重算聚合覆盖率报告 |
| `node scripts/check-coverage-ratchet.mjs --update` / `all` | 0 / 0 | 登记本卡与 K06 遗留的未登记文件；无下调、无删除 |

## 8. 覆盖率

本卡新增主源码文件在完整覆盖率数据下由 `node scripts/check-coverage-ratchet.mjs --update` 登记单文件基线；
只新增条目或上调既有值，不下调任何既有基线。

## 9. 未验证项

1. **退役代向量的回收策略**：孤儿检查能点名"退役代仍有切片/向量"，但**不自动回收**——
   回退窗保留多久、何时回收属运营策略（K09/后续运维卡决定）。
2. **回退到退役代**：K02 的索引代状态机只允许 `BUILDING → ACTIVE`，因此"把已退役的一代重新激活"
   不被支持；本卡的回退窗是"保留数据 + 重建"（重建即重新索引），未实现"原地回退激活"。
3. **文件引用的真实删除**：集成上下文没有主体，A07 的释放 fail-closed 跳过；
   "最后一个引用释放时真正删文件"由 A07 的用例覆盖，本卡未在集成里端到端验证。
4. **保留期到期自动撤销**：知识库的 `retention_days` 只落库与展示，按保留期自动触发撤销/清理
   （`processPendingCleanups` 只处理已撤销的文档）尚未接线，属 K09 的运营配置范围。

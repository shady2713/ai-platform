# K05 切片嵌入与版本化索引证据（2026-09-22）

本记录是 [K05 实现切片嵌入和版本化索引](../tasks/K05.md) 的验收证据。
依赖 [K04](../tasks/K04.md)（受限解析器）、[K01](../tasks/K01.md)（向量索引端口）、[M04](../tasks/M04.md)（模型调用）
均已有证据文档（`k04-document-parser-evidence.md`、`k01-knowledge-index-evidence.md`、`m04-model-*`）。

## 1. 交付内容

| 交付物 | 位置 |
|---|---|
| 切片器（固定 chunker 版本 + 稳定 chunkId + 位置继承） | `service/knowledge/indexing/AiKnowledgeChunker`、`AiKnowledgeChunk` |
| 嵌入端口与失败原因 | `service/knowledge/indexing/AiKnowledgeEmbeddingClient`、`AiKnowledgeEmbeddingException`（`embed-endpoint_unavailable`/`embed-dimension_mismatch`/`embed-upstream_failed`/`embed-response_invalid`） |
| 原文读取端口 | `service/knowledge/indexing/AiKnowledgeSourceReader` + `adapter/knowledge/FileApiKnowledgeSourceReader`（经 infra 受控文件接口，主体取自安全上下文） |
| 真实嵌入客户端 | `adapter/knowledge/AiModelEmbeddingClient`（按知识库声明的模型解析端点 → 维度校验 → M04 调用，外发等级 L2_INTERNAL） |
| 索引流水线 | `service/knowledge/indexing/AiKnowledgeIndexingService`（读原文 → 解析 → 切片 → 批量嵌入 → 写向量与切片 → 全成功才切 active） |
| 入库步骤实现 | `service/knowledge/indexing/AiKnowledgeIndexingStep`（接入 K03 的 `AiKnowledgeIngestionStep` 端口） |
| 装配 | `service/knowledge/indexing/AiKnowledgeIndexingConfiguration`（解析器与切片器 Bean） |
| 测试 | 单测 15 例（切片器 5、流水线 8、步骤 2）；集成 `AiKnowledgeIndexingIT` 3 例（真实 MySQL + 真实 Qdrant 容器） |

对外方法：`AiKnowledgeIndexingService.index(documentId, documentVersionId) → IndexingOutcome`；
`AiKnowledgeChunker.chunk(versionId, segments) → List<AiKnowledgeChunk>`；`AiKnowledgeIndexingStep.process(lease)`。

## 2. 与卡片逐步实施的对应

1. **按固定 chunker 版本生成稳定 chunkId 和位置**：`CHUNKER_VERSION="v1"`；向量点标识由
   `(documentVersionId, chunkerVersion, chunkIndex)` 确定性派生为 UUID（K01 要求点 ID 是 UUID），
   内容哈希只用于"内容是否变化"，不参与点标识——因此重跑覆盖同一批点而不是追加；
   位置取该块**起始段落**的位置（`段落 N`/`第 N 页`），引用可核验（AT-026）。
   超过块上限的单个段落按窗口切开（带重叠），不会产出超长块。
2. **批量 embedding 后按 documentVersion 幂等写索引**：分批（32 条/批）调用嵌入；
   向量载荷只放检索与引用所需字段（知识库/版本/序号/位置/正文）；
   切片行按版本整表替换（K02 的 `replaceVersionChunks`）。
3. **所有 chunk 成功才切 active，失败保留旧版本**：任何一步失败（读取/解析/嵌入/向量写入）都
   **不激活索引代**、不切换 active 版本；失败原因只落稳定原因码（`parse-*`/`embed-*`/`source-*`/`index-*`），
   K03 的 Job 据此把任务与版本标失败，旧可用版本继续服务（AT-024）。

## 3. 关键约束与安全语义

- **模型与维度不可混用（AT-029）**：嵌入模型来自知识库声明（调用方不能临时换模型）；
  维度必须与知识库声明一致，且先嵌入后建集合——不一致在**写任何索引数据之前**就被拒绝；
  模型中心另有"同维度不同 revision"的维度基线校验（`assertEmbeddingDimensionUnchanged`）。
- **原文读取必须走业务 ACL**：`FileApiKnowledgeSourceReader` 经 infra 的受控文件接口读取，
  主体取自安全上下文（A07 的 Provider 再按 A03 判定）；后台线程没有主体时**失败**而不是"以系统身份读全部文件"。
- **无向量服务不假装成功**：`KnowledgeIndexPort` 缺席时返回 `index-service-unavailable`。
- **扫描件不造假**：解析结论为"需要 OCR"时直接失败（`parse-ocr_required`），不生成占位正文。
- **失败不含正文**：异常只带原因码与异常类型名（K04/K05 同一约定）。

## 4. 验收用例对照

| 验收项 | 结论 | 证据 |
|---|---|---|
| AT-024（更新文档索引失败，旧 active 版本仍可用） | 嵌入失败不激活、不改 active 指针；旧版本仍 READY | `AiKnowledgeIndexingServiceTest.embeddingFailureKeepsTheOldVersionAndDoesNotActivate`、`AiKnowledgeIndexingIT.failedIndexingKeepsTheOldActiveVersion` |
| AT-029（换模型同维度不得混用） | 维度与知识库声明不一致 → 拒绝；模型来自知识库声明；维度基线校验 | `dimensionMismatchIsRejectedBeforeWritingAnything`、`AiKnowledgeIndexingIT.dimensionMismatchBetweenModelAndKnowledgeBaseIsRejected` |
| 中途失败重试不重复 | 点标识确定性、切片行按版本替换：重跑后点数不变 | `AiKnowledgeChunkerTest.vectorIdsAreDeterministicAndIndependentOfContent`、`AiKnowledgeIndexingServiceTest.rerunningTheSameVersionDoesNotDuplicateVectors` |
| 索引流水线可端到端跑通（真实向量服务） | 文档入库 → 索引 → 检索命中（引用可核验） | `AiKnowledgeIndexingIT.indexesDocumentIntoRealVectorServiceAndSwitchesActiveVersion` |
| 状态事务与故障恢复 | 索引代 BUILDING → ACTIVE 与文档 active 版本切换在同一编排内；失败路径不激活 | 流水线单测（激活/未激活断言）+ 集成用例（失败保留旧版本） |

## 5. 验证结果

工作目录：`/home/ctyun/桌面/zhongtai/ai-platform`（授权副本）。
命令前统一 `umask 022; export JAVA_HOME=$HOME/.local/opt/jdk17/usr/lib/jvm/java-17-openjdk-amd64`。

| 命令 | 退出码 | 结论 |
|---|---|---|
| `./mvnw -q -o -pl basic-framework-module-ai spotless:apply` | 0 | 格式已应用 |
| `./mvnw -o -pl basic-framework-module-ai test -Dtest='AiKnowledge*Test' -DfailIfNoTests=false` | 0 | **81 例通过**（含 K05 新增 15 例） |
| `./mvnw -o -Pintegration -pl basic-framework-server verify -Dit.test=AiKnowledgeIndexingIT -Dtest=AiKnowledgeIndexingIT -DfailIfNoTests=false -Djacoco.skip=true` | 0 | **3 例通过**（真实 MySQL 8.4.11 + 真实 Qdrant v1.19.1 容器） |

## 6. 顺带修复的依赖缺口（真实暴露）

1. **解析器/切片器没有 Spring Bean**：`DocumentParser` 与 `AiKnowledgeChunker` 是无状态纯类，
   K04 未装配 → 流水线启动失败（`No qualifying bean of type DocumentParser`）。
   处置：K05 在自己的包内新增 `AiKnowledgeIndexingConfiguration` 装配二者（解析器保持无 Spring 依赖，便于单测）。
2. **流水线顺序**：最初"先建集合再嵌入"，维度不一致时报错落在向量服务侧（`index-upstream-failed`），
   语义不如自身校验清晰 → 调整为"先嵌入（含知识库维度校验）再建集合"，维度不一致在写任何索引数据前拒绝。
3. **集成用例隔离**：向量集合的维度创建后不可变（K01 结论），跨用例复用同一集合会互相污染 →
   每个用例使用独立知识库标识与集合名；替身的状态改为用例显式开关，避免跨用例串扰。

## 7. 门禁结果

| 命令 | 退出码 | 结论 |
|---|---|---|
| `sh .harness/verify.sh contracts` | 0 | 契约台账、字段目录、权限目录、生命周期、安全检查全部通过 |
| `sh .harness/verify.sh backend` | 0 | 编译、单测（模块含本卡新增用例）、格式、架构与覆盖率检查通过 |
| `sh .harness/verify.sh frontend` | 0 | 本卡未改前端，门禁保持通过 |
| `./mvnw -q -Pintegration clean verify`（全量 IT） | 1 | 仅既有负载敏感用例失败（`UserProfilePersistenceIT`，多份证据已记录）；本卡新增 3 例全部通过 |
| `./mvnw -q -Pintegration -pl basic-framework-server verify -Dit.test=UserProfilePersistenceIT -Dtest=UserProfilePersistenceIT -DfailIfNoTests=false` | 0 | 该既有用例单独跑通过（干净容器） |
| `./mvnw -o -Pintegration -pl basic-framework-server verify -Dit.test=AiKnowledgeIngestionIT -Dtest=AiKnowledgeIngestionIT -DfailIfNoTests=false -Djacoco.skip=true` | 0 | K03 的入库用例在 K05 接入后按新语义通过（失败原因由 `parser-unavailable` 变为 `index-service-unavailable`，仍是 fail-closed） |
| `./mvnw -q -Pintegration -pl basic-framework-coverage -am verify -DskipTests` | 0 | 重算聚合覆盖率报告 |
| `node scripts/check-coverage-ratchet.mjs --update` / `all` | 0 / 0 | 登记本卡新增文件（切片器/流水线/步骤/装配/端口/异常/适配器/读取器）；无下调、无删除 |

### 依赖既有用例的期望更新（如实记录）

K05 把 `AiKnowledgeIngestionStep` 落地后，K03 的集成用例
`jobFailsHonestlyWhenNoParserImplementationIsWired` 的预期失败原因由 `parser-unavailable` 变为
`index-service-unavailable`（该用例上下文没有配置向量服务，K01 的适配器不是 Spring Bean）。
这是**接入后的真实语义**，用例已改名为 `jobFailsHonestlyWhenIndexingIsUnavailable` 并同步断言；
未跳过、未放宽任何检查。

## 8. 覆盖率

本卡新增主源码文件在完整覆盖率数据下由 `node scripts/check-coverage-ratchet.mjs --update` 登记单文件基线；
只新增条目或上调既有值，不下调任何既有基线。

## 9. 未验证项

1. **真实模型嵌入**：集成用例用确定性替身（同文本同向量、可被检索命中）；真实嵌入模型的效果与
   维度行为由 Q04/Q10 的评测负责，本卡未调用真实模型端点。
2. **后台主体上下文**：入库 Job 当前没有主体上下文，生产环境的原文读取会以
   `source-subject_missing` 失败（fail-closed）；"后台在什么授权下读取原文"是 K07/K08 的策略决定，
   本卡只保证不绕过业务 ACL。
3. **大批量与性能**：未做规模压测（分批 32 条、单块 800 字符为默认值）。
4. **索引代回收**：K05 只负责建立与激活；退役代的向量与切片回收由 K07 执行（`deleteGenerationChunks` 已就位）。
5. **chunker 版本升级路径**：换 chunker 版本必须换索引代，这条约束在代码注释与常量中体现，
   但"自动换代"的编排由 K07/K09 决定（未验证）。

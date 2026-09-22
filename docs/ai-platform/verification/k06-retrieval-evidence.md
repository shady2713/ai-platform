# K06 授权检索与引用读取证据（2026-09-22）

本记录是 [K06 实现授权检索与引用读取](../tasks/K06.md) 的验收证据。
依赖 [K05](../tasks/K05.md)、[A03](../tasks/A03.md)、[A07](../tasks/A07.md) 均已有证据文档。

## 1. 交付内容

| 交付物 | 位置 |
|---|---|
| 检索与引用服务 | `service/knowledge/retrieval/AiKnowledgeRetrievalService(+Impl)` |
| 服务层 DTO | `service/knowledge/retrieval/dto/AiKnowledgeCitationDTO`、`AiKnowledgeRetrievalResultDTO` |
| 应用端接口（3 个，`@AuthenticatedOnly`） | `controller/app/v1/knowledge/AiKnowledgeRetrievalController`：`POST /ai/knowledge/search`、`GET /ai/knowledge/citation`、`GET /ai/knowledge/document/content` + 2 个 VO |
| 端点登记 | `AiAppEndpointScopeContractTest`（3 个端点）、`docs/contracts/ai/scope-catalog.md`（3 行）、`docs/integrations/open-api/ai-open-api.json`（3 条路径） |
| 测试 | `AiKnowledgeRetrievalServiceImplTest` 8 例（过滤前置、恶意输入、候选复核、引用映射、读取再鉴权、topK 边界） |

对外方法：`search(query, topK)`、`readCitationSnippet(citationId)`、`readOriginal(documentId)`。

## 2. 与卡片逐步实施的对应

1. **服务端生成类型化 ACL 过滤条件并执行向量检索**：授权范围 = 当前主体的 A03 READ 授权 ∩ 启用中的知识库；
   过滤条件只由它推导（`KnowledgeFilter.of("knowledge_base_id", 授权知识库编号)`），
   按嵌入模型分组检索（同模型同维度共用一个查询向量），逐知识库用其**生效索引代**的集合检索。
2. **正文进入模型前复核当前 ACL/activeVersion**：候选逐条复核"知识库启用 + 版本 READY +
   是文档当前 active 版本 + 当前主体仍被授权"，任一不满足即丢弃并计入 `filteredOutCount`。
3. **引用按真实 chunk 构造，原文/片段读取再次鉴权**：引用标识 = `知识库:版本:切片序号`，
   读取片段重新走 A03 判定后从**原文**取回该位置正文；读取原文经 A07 的业务文件权限 SPI。
4. **捕获传给模型的实际上下文，证明没有被拒正文**：检索结论同时返回命中引用与
   `candidateCount`/`filteredOutCount`/`searchedKnowledgeBaseCount`——调用方（K08）据此证明
   进入模型的片段只来自本次候选；`noEvidence()` 供调用方明确说明"资料不足"。
5. **引用只允许从本次检索候选映射构建**：引用由服务端在复核通过后生成，
   调用方无法提交文档编号或位置；原文点击走同一业务文件权限 SPI（A07）。

## 3. 关键约束与安全语义

- **恶意取值改不了授权**：问题文本只用于向量化，不参与过滤条件构造（单测用
  `knowledge_base_id:other-base` 证明过滤条件仍只含授权范围）。
- **没有授权就不检索**：授权范围为空时直接返回空结果，不调用向量服务（单测断言 `search` 未被调用）。
- **索引残留不可见**：删除文档后向量仍在索引中，但复核丢弃候选（`filteredOutCount` 递增），
  检索结论为"无证据"。
- **读取再鉴权**：片段/原文读取在授权回收后立即失败（越权与不存在同语义，防枚举）。
- **无向量服务不假装成功**：索引端口缺席时返回空结果并保持"无证据"。

## 4. 验收用例对照

| 验收项 | 结论 | 证据 |
|---|---|---|
| AT-025（检索 Alice/Bob 隔离） | 过滤条件只由当前主体授权推导；无授权主体不检索 | `searchBuildsFilterOnlyFromGrantsAndIgnoresMaliciousQueryText`、`withoutGrantsNothingIsSearched`、`revokedReadGrantIsNotInTheAuthorizedScope` |
| AT-026（引用不被编造、可核验） | 引用只从本次候选生成，标识含知识库/版本/切片；片段读取回到原文 | `searchBuildsFilterOnlyFromGrantsAndIgnoresMaliciousQueryText`、`citationSnippetIsReadFromOriginalAfterReauthorization` |
| AT-028（删除后索引残留不可见） | 候选复核要求 active 版本 + READY + 启用，删除/换代后丢弃 | `candidatesAreReverifiedAgainstActiveVersionAndCurrentAcl` |
| 受限文档从未进入模型入参 | 只有复核通过的候选进入 `citations`；被拒候选只计入 `filteredOutCount` | 同上（`filteredOutCount` 断言） |
| 恶意过滤值不可改写授权 | 问题文本中的过滤语法不进入过滤条件 | `searchBuildsFilterOnlyFromGrantsAndIgnoresMaliciousQueryText` |

## 5. 验证结果

工作目录：`/home/ctyun/桌面/zhongtai/ai-platform`（授权副本）。
命令前统一 `umask 022; export JAVA_HOME=$HOME/.local/opt/jdk17/usr/lib/jvm/java-17-openjdk-amd64`。

| 命令 | 退出码 | 结论 |
|---|---|---|
| `./mvnw -q -o -pl basic-framework-module-ai spotless:apply` | 0 | 格式已应用 |
| `./mvnw -o -pl basic-framework-module-ai test -Dtest=AiKnowledgeRetrievalServiceImplTest` | 0 | **8 例通过** |
| `./mvnw -o -pl basic-framework-server test -Dtest='AiAppEndpointScopeContractTest,AiOpenApiContractTest'` | 0 | 应用端端点登记与 OpenAPI 契约一致（3 个新端点） |

## 6. 未验证项（含本卡的真实阻断）

1. **真实向量服务上的端到端检索用例未交付**：本卡尝试编写 `AiKnowledgeRetrievalIT`
   （真实 MySQL + 真实 Qdrant），在测试装配上连续受阻——A03 的判定要求**主体范围可解析**
   （依赖 A05 票据会话），而检索 IT 的上下文没有会话；改用真实票据登录时又遇到应用凭据校验问题。
   最终该用例未通过，**已删除而不是以跳过/注释形式留在仓库**；本卡只交付了服务层单测（覆盖上表全部验收点）
   与 K05 的真实索引用例（索引侧）。后续卡（K07/K08）建立后台/会话主体策略后应补该端到端用例。
2. **片段位置的精确对应**：`readCitationSnippet` 返回"引用切片序号起始的原文段"，与切片边界
   （可能跨段）不完全重合；逐字一致性未验证（单测只断言非空与来源正确）。
3. **A03 的真实判定**：单测用 Mockito 桩验证"授权被拒即丢弃/拒绝"；A03 自身的范围解析语义
   由 `AiAuthorizationMatrixIT` 覆盖（本卡未重复）。
4. **多知识库混合模型的检索排序**：按模型分组分别检索后按引用标识排序，未做跨组相关度归一化（未验证）。

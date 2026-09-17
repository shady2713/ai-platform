# M04 嵌入模型与能力探测证据（2026-09-17）

本记录是 [M04 实现嵌入模型与能力探测](../tasks/M04.md) 的验收证据。

## 1. 交付内容

| 交付物 | 位置 |
|---|---|
| 嵌入契约 | `starter-ai` `core/model`：`EmbeddingRequest`（批量、逐条非空白、空批次拒绝）、`EmbeddingResponse`（向量顺序与请求一致、**构造即校验维度一致**） |
| 端口扩展 | `ModelPort.embed(EmbeddingRequest)`、`ModelPort.probe(ModelProbeKind)`；未覆盖时按能力缺失拒绝并指明能力名 |
| 探测词汇 | `ModelProbeKind`（`CONNECTIVITY`/`TEXT`/`TEXT_STREAM`/`STRUCTURED_OUTPUT`/`TOOL_CALLING`/`EMBEDDING`）、`ModelProbeResult`（`SUPPORTED`/`UNSUPPORTED`/`FAILED` + 稳定明细码 + 维度 + 耗时） |
| 批次与错误码 | `AiModelProperties.max-embedding-batch`；`ModelException` 新增 `BATCH_TOO_LARGE`、`EMBEDDING_DIMENSION_MISMATCH`、`TOOL_CALLING` 能力 |
| 嵌入实现 | `provider/springai/SpringAiModelClient.embed`：批次上限、条数一致性、按厂商 index 还原请求顺序、用量映射（缺失记 UNKNOWN） |
| 探测实现 | `provider/springai/SpringAiModelClient.probe`：六类探测全部基于真实调用；工具探测注册**禁止执行**的占位工具并关闭厂商内部执行循环 |
| 业务服务 | `module-ai/service/model/AiModelCapabilityProbeService(+Impl)`：探测并落库、只读最新结论、能力总览（声明 ∩ 确认 = 可发布） |
| 持久化 | 迁移 `V49__ai_model_probe.sql`：`ai_model_probe` 表 + `ai_model_endpoint.embedding_dimension` 列 + 权限点 `ai:model-endpoint:probe`（菜单 id 4005）；快照 `数据库文件/basic_framework.sql` 同步 |
| 维度保护 | `AiModelEndpointService.assertEmbeddingDimensionUnchanged`：首次写入记录，之后**改变即 409**（`AI_MODEL_EMBEDDING_DIMENSION_CHANGED`） |
| 接口 | `controller/admin/model/AiModelCapabilityProbeController`：`POST /ai/model-endpoint/{id}/probe`、`GET .../{id}/probe`、`GET .../{id}/capabilities` |
| 台账 | `docs/contracts/data-lifecycle.json`（新表策略）、`data-permission-exemptions.json`（豁免 + 控制器权限证据）、`error-code-map.md`（新错误码 409 语义） |
| 测试 | starter：`SpringAiEmbeddingTest`(8)、`SpringAiModelProbeTest`(11)、`EmbeddingContractTest`(6)；module：`AiModelCapabilityProbeServiceImplTest`(6)、`AiModelCapabilityProbeControllerTest`(4)、`AiModelEndpointServiceImplTest` 维度用例(3)；IT：`AiModelProbePersistenceIT`(3) |

## 2. 与卡片逐步实施的对应

1. **批量 embedding 与维度校验**：`EmbeddingRequest` 拒绝空批次与空白文本；客户端按
   `max-embedding-batch` 拒绝超限批次（**不调用上游**）；响应条数必须与请求一致，
   `EmbeddingResponse` 构造时校验所有向量长度一致；厂商乱序返回时按 index 还原请求顺序。
2. **连接/文本/结构化/工具/嵌入探测**：六类探测各做一次真实调用（流式探测为 M03 能力的补充）；
   结论含状态、稳定明细码、耗时；工具探测关闭厂商内部工具执行循环并使用会抛错的占位工具，
   **一旦工具被执行即判定 FAILED**，从机制上保证探测不触发任何工具。
3. **声明与适配共同决定可发布范围**：`getCapabilityOverview` 返回 `declared` / `supported` /
   `publishable`，其中 `publishable = declared ∩ supported`；未确认（FAILED/UNSUPPORTED/从未探测）
   的能力不进发布范围。

## 3. 关键约束与安全语义

- **维度改变拒绝写既有索引**：`assertEmbeddingDimensionUnchanged` 首次观测记录维度（CAS 写入，
  并发首写以数据库最终值为准），此后任何不同维度直接 409；拒绝后不写库（IT 断言维度未被改写）。
- **批次长度异常失败**：超限批次在调用上游之前拒绝（`BATCH_TOO_LARGE`），且该原因在可重试白名单之外，
  不会重发放大上游压力。
- **探测失败不被启用状态掩盖**：探测走 `resolveForProbe`（只要求端点存在且凭据可解密，**不要求启用**）；
  端点解析失败时六类探测全部记为 FAILED 并带稳定原因，而不是跳过或抛 500；停用端点的探测结果同样落库。
- **不落敏感内容**：`ai_model_probe` 只存状态、明细码、维度与耗时；失败明细是 `ModelException.Reason` 名称，
  不含提示词、响应正文与凭据；`credential_revision` 只记录版本号并排除在 `toString` 之外。
- **凭据边界不变**：探测复用 M02 的解析链路（端点行密文解密后进工厂），响应不含凭据字段
  （控制器用例断言响应模型字段集合）。

## 4. 验证结果

| 命令 | 退出码 | 结论 |
|---|---|---|
| `./mvnw -pl basic-framework-core/basic-framework-spring-boot-starter-ai verify` | 见交接记录 | 嵌入与探测用例通过，新文件行覆盖率 ≥ 80% |
| `./mvnw -pl basic-framework-module-ai -am verify` | 见交接记录 | 服务/控制器用例通过 |
| `AiModelProbePersistenceIT`（`-Pintegration`） | 见交接记录 | 真实 MySQL：探测追加落库与最新结论、停用端点结论保留、维度首写与拒绝改变 |
| `node scripts/check-data-lifecycle.mjs` / `check-data-permission.mjs` | 0 | 新表策略与数据权限豁免登记后通过 |
| `sh .harness/verify.sh contracts` / `backend` / `integration` | 见交接记录 | 按卡片顺序全量门禁 |

| `sh .harness/verify.sh integration` | 0 | Testcontainers（MySQL 8.4）全部集成用例通过，门禁末尾的覆盖率棘轮检查同时通过 |

集成门禁期间修复的两个真实缺陷：V49 迁移菜单种子列数不匹配（MySQL 1136）、
快照 `数据库文件/basic_framework.sql` 菜单 4004 行缺右括号（快照回放用例失败）。

**覆盖率棘轮（已收口）**：补测后 `node scripts/check-coverage-ratchet.mjs --update` 登记基线，
`node scripts/check-coverage-ratchet.mjs all` 复验通过（只升不降）。AI 文件的登记值：

| 文件 | 行覆盖率 |
|---|---|
| `SpringAiModelClient` | 98.45% |
| `SpringAiModelClientFactory` | 100% |
| `AiModelClientResolver` | 100% |
| `AiModelInvocationServiceImpl` | 97.83% |
| `core/model/*`（M03/M04 新增契约） | 100%（`StructuredJsonOutput` 94.44%） |
| `AiModelCapabilityProbeServiceImpl` | 95.08% |
| `SpringAiModelStream` | 86.26% |

## 5. 未验证项

1. **真实厂商端点探测**：探测实现与持久化用桩客户端（单测）与不可达目标（IT）验证；
   真实 OpenAI 兼容端点的六类探测结论属环境验收（需凭据与允许清单）。
2. **可发布范围的消费方**：应用发布（A/D 系列）尚未接线 `getCapabilityOverview`，
   当前只提供契约与接口；发布校验接入后需要补充端到端用例。
3. **向量索引侧写入**：知识库（K 系列）在写索引前需调用 `assertEmbeddingDimensionUnchanged`；
   本卡只提供并验证该保护点，不实现索引写入。
4. **工具调用的真实执行**：探测只验证"模型会返回工具调用"，执行与结果回填属工具层（D08/D09）。

# AI 中台上游候选台账与兼容矩阵（F02）

本记录是 [F02 验证并冻结第一组上游依赖](../ai-platform/tasks/F02.md) 的交付物：候选版本、实测组合、Go/No-Go 结论与待补证据。
所有实验在授权实验目录（`/tmp/f02-experiment`）进行，不进入交付仓库；原始日志与产物归档在 `.local-state/f02-upstream/`（Git 忽略）。

## 1. 结论摘要

| 上游 | 冻结版本 | 结论 | 依据 |
|---|---|---|---|
| Spring AI | 1.1.8 | **Go** | 1.1 线最后一个发布（2026-06-12），Boot 3.5.15 基线；1.1 线全部已知安全公告的最高修复阈值即 1.1.8 |
| Spring AI 2.x | 2.0.1（不采用） | **No-Go** | 官方发布说明写明升级到 Spring Boot 4.1.0，与平台 Boot 3.5 线不兼容 |
| Qdrant 服务端 | qdrant/qdrant:v1.19.1 | **Go** | 与 Spring AI 1.1.8 所用 Java 客户端 1.13.0 实测 create/upsert/query/search/retrieve/delete 全链路通过 |
| Qdrant Java 客户端 | io.qdrant:client:1.13.0 | **Go** | Spring AI 1.1.8 钉定版本；对服务端 1.19.1 实测通过 |
| Apache Tika | 3.2.3（core 既有 + parsers 冻结） | **Go** | 两个 CRITICAL 修复于 3.2.2，3.2.3 已覆盖；解析行为验证转 K04 |

## 2. Spring AI 维护线与安全公告核查

### 2.1 版本线事实（来源：官方发布页与 Maven 元数据）

| 版本 | 发布日期 | 关键内容 |
|---|---|---|
| 1.1.6 | 2026-05-08 | 修复多个 1.1 线注入类公告 |
| 1.1.7 | 2026-05-22 | Anthropic Skills 路径穿越修复（GHSA-cc4m-mp48-x7qg） |
| 1.1.8 | 2026-06-12 | 升级 Spring Boot 3.5.15、MCP SDK 0.18.3；ES/OpenSearch/GemFire 向量库过滤注入修复 |
| 2.0.0 / 2.0.1 | 2026-06-12 / 2026-08-21 | 升级 **Spring Boot 4.1.0**、MCP SDK 2.0.0 |

1.1.8 之后 3 个月无 1.1.9，1.1 线以 1.1.8 收尾；平台不采用 2.x（Boot 4 线）。

### 2.2 1.1 线安全公告清单（GitHub Advisory Database，org.springframework.ai）

| GHSA | 级别 | 主题 | 1.1 线修复版本 |
|---|---|---|---|
| GHSA-cmwh-w62w-r2mf | high | ES/OpenSearch/GemFire 向量库元数据过滤 | **1.1.8** |
| GHSA-cc4m-mp48-x7qg | medium | Anthropic Skills 文件名未净化 | 1.1.7 |
| GHSA-q62f-h9x2-gcqc | high | ChatMemory 默认会话 ID 跨用户泄露 | 1.1.6 |
| GHSA-5852-phmh-8fhr | high | PromptChatMemoryAdvisor 记忆投毒 | 1.1.6 |
| GHSA-v632-2m87-7469 | high | Milvus/Typesense 过滤表达式注入 | 1.1.6 |
| GHSA-63c8-m9m2-cvr3 | high | CosmosDB 向量库 SQL 注入 | 1.1.5 |
| GHSA-qc4j-qjqx-vr58 | high | VectorStore 过滤表达式转换注入 | 1.1.5 |
| GHSA-fvh3-672c-7p6c | critical | 过滤表达式 key 触发 SpEL 注入 | 1.1.4 |
| GHSA-44f4-gvwj-6qg3 | high | Redis 向量库 TAG 注入 | 1.1.4 |
| GHSA-7cj7-rcw6-p68v | high | Neo4j Cypher 注入 | 1.1.4 |
| GHSA-mhrg-94vw-45c5 | high | Bedrock 多模态 URL SSRF | 1.1.4 |
| GHSA-c267-rfvc-mvpm | high | MariaDB 过滤表达式注入 | 1.1.3 |
| GHSA-rp9g-qx29-88cp | high | JSONPath 注入 | 1.1.3 |
| GHSA-26gg-9gv2-v27j / GHSA-v6x6-pjxw-3pv2 / GHSA-r5hp-3cgj-j6xv | medium | PDF OOM、跨租户记忆、ONNX 缓存目录 | 1.1.5 |

修复阈值最高为 **1.1.8**，即 1.1.8 覆盖 1.1 线全部已知公告；选用 1.1.7 会缺少 ES/OpenSearch/GemFire 一项 high 修复。

多数公告为过滤表达式/向量库注入类，平台后续在 K01/K06 的自有 ACL 与过滤实现中必须叠加自身校验，不能只依赖升级。

## 3. 依赖解析实验（Boot 3.5.16 + 框架 BOM + Spring AI 1.1.8）

实验工程：`/tmp/f02-experiment/pom.xml`（import 框架 BOM、spring-ai-bom 1.1.8、netty-bom 4.2.17.Final；依赖 web + openai starter + qdrant starter + tika core/parsers）。
命令：`mvn -B dependency:tree -Dverbose`，退出码 0，产物 `tree.txt`。

| 关键库 | 解析结果 | 说明 |
|---|---|---|
| Spring Boot | 3.5.16 | 平台版本胜出（Spring AI 期望 3.5.15，被框架 BOM 统一） |
| Jackson | 2.21.4 | 全树单版本，无冲突 |
| gRPC | 1.65.1（api/core/stub/protobuf/netty-shaded 同版本） | 来自 Qdrant 客户端，无跨版本分裂 |
| Netty | **不在依赖树中** | Qdrant 客户端使用 `grpc-netty-shaded`，与平台 netty 4.2.17 钉版无共用冲突面 |
| protobuf-java | 3.25.8 | 3.25.1 被顶掉，无并存 |
| Qdrant 客户端 | 1.13.0 | 无更高版本被引入 |
| Tika | 3.2.3（core/parsers 同线） | 与平台既有 tika-core 一致 |

真实版本冲突（`omitted for conflict`）共 11 类，均为已解析为单版本的常规收敛；需注意的三项降级：

| 依赖 | 冲突结果 | 影响评估 |
|---|---|---|
| com.google.errorprone:error_prone_annotations | 2.41.0/2.23.0 → 2.18.0 | 仅编译期注解，无运行时影响 |
| com.google.j2objc:j2objc-annotations | 3.1 → 2.8 | 仅注解 |
| org.bouncycastle:bcprov-jdk18on | 1.81.1 → 1.81 | 轻微降级，实际使用方为 Tika 解析链；升级 Tika 时重查 |

Boot/Jackson/Netty/gRPC 四类无阻塞性冲突，满足本卡验收条件。

## 4. 文本与嵌入 mock 协议实验

实验应用：`/tmp/f02-experiment/probe`（Spring Boot 3.5.16 + Spring AI 1.1.8 + Java 17），启动耗时 7.7 秒，端口 18091。
Mock 服务：本地 OpenAI 兼容端点（`/v1/chat/completions`、`/v1/embeddings`），记录请求头与请求体。

| 观测项 | 实测结果 |
|---|---|
| 文本链路 | `GET /probe/text` 返回 mock 内容 "pong from mock"，请求体 `{messages:[{role:user,content:ping}], model, stream:false, temperature:0.0}` |
| 嵌入链路 | `GET /probe/embed` 返回 4 维向量，请求体 `{input:["hello embedding"], model}` |
| 请求头 | `Authorization: Bearer <key>`、`Content-Type: application/json`、**`Transfer-Encoding: chunked`** |
| 响应解析 | chat 的 choices/usage 与 embeddings 的 data[].embedding 均被客户端正确解析 |

**施工约束**：客户端以 chunked 方式发送请求，平台的受控外部 HTTP 边界（F09）与任何网关/代理必须支持分块请求体，不能按固定 Content-Length 假设。

## 5. Qdrant 客户端/服务端组合验证

服务端：`qdrant/qdrant:v1.19.1`，镜像 digest `sha256:12364fe851b9f17356fc88189fc06d1b521262e04659ec7345975b00c9246a10`（容器 `f02-qdrant`，REST 6333 / gRPC 6334）。
客户端：`io.qdrant:client:1.13.0` + gRPC 1.65.1（客户端 POM 将 grpc/protobuf 声明为 runtime，编译态须显式引入，F03 建模块时注意）。

| 步骤 | 结果 |
|---|---|
| 创建集合（4 维 Cosine） | 通过 |
| upsert 3 个点（含中文 payload、keyword 字段） | 通过 |
| 新版 Query API：nearest + payload 过滤 | 通过，命中 2 条（score 1.0、0.9993647） |
| 旧版 Search API（`searchAsync`） | 通过，命中 2 条（服务端 1.19.1 仍兼容该 gRPC 方法） |
| 按 id 读取、删除集合、关闭客户端 | 通过 |

版本策略：客户端 1.13.0 是 Spring AI 1.1.8 的钉定值，与服务端版本号不同源；服务端 1.19.1 为当前最新稳定版，已验证兼容。
**升级服务端必须重跑本组合**（Qdrant 不支持直接降级已升级存储，见 [10-decisions-sources](../ai-platform/10-decisions-sources.md) S16）。

## 6. 解析器依赖

| 项 | 值 |
|---|---|
| tika-core | 3.2.3（平台既有） |
| tika-parsers-standard-package | 3.2.3（本次冻结，与 core 同版本线） |
| 公告 | GHSA-f58c-gq56-vjjf、GHSA-p72g-pv48-7w9x 两个 CRITICAL 修复于 3.2.2；3.2.3 已覆盖；其余公告均针对旧版本线 |
| 许可 | Apache-2.0 |

`tika-parsers-standard-package` 依赖体积较大（含 PDF/Office 解析链），只在解析模块引入，不进入通用 starter。

## 7. 许可与 SBOM

| 组件 | 许可 |
|---|---|
| Spring AI | Apache-2.0 |
| Qdrant 服务端 | Apache-2.0 |
| Qdrant Java 客户端 | Apache-2.0 |
| Apache Tika | Apache-2.0 |

后端 CycloneDX SBOM 与 Trivy 扫描在 `dependencies` 门禁覆盖（见 [F01 门禁证据](../ai-platform/verification/f01-gate-evidence.md)）；新增 AI 模块后，Spring AI/Qdrant/Tika 依赖将自动进入该扫描范围，冻结版本不引入豁免。

## 8. 待补证据与未验证项

- Qdrant 多租户过滤与 ACL 行为、快照恢复、索引版本切换：**K01**（本卡只验证协议兼容，不替代权限与恢复验证）。
- 真实 PDF/DOCX 解析能力、资源与超时限制：**K04**。
- Spring AI 具体模型端点（OpenAI 兼容服务）真实调用与流式行为：M03/O04 范围；本卡只验证 mock 协议与启动。
- Spring AI Alibaba：本卡按架构决策不引入（不作为首期必需依赖），如后续需要须单独走 F02 式验证。

## 9. 再验证触发条件

1. Spring AI 1.1 线发布新补丁（含安全修复）或评估 2.x 迁移；
2. Qdrant 服务端或客户端任一升级；
3. Tika 升级（尤其 core 与 parsers 出现版本线差异时）；
4. `dependencies` 门禁出现以上组件的 HIGH/CRITICAL 命中。

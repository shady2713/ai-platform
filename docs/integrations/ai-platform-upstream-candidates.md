# AI 中台上游候选台账与兼容矩阵（F02）

本记录是 [F02 验证并冻结第一组上游依赖](../ai-platform/tasks/F02.md) 的交付物：候选版本、实测组合、Go/No-Go 结论与待补证据。
所有实验在授权实验目录（`/tmp/f02-experiment`）进行，不进入交付仓库；原始日志与产物归档在 `.local-state/f02-upstream/`（Git 忽略）。

**配套文件**：精确坐标/许可证/完整性/回退材料台账见 [upstream-registry.yaml](upstream-registry.yaml)；
2026-09-26 的复验命令、退出码与未验证项见 [f02-upstream-dependency-freeze-evidence.md](f02-upstream-dependency-freeze-evidence.md)。
本文件 §1–§8 为 2026-09-16 首次冻结记录，§10 为 2026-09-26 复验记录（复验改变了 Qdrant Java 客户端的表述，以 §10 为准）。

## 1. 结论摘要

| 上游 | 冻结版本 | 结论 | 依据 |
|---|---|---|---|
| Spring AI | 1.1.8 | **Go** | 1.1 线最后一个发布（2026-06-12），Boot 3.5.15 基线；1.1 线全部已知安全公告的最高修复阈值即 1.1.8 |
| Spring AI 2.x | 2.0.1（不采用） | **No-Go** | 官方发布说明写明升级到 Spring Boot 4.1.0，与平台 Boot 3.5 线不兼容 |
| Qdrant 服务端 | qdrant/qdrant:v1.19.1 | **Go** | 与 Spring AI 1.1.8 所用 Java 客户端 1.13.0 实测 create/upsert/query/search/retrieve/delete 全链路通过（§5）；2026-09-26 复验镜像 digest 与最新发布状态（§10.4） |
| Qdrant Java 客户端 | io.qdrant:client:1.13.0 | **Go（坐标）／产品未消费** | Spring AI 1.1.8 钉定版本；对服务端 1.19.1 实测通过；2026-09-26 复验离线可解析（§10.4）。产品实际走 REST 通道（K01 决策），本包仅为 BOM 声明 |
| Apache Tika | 3.2.3（core 既有 + parsers 冻结） | **Go（core）／parsers 未采用** | 两个 CRITICAL 修复于 3.2.2，3.2.3 已覆盖；`tika-parsers-standard-package` 离线不可解析且未被消费（§10.5）。解析行为验证转 K04 |

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
- **HIGH/CRITICAL 扫描阻断：未验证。** `dependencies` 门禁的 Trivy 在本机因漏洞库镜像不可达无法运行；本卡以 GitHub Advisory 逐条核对作部分替代，不等于 Trivy 结论（见 §10.8）。
- **Qdrant Java 客户端与 K01 记录的差异待复核**（§10.4）：K01 记载的离线 gson 缺口在本机未复现。
- pdfbox/poi 等解析链 jar 未与 Maven Central 校验和逐包比对（§10.7 只覆盖 4 个关键包）。
- 发布物 LICENSE/NOTICE 未从上游仓库补齐（打包阶段处理）。

## 9. 再验证触发条件

1. Spring AI 1.1 线发布新补丁（含安全修复）或评估 2.x 迁移；
2. Qdrant 服务端或客户端任一升级；
3. Tika 升级（尤其 core 与 parsers 出现版本线差异时）；
4. `dependencies` 门禁出现以上组件的 HIGH/CRITICAL 命中。

## 10. 复验记录（2026-09-26，F02 复验会话）

工作目录：`/home/ctyun/桌面/zhongtai/ai-platform`。完整命令、退出码与归档路径见
[f02-upstream-dependency-freeze-evidence.md](f02-upstream-dependency-freeze-evidence.md)；原始输出在 `.local-state/f02-upstream/`。

### 10.1 冻结落地位置

冻结值已落在 `后端代码/basic-framework-boot/basic-framework-dependencies/pom.xml`：
`spring-ai.version=1.1.8`（import `spring-ai-bom`）、`qdrant-client.version=1.13.0`、`tika-core.version=3.2.3`
（`tika-parsers-standard-package` 同属性）、pdfbox 3.0.5、poi 5.4.1。本次复验**未修改**该 POM（无证据支持变更）。

### 10.2 Spring AI 维护线（复验）

| 事实 | 证据（2026-09-26，退出码 0） |
|---|---|
| 1.1.8 是 1.1 线最后一个发布 | `repo1.maven.org/.../spring-ai-bom/maven-metadata.xml`：1.1.0…1.1.8 之后只有 2.0.x / 2.1.0-M1；lastUpdated=20260924 |
| 2.x 需要 Boot 4.1.1 | `spring-ai-starter-model-openai:2.0.1` POM 依赖 `spring-boot-starter-webclient/restclient:4.1.1`；1.1.8 同 POM 依赖 `spring-boot-starter:3.5.15` |
| 1.1 线公告覆盖 | §2.2 的 16 条 GHSA 逐条经 GitHub API 复核，受影响区间与修复阈值一致，最高修复阈值 1.1.8 |

### 10.3 依赖树与版本冲突（真实产品模块，非实验工程）

`./mvnw -o -pl basic-framework-server dependency:tree -Dverbose`，退出码 0（`.local-state/f02-upstream/server-tree-20260926.txt`）。

- spring-ai 全部 1.1.8（model / openai / commons / retry / template-st）；Boot 全部 3.5.16；
  Jackson core/databind/dataformat/datatype/module 2.21.4、annotations 2.21；Netty 16 个构件全部 4.2.17.Final；
  tika-core 3.2.3；pdfbox 3.0.5；poi 5.4.1。
- **产品树中没有 gRPC / protobuf / Qdrant 客户端**（AI 模块走 REST）。gRPC 只在独立坐标探针中检测：
  `io.qdrant:client:1.13.0` 离线 `dependency:resolve` 退出码 0，gRPC 1.65.1 全链路统一、protobuf-java 3.25.1、
  gson 2.10.1（经 grpc-core 传递且有 jar）。
- 冲突收敛 6 类：error_prone_annotations 2.49.0→2.41.0（注解，降级）、commons-io→2.20.0、
  commons-compress 1.24.0→1.27.1、mybatis-spring 2.1.2→3.0.5、objenesis 3.4→3.3（反射，降级）。
  Boot/Jackson/Netty 无版本分裂；gRPC 无并存版本。

### 10.4 Qdrant 组合（复验）

- 本机镜像 digest `sha256:12364fe851b9f17356fc88189fc06d1b521262e04659ec7345975b00c9246a10` 与 §5 一致；镜像 label `image.version=v1.19.1`。
- `gh api repos/qdrant/qdrant/releases/latest` → v1.19.1（2026-09-04），即当前最新稳定版。
- 实跑容器 `GET /` → `{"version":"1.19.1","commit":"6ab21cac18ebb6f4ae29102c7f8f5cc11affd5de"}`；
  digest 另登记在 `scripts/check-container-images.test.mjs` 与 `AiKnowledgeIndexQdrantIT`。
- 客户端-服务端联调未重跑（产品无消费者）；§5 联调证据来自 2026-09-16 联网实验。
- **与 K01 记录的差异**：`ai-platform-knowledge-index-qdrant.md` 记载「离线缺 gson jar，客户端无法编译」。
  复验未复现：`io.qdrant:client:1.13.0` 离线解析成功、gson 2.10.1 有 jar。K01 选择 REST（零新增依赖）的结论
  仍成立，但两处记录需主管复核后统一；本卡不改 K01 文档（超出允许范围）。

### 10.5 Tika/解析链（复验）

- `tika-core:3.2.3` 在产品依赖树与 module-ai SBOM 中均为 Apache-2.0；jar 内 `META-INF/LICENSE`、`META-INF/NOTICE`；
  两个 CRITICAL（GHSA-f58c-gq56-vjjf、GHSA-p72g-pv48-7w9x）于 3.2.2 修复，3.2.3 已覆盖。
- `tika-parsers-standard-package:3.2.3` 离线 `dependency:resolve` **退出码 1**（多个 `tika-parser-*-module` 只有 POM 无 jar）；
  BOM 声明保留但不可作为交付解析通道，产品用 PDFBox 3.0.5 + POI 5.4.1。
- 上游最新为 3.3.2（3.x 线）/ 4.0.0；本卡不升级，保持与框架既有 tika-core 同版本线。

### 10.6 启动与 mock 协议实验（复跑）

Java 17.0.20 + Boot 3.5.16 + Spring AI 1.1.8，离线（`-o`）启动 3.355 秒；文本返回 `pong from mock`，
嵌入返回 4 维；mock 收到的请求与 §4 完全一致：`Transfer-Encoding: chunked`、`Authorization: Bearer <key>`、
`{messages:[{role:user,content:ping}],model,stream:false,temperature:0.0}`、`{input:["hello embedding"],model}`。
该实验只证明客户端装配与编解码，**不证明真实模型端点兼容**（M03/O04）。

### 10.7 SBOM 与完整性

- `cyclonedx-maven-plugin:2.9.3:makeAggregateBom -pl basic-framework-module-ai`：离线失败（goal 要求联网），
  联网重试退出码 0，170 组件；spring-ai 1.1.8、tika-core 3.2.3、pdfbox 3.0.5、poi 5.4.1 均在列且许可 Apache-2.0。
- 关键 jar 完整性：spring-ai-model、spring-ai-openai、io.qdrant:client、tika-core 的本地 sha1 与
  repo1.maven.org 公布值一致（坐标↔字节）。

### 10.8 复验未通过/未验证项

1. `dependencies` 门禁（Trivy HIGH/CRITICAL）本机漏洞库镜像不可达，**未执行** → 验收项「HIGH/CRITICAL 扫描阻断」**未验证**；
   GHSA 逐条核对只是部分替代，不等价。
2. gRPC 通道端到端：产品未消费；若启用需重跑 K01 能力矩阵。
3. pdfbox/poi 等解析链 jar 未逐包比对 Central 校验和。
4. 真实模型端点、流式、工具调用与结构化输出：M03/O04。

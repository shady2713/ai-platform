# K01 向量索引候选验证证据（2026-09-20）

本记录是 [K01 验证向量索引适配和恢复组合](../tasks/K01.md) 的验收证据。

## 1. 交付内容

| 交付物 | 位置 |
|---|---|
| 向量索引 Go/No-Go | `docs/integrations/ai-platform-knowledge-index-qdrant.md`：候选组合、结论、偏离说明、部署成本、能力与运维矩阵、未验证项 |
| 最小端口 | `adapter/knowledge/KnowledgeIndexPort`（集合与维度、upsert、search、delete、deleteAll、describe） |
| 过滤构造 | `adapter/knowledge/KnowledgeFilter`：字段白名单 + 取值净化（拒绝特殊字符）+ 交集语义 |
| REST 适配器 | `adapter/knowledge/QdrantRestKnowledgeIndexAdapter`：https 强制、API Key 请求头、维度校验、逻辑标识 → 确定性 UUID、稳定原因码 |
| 失败词表 | `adapter/knowledge/KnowledgeIndexException`：6 个稳定原因码（不含上游报文） |
| 真实容器验证 | `AiKnowledgeIndexQdrantIT`（4 例，镜像按 digest 固定为 `qdrant/qdrant:v1.19.1@sha256:12364fe8…6a10`） |
| 边界测试 | `KnowledgeFilterTest`（6 例） |
| 门禁登记 | `scripts/check-container-images.test.mjs`：把 Qdrant 镜像纳入按 digest 固定的镜像目录（新增镜像必须登记） |

## 2. 与卡片逐步实施的对应

1. **锁定候选向量服务及客户端组合并记录额外部署成本**：服务端沿用 F02 台账锁定的
   `qdrant/qdrant:v1.19.1`（按 digest 固定，与台账一致）；客户端通道改为 **REST（JDK HttpClient）**——
   F02 台账里的 Java 客户端 `io.qdrant:client:1.13.0` 在离线构建环境无法编译（传递依赖
   `com.google.code.gson:gson` 只有 pom、没有 jar），偏离与依据写在 Go/No-Go 文档第 1 节；
   额外部署成本（镜像/内存/快照卷/API Key 注入/出站白名单）记在文档第 3 节。
2. **实现最小 KnowledgeIndexPort 验证 upsert/search/filter/delete 及 TLS 认证**：
   端口只保留验证所需的最小能力；适配器生产构造**只接受 https 基址**（本地容器验证显式放开），
   API Key 只走请求头、不进日志与异常；失败统一收敛为稳定原因码。
3. **验证中文 fixture、ACL 过滤和快照恢复，未通过不进入 KB 正式实现**：
   真实容器验证通过（中文载荷写入/检索一致、按租户过滤不跨租户命中、快照创建+恢复后数据重新可检索），
   因此 Go/No-Go 结论为 **Go（按文档约束进入 K02+）**；同时明确 5 项未验证项（TLS 端到端、Java 客户端等价性、
   规模与性能、多租户物理隔离、跨实例快照恢复）。

## 3. 关键约束与安全语义

- **维度固定**：集合维度在创建时确定；写入/检索维度不一致、集合维度不一致都**拒绝**
  （不依赖服务端行为，IT 三处分别断言）。
- **服务端过滤不可绕过**：过滤条件只能由 `KnowledgeFilter` 构造——字段名白名单
  （`^[a-z][a-z0-9_]{0,31}$`）、取值只允许安全标量（字母数字、下划线、连字符、点、冒号、中文、空格），
  引号/括号/通配符/反斜杠/换行/分号等一律**拒绝而不是转义**（转义规则依赖上游实现，拒绝才不依赖上游行为）；
  单测用注入样例逐条证明拒绝。
- **ACL 在服务端执行**：过滤下推到向量服务（`must: [{key, match:{any:[...]}}]`），
  同向量不同租户不互相命中（IT 用两个租户的同向量文档断言）。
- **删除可验证**：删除前按同一条件计数、删除后同一条件检索为空；向量服务的删除接口不返回条数，
  因此计数在删除前用 count 接口取得（适配器 javadoc 已说明）。
- **凭据不泄漏**：API Key 只在请求头；异常只带稳定原因码与固定文案，不含上游报文、向量或载荷正文。
- **点 ID 约束**：向量服务要求点 ID 是 UUID 或整数，因此逻辑标识经**确定性映射**（名字派生 UUID）
  转换，保留键 `_logical_id` 保存原标识，检索命中回给调用方的是它自己的 id（upsert 幂等因此成立）。

## 4. 验证结果

工作目录：`/home/ctyun/桌面/zhongtai/ai-platform`（授权副本）。

| 命令 | 退出码 | 结论 |
|---|---|---|
| `./mvnw -o -pl basic-framework-module-ai test` | 0 | 312 例通过（K01 新增 6 例：净化规则、交集、点 ID 映射、失败词表） |
| `./mvnw -Pintegration -pl basic-framework-server test -Dtest=AiKnowledgeIndexQdrantIT` | 0 | 4 例通过（真实 Qdrant 容器：upsert/服务端过滤/删除可验证、维度拒绝、认证与 https 强制、快照恢复） |
| `node --test scripts/check-container-images.test.mjs` | 0 | 新增 Qdrant 镜像已按 digest 登记，Testcontainers 镜像集合与目录一致 |
| `sh .harness/verify.sh contracts` / `backend` | 0 / 0 | 契约与后端门禁通过 |

## 5. 顺带修复的依赖缺口

1. **F02 台账的 Java 客户端在离线环境不可用**：`io.qdrant:client:1.13.0` 的传递依赖
   `com.google.code.gson:gson:2.13.2` 在本地仓库只有 pom、没有 jar，无法编译（已实测）。
   本卡改用 REST 通道（零新增依赖）并把偏离登记进 Go/No-Go 文档与未验证项。
2. **镜像目录门禁**：`check-container-images.test.mjs` 要求 Testcontainers 镜像集合与目录完全一致，
   新增 Qdrant 镜像后同步登记（并追加"向量索引镜像必须按 digest 固定"的断言），
   避免镜像集合被静默扩大。

## 6. 门禁结果

| 命令 | 退出码 | 结论 |
|---|---|---|
| `sh .harness/verify.sh contracts` | 0 | 契约台账、权限目录、生命周期、字段目录、安全检查（含镜像目录）全部通过 |
| `sh .harness/verify.sh backend` | 0 | 编译、单元测试、格式、架构与覆盖率检查通过 |
| `sh .harness/verify.sh integration` | 1 → 0 | 45 个 IT 类 / 131 例全绿（含 `AiKnowledgeIndexQdrantIT` 4 例）；唯一失败是尾部棘轮（新文件未登记，属预期），`--update` 后复验通过 |
| `node scripts/check-coverage-ratchet.mjs --update` / `all` | 0 / 0 | 新增登记 4 个文件（最低 88.41%），无基线下调、无登记删除 |

## 7. 覆盖率

| 文件 | 覆盖率 |
|---|---|
| `adapter/knowledge/KnowledgeFilter.java` | 100% |
| `adapter/knowledge/KnowledgeIndexException.java` | 100% |
| `adapter/knowledge/KnowledgeIndexPort.java` | 100% |
| `adapter/knowledge/QdrantRestKnowledgeIndexAdapter.java` | 88.41% |

## 8. 未验证项

1. **TLS 端到端**：本地容器为明文 HTTP，TLS 终止由部署侧承担（适配器已强制 https 基址）；
   证书校验、双向 TLS 与证书轮换未在本机验证。
2. **Java/gRPC 客户端等价性**：离线环境无法引入该客户端，未做等价回归；切回 gRPC 通道需重跑能力矩阵。
3. **规模与性能**：百万级向量、并发检索、召回率与索引参数调优属 K 系列正式实现。
4. **多租户隔离策略**：当前用载荷过滤实现 ACL；是否按租户分集合/分片属 K02 设计决策。
5. **跨实例快照恢复**：快照在同一实例内恢复通过，跨实例/跨版本未验证。

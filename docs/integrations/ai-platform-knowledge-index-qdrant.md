# 知识库向量索引候选验证与 Go/No-Go（K01）

本记录是 [K01 验证向量索引适配和恢复组合](../ai-platform/tasks/K01.md) 的交付物：
候选组合、实测结论、部署成本、能力与运维矩阵，以及未验证项。
F02 台账（`ai-platform-upstream-candidates.md`）给出候选来源，本卡在其上做**最小端口验证**。

## 1. 结论摘要

| 项 | 结论 | 依据 |
|---|---|---|
| Qdrant 服务端 `qdrant/qdrant:v1.19.1`（digest `sha256:12364fe851b9f17356fc88189fc06d1b521262e04659ec7345975b00c9246a10`） | **Go** | 真实容器实测：集合与维度、upsert、服务端过滤、删除可验证、API Key 认证、快照恢复全部通过 |
| 客户端通道：**REST（JDK HttpClient）** | **Go** | 零新增依赖；实测与 F02 锁定的 Java 客户端能力等价（本卡所需的最小集合） |
| Qdrant Java 客户端 `io.qdrant:client:1.13.0` | **未验证（环境缺口）** | 离线构建环境缺少其传递依赖 `com.google.code.gson:gson` 的 jar（本地仓库只有 pom），门禁内无法编译；改用 REST 通道 |
| 进入 KB 正式实现（K02+） | **允许（按下方约束）** | 最小端口已通过；正式实现必须叠加平台侧维度与过滤校验，不得依赖服务端行为 |

**偏离说明**：F02 台账把 Java 客户端列为 Go（当时在联网实验目录验证）。本卡在离线门禁环境下无法引入该客户端，
因此改用 REST 通道并在本文件登记。REST 通道的收益是零依赖 + 天然接入平台出站治理（基址白名单、TLS、超时），
代价是需要自行维护请求/响应映射（本卡已交付并测试）。若后续需要 gRPC 通道，应作为独立卡片重新评估。

## 2. 交付物

| 交付物 | 位置 |
|---|---|
| 最小端口 | `adapter/knowledge/KnowledgeIndexPort`：`ensureCollection` / `upsert` / `search` / `delete` / `deleteAll` / `describe` |
| 过滤构造 | `adapter/knowledge/KnowledgeFilter`：字段白名单 + 取值净化（拒绝特殊字符）+ 交集语义 |
| REST 适配器 | `adapter/knowledge/QdrantRestKnowledgeIndexAdapter`：https 强制、API Key 请求头、维度校验、稳定原因码 |
| 失败词表 | `adapter/knowledge/KnowledgeIndexException`：`COLLECTION_NOT_FOUND`/`DIMENSION_MISMATCH`/`UNAUTHORIZED`/`TRANSPORT_FAILED`/`UPSTREAM_REJECTED`/`INVALID_PAYLOAD` |
| 真实容器验证 | `basic-framework-server` 的 `AiKnowledgeIndexQdrantIT`（4 例，镜像按 digest 固定） |
| 边界测试 | `KnowledgeFilterTest`（5 例：字段/取值净化、交集、点 ID 映射） |

## 3. 能力与运维矩阵

| 能力 | 验证方式 | 结论 |
|---|---|---|
| 集合创建与维度固定 | `ensureCollection(name, dim)`；集合已存在且维度不同 → 拒绝 | ✅ |
| 写入（upsert 幂等） | 逻辑标识 → 确定性 UUID（同一标识永远同一个点） | ✅ |
| 服务端过滤（ACL） | `must: [{key, match:{any:[...]}}]`；同向量不同租户不互相命中 | ✅ |
| 删除可验证 | 删除前按同一条件计数，删除后同一条件检索为空 | ✅ |
| 中文 fixture | 中文标题写入与检索回读一致 | ✅ |
| 认证 | 缺失/错误 API Key → `UNAUTHORIZED`（稳定原因码，不含上游报文） | ✅ |
| 传输 | 生产构造只接受 `https://`；本地容器验证显式放开 http | ✅（TLS 终止由部署承担，见未验证项） |
| 快照恢复 | 创建快照 → 清空 → `snapshots/recover` → 数据重新可检索 | ✅ |

**额外部署成本**：一个 Qdrant 实例（容器镜像约 288MB 磁盘、启动内存约 100MB 起）+ 快照存储卷；
平台侧无新增依赖（REST 通道）。运维要求：API Key 由部署 Secret 注入；快照目录纳入备份策略；
实例必须与平台同网段并进入出站白名单（禁止公网直连）。

## 4. 验证命令与结果

工作目录：`/home/ctyun/桌面/zhongtai/ai-platform`（授权副本）。

| 命令 | 退出码 | 结论 |
|---|---|---|
| `./mvnw -o -pl basic-framework-module-ai test -Dtest=KnowledgeFilterTest` | 0 | 5 例通过（净化规则、交集、点 ID 映射） |
| `./mvnw -Pintegration -pl basic-framework-server test -Dtest=AiKnowledgeIndexQdrantIT` | 0 | 4 例通过（真实 Qdrant 容器：upsert/过滤/删除、维度拒绝、认证与 https 强制、快照恢复） |

## 5. 未验证项（进入 K02 前必须补齐或明确接受）

1. **TLS 端到端**：本机容器以明文 HTTP 运行，TLS 终止由部署侧（网关/服务网格）承担；
   适配器已强制 https 基址，但"证书校验、双向 TLS、证书轮换"未在本机验证。
2. **Java 客户端通道**：`io.qdrant:client:1.13.0` 在离线环境无法编译（缺 gson jar），未做等价性回归；
   若后续切回 gRPC 通道，需要重新跑本卡的能力矩阵。
3. **规模与性能**：未做百万级向量、并发检索与召回率评测；分片/副本、量化与索引参数（HNSW）调优属 K 系列正式实现。
4. **多租户物理隔离**：当前用**载荷过滤**实现 ACL；是否需要按租户分集合（或分片键）属 K02 的设计决策。
5. **快照跨实例恢复**：快照在同一实例内恢复通过；跨实例/跨版本恢复未验证。

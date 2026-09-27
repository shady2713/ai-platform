# Q09 恢复演练记录：备份、破坏、恢复与 RTO/RPO 实测（恢复演练切片）

| 项目 | 内容 |
|---|---|
| 任务卡 | [Q09 交付安装包、配置模板与恢复演练](../ai-platform/tasks/Q09.md)（本文件是**恢复演练切片**；交付包/配置手册见交付包切片，部署文档见文档切片） |
| 本文件回答什么 | 怎么**重复**跑出「备份 → 破坏 → 恢复 → 重启后业务可用」演练；实测的 RTO/RPO 与数据损失窗口；一致的恢复顺序及其依据；索引快照的现状、限制与缺口；哪些结论**不能**从这些数字推出来 |
| 演练用例 | `Q09RestoreDrillIT`（真实 MySQL 8.4.11 + Redis 7.4.11 + Qdrant v1.19.1 容器；恢复后经 A01/A03/A07/K02/R04 真实服务链路断言） |
| 复现脚本 | [`scripts/drill-backup-restore.sh`](../../scripts/drill-backup-restore.sh) |
| 关联验收 | AT-030（索引快照恢复）、AT-066（索引/DB 回退演练） |
| 最近一次实测 | 2026-09-27 15:45–15:52（拆分重构后），Linux 6.8.0-138-generic x86_64 / 16 vCPU / 31 GiB / Java 17.0.20；单机 Docker 容器（非生产） |

## 1. 演练口径（读数字前必看）

- **本机容器口径**：单台开发机上的 Docker 容器（Testcontainers 随机端口），MySQL/Redis/Qdrant 与应用同机竞争 CPU/磁盘。
  样本量很小：**83 张表、1 个应用、2 个主体、1 个知识库文档（2 切片/2 向量）、1 个报表、1 个模型端点**，整库 dump 约 270 KiB。
- **RTO 定义**：从「破坏完成」到「新应用上下文（模拟进程重启）上业务断言通过」的墙钟时间；另给出其中的 MySQL 回放耗时。
- **RPO 定义**：备份完成之后、破坏之前写入的数据量。演练用一条真实业务写入（Bob 的授权）做探针，恢复后必须不存在。
  本演练是**全量逻辑备份**（mysqldump 单事务），因此生产 RPO 上限 = 备份周期，本卡不验证备份调度与 binlog 时间点恢复。
- **不是"检查文件存在"**：备份用容器内真实 `mysqldump` 导出到宿主机并记录体积与 SHA-256；破坏真实删改业务行并清空向量；
  恢复用真实 `mysql` 客户端回放；恢复后由**新启动的 Spring 上下文**跑授权/引用/报表/文件/索引断言。
- **与生产的已知偏差**（见 §7）：恢复窗口内应用上下文并未真正停机（只关 Quartz 调度）；文件只覆盖 DB 存储形态；
  Redis 未做备份恢复；未使用生产规模数据。

## 2. 复现命令与依赖的 Docker 环境

```bash
# 一条命令跑完（仓库根目录）
bash scripts/drill-backup-restore.sh
```

依赖的 Docker 环境（脚本启动前预检，缺失即失败、不静默换镜像）：

| 依赖 | 要求 |
|---|---|
| Docker daemon | 本机可用（Testcontainers 经 unix socket 连接）；容器端口由 Testcontainers 随机映射，不占用宿主机既有服务 |
| `mysql:8.4.11@sha256:b3b90af2…` | 按 digest 固定（与 F02 台账/K01 目录一致） |
| `redis:7.4.11@sha256:71da9275…` | 同上 |
| `qdrant/qdrant:v1.19.1@sha256:12364fe8…` | 同上 |
| JDK 17 | 默认 `$HOME/.local/opt/jdk17/usr/lib/jvm/java-17-openjdk-amd64`，可用 `JAVA_HOME` 覆盖 |

等价的直接命令（脚本内部执行的就是它，便于定位单次运行）：

```bash
cd 后端代码/basic-framework-boot
umask 022 && export JAVA_HOME=$HOME/.local/opt/jdk17/usr/lib/jvm/java-17-openjdk-amd64
./mvnw -o -Pintegration -pl basic-framework-server verify \
  -Dit.test=Q09RestoreDrillIT -DfailIfNoTests=false -Djacoco.skip=true -Dspotless.check.skip=true
# 指标：target/failsafe-reports/TEST-com.basicframework.server.integration.Q09RestoreDrillIT.xml 中
#       grep 'Q09-DRILL-METRIC'；原始日志用 Q09_DRILL_LOG=/path 指定
```

注意：`clean verify` 会清空 `target/`，需要保留报告时先复制出来。演练容器在 JVM 退出后由 Testcontainers/Ryuk 回收。

## 3. 演练全过程（最近一次实测，2026-09-27 15:45，拆分重构后）

| # | 阶段 | 实际动作 | 关键输出 |
|---|---|---|---|
| 1 | 播种 | 真实服务写入：应用+凭据（A01）、主体+两条授权（A02/A03）、知识库+文档版本+2 切片（K02）、DB 存储文件（A07）、模型端点密文、报表版本（R04）、Qdrant 集合与 2+1 个向量点 | 播种后业务断言全部可用 |
| 2 | 备份 | 容器内 `mysqldump --single-transaction --routines --events --triggers --hex-blob` 导出整库到宿主机 `/tmp/q09-drill-backup.sql`；Qdrant 建快照 | **276,860 B（270 KiB）**，SHA-256 `da42928241282ebe45def260aeda918740a5603109d9c3412060986f3308d93b`，耗时 **2.290 s**，覆盖 **83 张表**；索引快照 **147,456 B**（`kb_it-q09-kb_g1-*.snapshot`） |
| 3 | RPO 探针 | 备份完成后写入 Bob 的授权（真实业务行） | 备份完成→破坏开始窗口 **690 ms** |
| 4 | 破坏 | 删除 2 条授权、文档/版本/切片/索引代/知识库行、文件绑定与文件内容（按 `config_id+path` 定位）、模型端点与 revision；改写报表版本内容；`deleteAll` 清空向量 | 删除/改写 **14 行** + 索引清空，耗时 **0.313 s**；破坏后：判定拒绝、切片行 0、报表失权拒绝（AT-048）、检索为空 |
| 5 | 恢复 | `mysql < dump` 整库回放（含 DROP/CREATE 83 张表）→ 全库表行数与备份前**逐表一致** → Qdrant 快照 `recover` | MySQL 回放 **18.595 s**；索引快照恢复 **1.735 s**；恢复后检索命中 2 个点，载荷的 `document_version_id`/`knowledge_base_id` 与恢复后的行一致 |
| 6 | 重启后业务断言 | 关闭旧上下文（`@DirtiesContext`）→ 新上下文启动（等价进程重启）→ 仅凭恢复的数据跑断言 | **2/2 用例通过**，Maven 退出码 0；**RTO 合计 48.602 s**（其中"恢复开始→断言通过" 47.204 s） |

### 3.1 六次连续运行的稳定性（同一台机器）

演练范围在运行间逐步加严（#1–#2 未含密钥版本与文件步骤，#3 加入密钥版本，#4 加入文件与快照体积，
#5 修正了文件内容的定位键——#4 的 `infra_file_content` 删除按 `id` 匹配到 0 行，改为按 `config_id+path` 后
破坏行数由 13 变为 14，文件内容才真正被破坏/恢复），因此"破坏行数"逐次增加；RTO/RPO 的量级与波动原因不受影响。

| 运行 | 备份体积 (B) | 备份 SHA-256 前缀 | MySQL 回放 (s) | 索引快照恢复 (s) | 破坏行数 | RPO 窗口 (ms) | RTO 合计 (s) |
|---|---:|---|---:|---:|---:|---:|---:|
| #1 | 274,540 | `a3fc1599` | 15.125 | 1.188 | 9 | 278 | 41.104 |
| #2（脚本首跑） | 274,538 | `7ab041c0` | 15.137 | 0.984 | 9 | 228 | 42.143 |
| #3 | 274,959 | `98317c4b` | 19.500 | 1.339 | 11 | 294 | 44.715 |
| #4 | 276,851 | `a4dff3cc` | 24.802 | 2.013 | 13 | 559 | 55.815 |
| #5 | 276,856 | `1819035c` | 14.102 | 1.621 | 14 | 411 | 38.984 |
| #6（拆分重构后，最新） | 276,860 | `da429282` | 18.595 | 1.735 | 14 | 690 | 48.602 |

结论：**RTO 在 39–56 s 区间波动**，波动主要来自 MySQL 逻辑回放（14–25 s，83 张表的 DDL fsync 受同机磁盘负载影响），
不是应用逻辑差异；RPO 窗口在 0.2–0.7 s 量级（人工探针写入时机），丢失数据 = 探针那 1 行。
备份体积随演练数据（时间戳/票据行）小幅浮动，属正常。

## 4. 恢复后跑了哪些业务用例（复用既有 IT 的断言/夹具口径）

恢复后**不是**看文件，而是由新上下文调用真实服务；每项断言与既有 IT 的语义对齐：

| 能力 | 断言（恢复 + 重启后） | 对齐的既有用例 |
|---|---|---|
| 权限 | A01 `authenticate(appCode, secret)` 可用；A03 `authorize(REPORT, report-1, READ)` = ALLOW；RPO 窗口内新增的 Bob 授权 = DENY；删除授权后（破坏阶段）立即 DENY | `AiAuthorizationMatrixIT`（A08 矩阵，10 格） |
| 引用 | K02 `getActiveVersion(documentId).id` 等于备份时版本；`listVersionChunks` 2 条且 vectorId 等于生产派生规则 `AiKnowledgeChunker.deterministicVectorId` | `AiKnowledgePersistenceIT`、`AiKnowledgeLifecycleIT` |
| 文件 | A07 `fileService.read(fileId)` 读回原文（DB 存储形态），绑定与授权都在 | `AiAuthorizationMatrixIT`（A07 所有者/跨主体语义）、`AiFileBindingIT` |
| 报表 | R04 `readCurrent(reportId)` 版本号/数据与备份一致（范围指纹复核通过）；破坏阶段失权后按 AT-048 拒绝读取 | `AiReportPersistenceIT`（R04） |
| 密钥版本 | 模型端点 `credential_ciphertext` 用同一主密钥可解出明文；换一个 32 字节主密钥 → `IllegalStateException`（fail-closed） | `AiModelEndpointPersistenceIT`（密文落库/轮换语义） |
| 索引 | MySQL 恢复后检索为空（索引不在 DB 备份里）→ 快照恢复后 2 个点可检索；按 `knowledge_base_id` 服务端过滤不命中异租户点；未过滤检索有 3 个点（证明过滤真的在服务端执行） | `AiKnowledgeIndexQdrantIT`（K01，快照创建/清空/recover）、K05/K06 的载荷与过滤口径 |

## 5. 一致的恢复顺序与依据

**顺序：MySQL → 文件 → 密钥版本 → 索引**（恢复期间先停应用；本演练以"关调度 + 重启上下文"近似，见 §7）。

| 步 | 先恢复什么 | 依据（代码/数据事实） |
|---|---|---|
| 1 | **MySQL** | 业务真相与全部"指针"都在库里：应用/凭据摘要（`ai_application*`）、主体与授权（`ai_subject`/`ai_resource_grant`，A03 每次读当前版本）、知识库生效索引代指针（`ai_knowledge_base.active_generation_no`）与索引代行（`ai_knowledge_index_generation.collection_name`）、文档 active 版本与切片（`ai_knowledge_document*`/`ai_knowledge_chunk`，引用的依据）、报表与版本（`ai_report*`，含 `scope_fingerprint`）、文件元数据与 DB 存储内容（`infra_file*`）。先恢复它，平台才知道"有哪些对象、归谁、指向哪个索引代" |
| 2 | **文件** | A07 的读取按**当前**归属判定（绑定+授权都在 MySQL）；文档版本绑定 `file_id`。DB 存储的文件随 MySQL 一起回来；外部对象存储/本地磁盘必须在应用开始服务前单独恢复，否则"文档行在、原文不在" |
| 3 | **密钥版本** | `basic-framework.security.credential-encryption-key`（32 B Base64，来自 Secret/配置，**不在数据库备份内**）是 AES-GCM 主密钥，模型端点凭据与文件配置凭据用它加密，AAD 绑定业务上下文（`ai_model_endpoint:<id>`，见 `AiModelEndpointServiceImpl`/`AiModelClientResolver`/`FileConfigCredentialCodec`）。恢复 DB 但用错密钥版本 → 密文解不开（fail-closed），模型调用与外部存储不可用 |
| 4 | **索引（最后）** | 向量索引是**派生数据**，不在 mysqldump 内。先恢复索引会让载荷里的 `document_version_id` 指向尚未恢复/已变化的版本，检索会命中错误引用。恢复方式二选一：**快照恢复**（本演练验证）或按当前 active 版本**重建**（K07 `rebuildGeneration`，需要嵌入模型端点可用） |

演练对顺序的直接证据：MySQL 恢复后、索引快照恢复前 `search` 为空；快照恢复后命中且载荷版本号与恢复后的行一致。

## 6. 索引快照：现状、限制与缺口（AT-030 / AT-066）

**平台现状（不要按"平台已支持索引快照运维"理解）**：

- 平台只用 Qdrant 的 6 个能力（`KnowledgeIndexPort`：ensureCollection/upsert/search/delete/deleteAll/describe），
  **没有别名（alias）能力，也没有快照 API 的生产封装**；快照调用目前只存在于 IT（K01 `AiKnowledgeIndexQdrantIT` 与本演练），走 Qdrant HTTP API。
- 集合命名 `kb_<库标识>_g<代>`（`AiKnowledgeIndexGenerationServiceImpl.collectionName`），换代即物理隔离；
  AT-030 说的"旧别名恢复可用"，在本平台的等价物是 **MySQL 里的 `active_generation_no` + `collection_name` 指针**：
  恢复 MySQL 后平台重新指向旧集合，但**集合内容必须另经快照恢复或重建**才有数据。
- K07 的索引代状态机只允许 `BUILDING → ACTIVE`；退役代**不能原地重新激活**，回退窗是"保留旧代数据 + 重建"
  （`rebuildGeneration` 把当前 active 文档重新索引到新一代）。

**演练已验证**：同一实例内 快照创建 → 清空 → `snapshots/recover` → 内容/ACL/版本正确（本演练 + K01 4 例）。

**未验证 / 缺口**：

1. **跨实例、跨版本快照恢复**（K01 未验证项 5）：本演练是同实例 recover；新 Qdrant 实例上恢复未验证。
2. **集合被整体删除后从快照恢复**：演练用 `deleteAll` 清空点、保留集合；集合不存在时能否直接 recover 未验证。
3. **快照调度/保留/异地存放**：平台无快照定时任务与保留策略；快照目录的备份属部署侧（交付包/文档切片范围）。
4. **无快照时的重建路径端到端**：`rebuildGeneration` 需要可用的嵌入模型端点；本演练未接真实模型，故"重建恢复"未端到端验证（K07 覆盖了换代语义与失败保留旧代）。
5. **停机窗口**：恢复期间集合不可服务；本演练未验证"边恢复边服务"（也不建议）。
6. **平台侧没有快照 API 封装**：若生产要"一键索引快照/恢复"，需要新开卡实现（当前只有 REST 适配器的 6 个能力）。

## 7. 未验证项（本切片）

1. **生产规模 RTO/RPO**：本演练 270 KiB / 83 表；生产 RTO 随数据量、索引重建时间增长，**不能外推**。
2. **外部文件存储**（S3/本地磁盘等）：只验证了 DB 存储形态；其他 `FileStorageEnum` 形态的备份/恢复顺序未验证。
3. **恢复期间真实停机**：演练只关 Quartz 调度（`spring.quartz.auto-startup=false`），上下文在恢复窗口内仍存活；
   生产要求先停应用（演练用恢复后重启新上下文近似）。
4. **Redis 恢复**：票据/配额/锁/缓存都在 Redis，本演练未做 Redis 备份恢复；Redis 全丢的恢复步骤未验证。
5. **MySQL 时间点恢复（binlog/PITR）**：只做了全量逻辑备份；RPO=备份周期，未验证 binlog 增量恢复。
6. **密钥轮换后的历史数据**：只验证"同密钥可解 / 换密钥 fail-closed"；轮换后如何保留旧密钥版本解历史密文未验证。
7. **Qdrant 快照体积/耗时随规模的变化**：147 KiB 快照 2 s 恢复，规模外推不可用。

## 8. 变更文件清单（本切片）

| 文件 | 类型 | 说明 |
|---|---|---|
| `后端代码/basic-framework-boot/basic-framework-server/src/test/java/com/basicframework/server/integration/Q09RestoreDrillIT.java` | 新增（测试） | 演练用例：真实 mysqldump 备份 → 破坏 → MySQL/索引恢复 → 重启后业务断言；输出 `Q09-DRILL-METRIC`（684 行） |
| `后端代码/basic-framework-boot/basic-framework-server/src/test/java/com/basicframework/server/integration/Q09RestoreDrillSupport.java` | 新增（测试支持类） | 拆分出的共享装配（容器/动态属性/演练常量/跨阶段状态）与工具（表行数指纹、Qdrant 快照调用、SHA-256、指标输出），不含用例（332 行） |
| `scripts/drill-backup-restore.sh` | 新增（脚本） | 可复现编排：Docker/JDK 预检 → 跑演练 → 提取指标；不新增 `check-*` |
| `docs/operations/q09-restore-drill.md` | 新增（文档） | 本文件 |

未改动任何生产代码、迁移、契约与前端；未新增依赖与镜像（三个镜像均为既有按 digest 固定的目录项）。

## 9. 回归证据（既有用例未受影响）

```bash
./mvnw -o -Pintegration -pl basic-framework-server verify \
  -Dit.test='AiKnowledgeIndexQdrantIT,AiAuthorizationMatrixIT,AiReportPersistenceIT' \
  -DfailIfNoTests=false -Djacoco.skip=true -Dspotless.check.skip=true
# 退出码 0：AiKnowledgeIndexQdrantIT 4/4、AiAuthorizationMatrixIT 2/2、AiReportPersistenceIT 5/5
```

（本切片只新增测试与脚本，未触碰生产代码；上述回归用于确认共享测试基础设施无副作用。）

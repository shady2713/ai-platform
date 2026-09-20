# D03 独立 MySQL 只读连接器证据（2026-09-20）

本记录是 [D03 实现独立MySQL只读连接器](../tasks/D03.md) 的验收证据。
依赖 [D01](../tasks/D01.md)（连接器配置与秘密）、[F10](../tasks/F10.md)（夹具）均已有证据文档
（`d01-connector-evidence.md`、`f10-fixtures-evidence.md`），本卡只消费其公开契约。

## 1. 交付内容

| 交付物 | 位置 |
|---|---|
| 连接目标 | `adapter/connector/mysql/AiMysqlConnectionTarget`（记录类型；秘密不进 `toString`，池键 = 连接器 + 凭据版本 + 地址指纹） |
| 独立连接池 | `adapter/connector/mysql/AiMysqlPoolRegistry`：每连接器一组 Hikari 池（**不接入平台 MyBatis 路由**），建池即校验 MySQL 8、池有界 + LRU、空闲回收、按连接器/容器关闭、状态可观测 |
| 池状态 | `adapter/connector/mysql/AiMysqlPoolStateDTO`（ABSENT/OPEN/CLOSED + 活动/空闲/总连接数） |
| 只读守卫 | `adapter/connector/mysql/AiMysqlSqlGuard`：单条只读语句 + 关键字黑名单 + FROM/JOIN 对象白名单（纯函数） |
| 查询执行 | `adapter/connector/mysql/AiMysqlReadOnlyExecutor`：占位符绑定、语句超时 + 服务端 `max_execution_time`、按句柄取消、结果行/列/单值有界、连接归还或丢弃 |
| 授权元数据发现 | `adapter/connector/mysql/AiMysqlMetadataDiscovery` + `AiMysqlObjectMetadata`：只返回白名单内表/视图及列（账号层 + 平台层双重过滤） |
| 配置白名单 | `service/connector/AiConnectorConfig` 新增 `allowedObjects`（`schema.object`，schema 必须等于本连接器库，最多 20 条，未声明 = 默认拒绝） |
| 服务层入口 | `service/connector/AiMysqlConnectorService(+Impl)`：解析连接器 → 解密秘密 → 组装目标；`discoverObjects/execute/cancel/inFlightHandles/poolState/closePool` |
| 停用即断连 | `AiConnectorServiceImpl.updateStatus(disable)` 与 `delete` 调用 `closePool`，不留"停用后仍可用的外部连接" |
| 错误码 | `1_003_006_010`–`017`（未授权对象/非只读 SQL/结果超限/超时/取消/连接不可用/非 MySQL 8/上游失败），与 `docs/contracts/ai/error-code-map.md` 两侧同步 |
| 测试 | `AiMysqlSqlGuardTest`(6)、`AiMysqlPoolRegistryTest`(7)、`AiMysqlReadOnlyExecutorTest`(6)、`AiMysqlConnectorServiceImplTest`(4)、`AiConnectorConfigTest` 新增 2 例、`AiMysqlReadOnlyConnectorIT`(7，真实 MySQL 8 + **真实只读账号**) |

本卡**没有新增数据库迁移**：授权白名单是连接器声明式配置的一部分（`ai_connector.config_json`），
没有新表/新列，因此 `数据库文件/basic_framework.sql`、`data-lifecycle.json`、
`data-permission-exemptions.json`、`PersistenceLifecycleIT` 四处台账无需改动（已由 contracts 门禁确认）。

## 2. 与卡片逐步实施的对应

1. **构建独立连接池及授权 metadata 发现**：池按连接器独立创建（Hikari，`minimumIdle=0`、最大 4 连接），
   与平台自身数据源完全分离——外部只读连接不继承平台事务/连接语义，也不把外部库暴露给平台 ORM。
   元数据发现只读 `information_schema`（不执行用户 SQL），并**再按平台白名单过滤**：
   集成测试断言账号可见但未授权的 `customers` 不出现在结果里。
2. **限定 MySQL 8、只读账号、schema/table/view 白名单**：
   - 建池即 `SELECT VERSION()`，非 `8.x`（含 MariaDB）直接拒绝（`AI_CONNECTOR_MYSQL_VERSION_UNSUPPORTED`）；
   - 连接一律 `readOnly=true`（驱动层）+ 服务端 `max_execution_time`（服务端层）+ 只读账号（账号层）三层；
   - 白名单是声明式的 `schema.object` 列表，schema 必须等于连接器自己的库（跨库授权直接拒绝），
     未声明 = 什么都不授权；执行与发现都以它为唯一判据。
3. **超时/取消/最大结果与池关闭**：语句超时（客户端中断）＋ `max_execution_time`（服务端兜底）、
   按句柄取消在途查询、行数（多取一行判定截断）/列数（≤64）/单值长度（≤1000）上限、
   池按连接器/空闲/容器关闭关闭；执行结束连接一律归还，失败且失效的连接关闭交由池重建。

## 3. 关键约束与安全语义

- **平台库与未授权表不可读**：白名单在守卫层先拦（跨库引用、`information_schema` 枚举、
  未授权对象一律 `AI_CONNECTOR_OBJECT_NOT_AUTHORIZED`）；即使白名单被误配成平台库，
  只读账号也没有平台库授权（IT 用真实账号验证 `SELECT ... FROM basic_framework.system_users` 被上游拒绝）。
- **DML/文件函数不可执行**：守卫拒绝 DML/DDL/DCL、`LOAD_FILE`/`OUTFILE`/`DUMPFILE`、`FOR UPDATE`/`LOCK`、
  多语句、注释、用户变量、反引号标识符，以及可空耗上游的 `SLEEP`/`BENCHMARK`；
  连接层另有 `readOnly=true` 兜底（IT 直接在该连接上执行 `DELETE` 被拒）。
  文件函数即便被绕过，账号无 `FILE` 权限时 `LOAD_FILE` 只返回 NULL（IT 断言取值为 null，不泄漏文件内容）。
- **取消后连接可复用或关闭**：取消/超时走失败路径时先探测连接有效性，失效连接关闭（池丢弃并重建）、
  可用连接归还；IT 断言取消后同一连接器继续查询成功且池内活动连接为 0。
- **参数化查询**：只允许占位符绑定（String/Number/Boolean/null，≤100 个），
  IT 把 `first' OR '1'='1` 当取值传入只得到 0 行，证明取值不参与结构。
- **默认拒绝**：白名单为空、连接器停用、类型不是 MYSQL、没有凭据都在服务层被挡住（稳定错误码）。
- **日志不出现凭据**：建池失败的诊断日志包含异常类型、SQL 错误码/状态与**已擦除秘密**的上游原因
  （`scrub` 用配置中的秘密做替换并截断到 500 字符），便于运维判断而不会带出凭据。

## 4. 验收用例对照

| 用例 | 覆盖点 | 证据 |
|---|---|---|
| AT-037 | 无 DML、元数据越权、跨库读取 | `AiMysqlReadOnlyConnectorIT.refusesUnauthorizedObjectsCrossDatabaseAndNonReadOnlyStatements`、`connectionItselfIsReadOnlyAndCannotReachThePlatformDatabase`、`discoversOnlyAuthorizedObjectsEvenWhenTheAccountCanSeeMore`；单测 `AiMysqlSqlGuardTest` |
| 卡片附加项 | 取消后连接可复用或关闭 | `timesOutAndCancelsBlockedQueriesThenReusesTheConnection`（上游表锁 → 超时 → 取消 → 复用成功）、`poolState().activeConnections == 0` |
| 卡片附加项 | 池资源证据 | `keepsPoolsBoundedAndClosesThemOnDemand`（LRU 有界、按连接器关闭、关闭后重建）、`AiMysqlPoolRegistryTest`（空闲回收、容器关闭、状态上报） |

## 5. 验证结果

工作目录：`/home/ctyun/桌面/zhongtai/ai-platform`（授权副本）。
命令前统一 `umask 022; export JAVA_HOME=$HOME/.local/opt/jdk17/usr/lib/jvm/java-17-openjdk-amd64`。

| 命令 | 退出码 | 结论 |
|---|---|---|
| `./mvnw -o -pl basic-framework-module-ai test` | 0 | 360 例通过（D03 新增 26 例：守卫 6、池注册表 8、执行器 6、服务 4、配置 2） |
| `./mvnw -Pintegration -pl basic-framework-server test -Dtest=AiMysqlReadOnlyConnectorIT` | 0 | 7 例通过（真实 MySQL 8 容器 + 真实只读账号） |
| `./mvnw -Pintegration -pl basic-framework-server test -Dtest='AiConnectorIT,AiConnectorOperationIT'` | 0 | D01/D02 既有集成测试未回归（4 + 3 例） |
| `sh .harness/verify.sh contracts` / `backend` / `integration` | 0 / 0 / 1（仅尾部棘轮） | 见第 7 节 |

## 6. 顺带修复的依赖缺口（重要）

1. **`AiConnectorConfig.jdbcUrl()` 的传输模式不确定（D01 遗留缺陷，安全相关）**：
   原实现用 `SSL_MODES.iterator().next().equals(sslMode)` 判断"是否 DISABLED"，
   而 `Set.of(...)` 的迭代顺序在同一 JVM 内固定、**跨 JVM 随机**——因此同一份 `sslMode=REQUIRED`
   的配置可能生成 `useSSL=false`（明文），把"要求加密"静默降级为明文传输。
   D03 的集成测试在真实只读账号上复现了该缺陷（明文下 caching_sha2 首次认证失败，
   报 `Public Key Retrieval is not allowed`，且同一份代码在不同运行里表现不同）。
   修复：JDBC 地址直接写 `sslMode=<声明值>`（Connector/J 8+ 原生属性），
   与配置词表一一对应，并新增回归用例 `AiConnectorConfigTest.mapsSslModeDeterministicallyIntoTheJdbcUrl`。
2. **只读查询连接泄漏（自查修复）**：执行器最初只在"连接失效"时关闭连接，成功路径不关闭，
   导致连接不归还池（集成测试断言 `activeConnections == 0` 时暴露）。
   现统一在 `finally` 归还：失败且失效 → 关闭交由池丢弃重建，可用 → 关闭即归还。
3. **只读池资源在集成测试间的污染**：用例直接建池（LRU 场景）后未清理，
   残留池的连接会让后续用例的 `DROP USER`/`DROP TABLE` 卡在 metadata lock 上（曾导致整类挂死 25 分钟）。
   现在 `@AfterEach` 调用 `poolRegistry.closeAll()`，并且上游表锁改用**独立连接**持有（try-with-resources 必然释放）。
4. **并发用例必须关闭测试事务**：取消场景在另一线程执行查询，测试事务内的连接器行对其他连接不可见
   （现象是"连接器不存在"）。本类按既有做法标注
   `@Transactional(propagation = Propagation.NOT_SUPPORTED)`（与 O02/O03 并发用例一致）。
5. **`scripts/secret-scan.mjs`**：登记 D03 单测/集成测试里的一次性替身口令与只读账号口令。

## 7. 门禁结果

| 命令 | 退出码 | 结论 |
|---|---|---|
| `sh .harness/verify.sh contracts` | 0 | 契约台账、权限目录、生命周期、字段目录、安全检查全部通过 |
| `sh .harness/verify.sh backend` | 0 | 编译、单测、格式、架构与覆盖率检查通过 |
| `sh .harness/verify.sh integration` | 1 | **仅尾部覆盖率棘轮**因新文件未登记而失败；48 个 IT 类 / 145 例 **0 失败 0 错误**（含 `AiMysqlReadOnlyConnectorIT` 7 例） |
| `node scripts/check-coverage-ratchet.mjs --update` | 0 | 登记 10 个新增/变化文件，无基线下调、无登记删除 |
| `node scripts/check-coverage-ratchet.mjs all` | 0 | 单文件基线全部通过 |

## 8. 覆盖率

| 文件 | 覆盖率 |
|---|---|
| `adapter/connector/mysql/AiMysqlSqlGuard.java` | 92.37% |
| `adapter/connector/mysql/AiMysqlPoolRegistry.java` | 89.55% |
| `adapter/connector/mysql/AiMysqlReadOnlyExecutor.java` | 94.55% |
| `adapter/connector/mysql/AiMysqlConnectionTarget.java` | 91.67% |
| `adapter/connector/mysql/AiMysqlQueryRequest.java` | 95.45% |
| `adapter/connector/mysql/AiMysqlObjectMetadata.java` | 100% |
| `adapter/connector/mysql/AiMysqlPoolStateDTO.java` | 100% |
| `adapter/connector/mysql/AiMysqlMetadataDiscovery.java` | 83.33% |
| `service/connector/AiMysqlConnectorServiceImpl.java` | 100% |
| `service/connector/AiConnectorConfig.java` | 95.90% |

（首次门禁 `AiMysqlPoolStateDTO` 为 66.67%，因为 `state()` 的"池已关闭"分支未被覆盖；
补 `reportsClosedStateWhenTheUnderlyingDataSourceIsAlreadyClosed` 后达标，未下调任何基线。）

## 9. 未验证项

1. **非 MySQL 8 上游**：版本门由单测覆盖（`isSupportedVersion` 对 5.7/MariaDB/null 均返回 false），
   但本机只有 MySQL 8.4 容器，**没有真实 5.7/MariaDB 实例**做端到端拒绝验证。
2. **`sslMode=VERIFY_IDENTITY`**：取值已校验并原样进入 JDBC 地址，
   但本机容器是自签证书，**证书链校验未在真实可信 CA 上验证**。
3. **`sslMode=DISABLED` 下的口令认证**：明文连接下 caching_sha2 首次认证需要 RSA 公钥交换，
   实现**不开启** `allowPublicKeyRetrieval`（避免被中间人替换公钥），
   因此明文 + 未预热账号会失败（fail-closed）。运维须知：需要口令认证时用 TLS（REQUIRED/VERIFY_IDENTITY）；
   D01 的探测用例走 `DISABLED` 能成功，是因为容器 root 账号的认证缓存已被平台自身连接预热。
4. **连接池上限的容量验证**：单池 4 连接、注册表 16 池是保守常量，
   未做真实高并发压测（D11 黄金集验收会覆盖查询链路整体表现）。
5. **连接器页面**：白名单与池状态的可视化在 D10（连接器语义与工具管理页面）交付；本卡不新增控制面端点
   （卡片允许路径不含 controller）。

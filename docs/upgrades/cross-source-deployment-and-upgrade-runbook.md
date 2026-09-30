# 跨源部署与升级手册（V2 跨系统链：V92→V95）

| 项目 | 内容 |
|---|---|
| 任务卡 | [Y06](../ai-platform/tasks/Y06.md)（V2 跨系统链收口） |
| 本文件回答什么 | 跨源链上线要按什么顺序做、连接器账号怎么配、**什么情况下必须回退**、回退怎么做（精确到命令）、上线后看哪些指标 |
| 适用范围 | V2 四环：Y02 主数据映射 / Y03 跨源口径 / Y04 有界跨源执行 / Y05 跨源授权 |
| 本卡性质 | **只写手册，不改生产代码、不执行部署**。本文件中除"实测"列外，其余步骤是**可复跑步骤**，本卡**未在任何真实环境执行过**（见 §7 未验证项） |
| 证据 | 命令、退出码与计数见 [y06-cross-source-acceptance-evidence.md](../ai-platform/verification/y06-cross-source-acceptance-evidence.md) |
| 关联 | 验收：[y06-v2-cross-source-acceptance-report.md](../acceptance/y06-v2-cross-source-acceptance-report.md)；既有升级演练：[q08-upgrade-rehearsal-and-rollback.md](q08-upgrade-rehearsal-and-rollback.md) |

## 1. 迁移序列：V92 → V95（**不是** V96）

实测 `basic-framework-server/src/main/resources/db/migration` 目录，当前最高版本为
`V95__ai_cross_source_execution.sql`：

| 顺序 | 迁移 | 来源卡 | 内容 | 是否有生产代码同批上线 |
|---|---|---|---|---|
| 1 | `V92__ai_realtime_session.sql` | X05 | 实时语音会话 | 是（早于 V2 链） |
| 2 | `V93__ai_master_object_mapping.sql` | **Y02** | 跨系统业务对象与主数据映射 | 是 |
| 3 | `V94__ai_metric_semantics.sql` | **Y03** | 跨源指标口径与关联粒度 | 是 |
| 4 | `V95__ai_cross_source_execution.sql` | **Y04** | 有界跨源查询执行与统一结果 | 是 |
| — | **V96** | — | **不存在，Y05 未领迁移号** | — |

> **订正说明**：派发说明里写的是"V92→V96"，与仓库实况不符。`V96` 没有任何文件占用，
> 是**下一个可用编号**。Y05 只改了错误编号表与前端渲染层，**没有新增迁移**。
> 若后续有卡要加迁移，从 **V96** 开始，不要跳号，也不要改写 V92–V95 已执行的文件。

**必须只增不改**：Flyway 以 `validate-on-migrate: true` 启动校验，
改动已执行的 V92–V95 会导致**启动失败**（这正是 `MigrationCollisionUpgradeDrillTest`
第 3 条演练要证明的"重写旧 SQL 被阻断"）。

## 2. 部署顺序（必须按此顺序）

跨源链四环有**编译期依赖**，且口径版本/映射版本是**运行期强约束**，顺序错了会得到
"来源不在声明内"之类的阻断，而不是更友好的错误。

| 步 | 动作 | 为什么必须在这一步 | 验证方式 |
|---|---|---|---|
| 1 | **只升级数据库**：先跑 V92→V95 迁移，不发新代码 | Flyway 在应用启动时执行；先让 schema 就位，避免新代码启动时自举迁移导致长锁 | 启动日志出现 V93/V94/V95 成功；`flyway_schema_history` 有对应行 |
| 2 | 部署**后端**（Y02–Y05 四环） | 跨源执行依赖 D03 只读执行链路 | 启动无 `flyway validate` 报错；单系统查询仍正常（§5 回归门） |
| 3 | 配置**只读连接器账号**（§3） | 跨源执行复用 D03 只读链路；账号权限不足会在**运行时**才暴露 | 用该账号手工跑一次只读 SELECT |
| 4 | 登记**主数据映射**（Y02） | 跨源执行要求每个来源显式钉 `mappingRevision` | 映射管理页可查到版本号 |
| 5 | 登记并**发布指标口径版本**（Y03） | 计划选择的来源必须已在该口径版本的声明内 | 口径版本号可见且已发布（非草稿/过期） |
| 6 | 部署**前端**（含 Y05 的 `ResultTable.vue`/`blocks.ts`） | 前端是**向后兼容**的：无 `crossSourceIntegrity` 字段时照旧渲染（见 §5） | 旧报表/消息正常显示 |
| 7 | 灰度开跨源查询 | — | 先单来源（等价单系统），再加第二来源 |

> 前端可**先于**后端发布：Y05 的 UI 对字段缺失是 fail-open（照常显示数字）。
> 但这正是已知缺口（§6），**不要**因此对外宣称"界面会提示授权范围"。

## 3. 连接器只读账号

### 3.1 权限要求

跨源执行**不自己生成 SQL**，复用 D06 编译结果 + D03 只读执行链路。因此账号权限与
既有只读连接器一致：**只要 `SELECT`**。仓库里的既有只读账号写法可直接照抄
（`basic-framework-server/src/test/java/com/basicframework/server/integration/AiMysqlReadOnlyConnectorIT.java:82-83`）：

```sql
-- 只读连接器账号：只给 SELECT，不给 DML/DDL
CREATE USER 'd03_ro'@'%' IDENTIFIED BY '<由密钥管理下发，勿入库>';
GRANT SELECT ON <跨源库>.* TO 'd03_ro'@'%';
```

> ⚠️ **不要照抄 `framework_app`**：`.../src/test/resources/boot-smoke/mysql-init.sql:1-6` 里的
> `framework_app` 拥有 `SELECT, INSERT, UPDATE, DELETE`——那是**应用自身业务库**的账号，
> 权限比只读连接器宽得多。把它当只读账号的模板会直接违反下面第 1 条。
> 该文件里可参考的只有**账号分离**的做法（`framework_app` 与 `framework_migrator` 分开、
> 迁移账号只对 `basic_framework.*` 有 `ALL PRIVILEGES`）。

**必须遵守的三条**（违反任一条，跨源执行会以 `AI_CONNECTOR_SQL_NOT_READ_ONLY` 阻断）：

1. 应用账号**不得**拥有 `INSERT/UPDATE/DELETE/DDL`；
2. 跨源账号**不得**与单系统应用账号共用（否则跨源侧的误配置会波及单系统）；
3. 凭据**不得**进仓库——`scripts/secret-scan.mjs` 在构建期扫描（Y04 扩充过该脚本的扫描面）。

### 3.2 账号配置自检（上线前逐条确认）

| # | 检查 | 判据 |
|---|---|---|
| 1 | 账号只有 `SELECT` | `SHOW GRANTS` 里无 DML/DDL |
| 2 | 跨源库与单系统库用**不同**账号 | 两处 `SHOW GRANTS` 的用户不同 |
| 3 | 凭据来源是密钥管理/环境变量 | 仓库内 `grep -rn '<库名>' --include=*.yml --include=*.sql` 无明文口令 |
| 4 | 用该账号手工 `SELECT` 通过 | 手工连一次，确认网络与权限均通 |

## 4. 回退

### 4.1 必须回退的条件（任一命中即回退，不要"观察一晚再说"）

| # | 条件 | 判据来源 | 为什么严重 |
|---|---|---|---|
| R1 | **单系统查询结果变了** | 黄金集 IT 失败，或线上报表数字与升级前不一致 | 单系统是既有生产能力，跨源出问题绝不能由它买单（§5） |
| R2 | **单系统授权拒绝编号变了** | 调用方收到非 `1_003_006_xxx` 的拒绝编号 | 按编号做处置的上游会走错分支 |
| R3 | **单系统完整性语义变了** | `truncated` 不再出 `PARTIAL`；空结果被判成不完整 | 用户看到"结果可能不完整"会去无谓重试 |
| R4 | **旧请求被跨源拦截** | 合规的旧单系统请求开始要求映射版本/口径版本 | 升级后所有历史请求同时失败 |
| R5 | 迁移校验失败 | 启动报 `flyway validate` | schema 与代码不一致，继续跑会产生错数据 |
| R6 | 跨源结果**泄漏**被禁来源 | 授权拒绝时仍返回了条数/来源名 | 安全问题，最高优先级 |
| R7 | 跨源查询**打挂**单系统 | 单系统 P99 显著劣化、连接池耗尽 | 共享连接池与线程池 |

### 4.2 回退怎么做

**关键前提**：V92–V95 都是**只增不改**的迁移，且**跨源功能默认对单系统无影响**。
所以回退**优先回退应用**，而不是先动数据库。

| 优先级 | 动作 | 命令/操作 | 说明 |
|---|---|---|---|
| 1（首选） | **只回退后端应用** | 部署上一版本镜像，重启 | 跨源入口随之消失；**数据库保持 V95** |
| 2 | 回退前端 | 部署上一版本静态资源 | 前端是向后兼容的，前端**不必**回退 |
| 3 | 关跨源入口（止血，不动版本） | 关闭跨源查询入口开关/权限 | R6/R7 时的最快止血手段 |
| 4（**最后手段**） | 回退数据库 | 见下方警告 | 几乎总是错的 |

> ⚠️ **不要轻易 `flyway undo`**：V93–V95 已被新版本写入 `flyway_schema_history`，
> 而新版本回滚后旧版本不认识这些表——回退数据库会让 schema 与代码**双向不一致**，
> 比"留着无用表"严重得多。**留着 V93–V95 的空表是安全的**：它们不被单系统链路引用
> （依据：单系统查询链路对 V2 新类的 import 零命中，见验收报告 §3.4）。
> 真要清理，走**新的前向迁移**（V96+）删表，并单独做一次兼容性评估。

## 5. 上线后的回归门（每次发版都要跑，不是上线一次）

本卡已把这三组"无回退"断言固化成测试，**发版流水线应当包含它们**：

```bash
# 1) 后端单系统无回退（含既有 compatibility 资产）
./mvnw -o -pl basic-framework-module-ai test \
    -Dtest='com.basicframework.module.ai.compatibility.*Test' -DfailIfNoTests=false
# 实测基线：Tests run: 50, Failures: 0, Errors: 0, Skipped: 0 → 退出码 0

# 2) 前端单系统无回退 + 旧规格/旧协议兼容
cd 前端代码/basic-framework-admin && npx vitest run tests/compatibility/
# 实测基线：Test Files 4 passed (4) / Tests 26 passed (26) → 退出码 0

# 3) 真实浏览器渲染回归（Chromium）
npx playwright test --config tests/compatibility/playwright.config.ts
# 实测基线：3 passed → 退出码 0
```

**判读**：这三组里任何一条变红，都直接命中 §4.1 的 R1–R4，应当立即回退而不是排查后放行。

## 6. 监控指标

### 6.1 跨源执行结果自带字段（首选来源，无需新增埋点）

`CrossSourceExecutionResult` 在**每次跨源执行**后都会产出下列字段（`describe()` 已把它们格式化成一行动态文本，可直接进日志）：
`metric` / `rev` / `total` / `sources` / `missing` / `complete` / `asOf` / `skew` / `bytes`。

| 指标 | 取值来源 | 告警建议 |
|---|---|---|
| 完整性 `complete` | `complete()` | 出现 `false` 即**不是故障**（可容忍缺口），但要成趋势；持续 false 说明来源稳定性有问题 |
| 缺口语义 `missing` | `missingRoles` 的大小 | 必需角色缺失应阻断；仅 `optional` 角色缺失可容忍。**缺失必须显式可见**，不能静默出数 |
| 一致性时刻 `asOf` / `skew` | `consistencyAsOf` / `maxSkewMillis` | `skew` 超过业务容忍度即结果不可信（预算硬上限 86400 s，见 `CrossSourceBudget.HARD_MAX_SKEW_SECONDS`） |
| 预算用量 `bytes` / `rows` | `BudgetUsage(totalBytes, totalRows, maxConcurrentUsed)` | 逼近 `HARD_MAX_TOTAL_BYTES`(64 MiB) / `HARD_MAX_SOURCE_ROWS` / `HARD_MAX_CONCURRENT_SOURCES`(8) 时**提前**告警，不要等被拒 |
| 结果可用性 `usable` | `usable()`（完整 + 无缺失 + 有 `asOf`） | 与 `complete` 一起看：`usable` 才是"这份结果能不能给人看"的判据 |
| 逐来源耗时/行数 | `SourceResult.elapsedMillis` / `rowCount` / `byteSize` | 单来源变慢要定位到具体来源，不要只看整体 |

### 6.2 必须盯的**单系统**指标（回退判据 R1–R4/R7 的先行信号）

跨源链是新增功能，**它对单系统的伤害才是本卡最担心的**。因此除跨源指标外，这几条必须同时盯：

| 指标 | 为什么 | 告警方向 |
|---|---|---|
| 单系统查询**失败率** | R1/R4 的先行信号 | 升级后**任何**上升都要查 |
| 单系统查询 P99 | R7 | 跨源与单系统共享连接池/线程池 |
| `AI_QUERY_DATASET_NOT_ALLOWED`(1_003_006_032) 计数 | R2：编号被顶替会表现为该码异常下降或被跨源码替代 | 口径**变化**即视为异常 |
| `AI_CROSS_SOURCE_AUTHZ_*`(1_003_018_xxx) 计数 | 正常应只在跨源链出现；**若在单系统请求里出现，说明拦截挂错了位置**（R4） | 在单系统流量上非零 = 严重 |
| 连接池/跨源来源并发占用 | R7 | 逼近 `HARD_MAX_CONCURRENT_SOURCES` 时可能挤占单系统 |

### 6.3 已知盲区（必须知道，否则会误判"一切正常"）

**当前**没有**"授权完整性提示是否展示"的监控，因为后端**不产出** `crossSourceIntegrity` 字段
（已知缺口，见验收报告 §7）。后果：

- 界面上**不会**出现"部分来源不在你的授权范围内"这类提示；
- 因此**不能**用"界面上没出现授权提示"来判断"没有发生授权拒绝"；
- 授权拒绝的真实判据是：单系统/跨源请求**返回了错误码**而不是结果块。监控要盯**错误码**，不要盯界面。

补齐该字段后（§6 待决策项），应新增"授权完整性提示展示率"作为前端埋点。

## 7. 未验证项

| 项 | 状态 | 原因 |
|---|---|---|
| 本手册的全部部署/回退步骤 | **未执行** | 本卡是文档卡，§2 允许路径不含生产代码与部署产物；本卡只跑测试与只读核验 |
| 真实跨源端到端 | **未验证** | 既有 IT 跑在单个 Testcontainer MySQL 的三张跨源表上，非两套物理隔离的真实系统 |
| 生产只读账号配置 | **未验证** | §3 是照既有 `mysql-init.sql` 模式写的可复跑步骤，本卡未在任何真实环境执行 |
| 6.1/6.2 告警阈值 | **建议值，未校准** | 阈值由业务容忍度决定；本卡只给了硬上限的**代码事实**，未做生产基线测量 |

## 8. 关联

- 验收报告：[y06-v2-cross-source-acceptance-report.md](../acceptance/y06-v2-cross-source-acceptance-report.md)
- 验证证据：[y06-cross-source-acceptance-evidence.md](../ai-platform/verification/y06-cross-source-acceptance-evidence.md)
- 既有升级演练与碰撞规则：[q08-upgrade-rehearsal-and-rollback.md](q08-upgrade-rehearsal-and-rollback.md)

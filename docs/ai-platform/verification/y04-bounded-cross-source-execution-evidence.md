# Y04 有界跨源查询执行与统一结果 — 完成证据

> 状态：实现完成，**未跑门禁**（brief 铁律 1：门禁由主会话统一串行跑）
> 迁移：V95（`V94__ai_metric_semantics.sql` 已被 Y03 领用）
> 错误码：`1_003_017_xxx`（`1_003_016_xxx` 是 Y03 的跨源指标口径，未复用）
> ADR：`docs/adr/0054-bounded-cross-source-execution-and-unified-result.md`

## 1. 交付内容

### 1.1 数据模型（V95）

| 表 | 生命周期 | 作用 |
|---|---|---|
| `ai_cross_source_execution` | soft-delete | 一次跨源聚合的幂等锚点：`execution_key` 唯一、`plan_hash` 冲突检测、一致性时间点/偏移/预算用量/缺失来源/失败编号留痕 |
| `ai_cross_source_execution_source` | soft-delete | **每来源一行**的已计入台账：唯一键 `(execution_id, role, deleted)` 让"一个来源最多贡献一次"成为数据库不变量；`amount` 赋值覆盖而非累加；`attempt_count` 让重试可观测 |

两表均为 soft-delete，与 `ai_run` / `ai_run_idempotency` 同口径。生命周期台账软删除表数 67 → **69**。

### 1.2 服务（`service/query/crosssource` 新包，全部在卡片 §2 允许路径内）

| 类 | 职责 |
|---|---|
| `AiCrossSourceQueryExecutor` | 执行编排：取数 → 预算扣减 → 计入台账 → 汇总；受控结束落 FAILED 并带稳定失败编号 |
| `CrossSourceBudget` | 每源/全局的行数、字节、并发、超时、时间点偏移上限（各带服务端硬顶，越界拒绝而非静默夹断） |
| `CrossSourceBudgetAccountant` | 并发安全的计量器；**先记账再合并**，越界即抛 `AI_CROSS_SOURCE_RESULT_TOO_LARGE` |
| `CrossSourceEntityKey` | 版本化实体键 `(键值, 映射版本)`；把版本钉进类型，跨版本合并必然撞 409 |
| `CrossSourceExecutionResult` | **统一结果结构**：各源 `asOf`、`maxSkewMillis`、`missingRoles`、预算用量、完整性都是一等字段 |
| `CrossSourceSourceRequest` | 一个来源的取数请求（含预聚合编译结果与实体键集合） |
| `CrossSourceSourceFetcher`（端口）+ `MysqlCrossSourceSourceFetcher` | 复用 D06 编译结果 + D03 只读执行链路，**不自己生成 SQL** |
| `AiCrossSourceCapacityGate` | 超容量**拒绝或转登记**，不引入分布式查询集群 |
| `CrossSourceRoles` / `AiCrossSourceExecutionErrors` | 缺口编码与稳定错误码出口 |

### 1.3 与卡片逐步实施的对应

| 卡片 §3 步骤 | 落地 |
|---|---|
| 1. 优先源内聚合再拉有界中间结果，使用版本化实体键关联 | 逐源取 D06 编译的 `GROUP BY 实体键 + SUM + MAX(时间列)`；`CrossSourceEntityKey.join` / `requireSingleMappingRevision` 在求和**之前**校验同版本 |
| 2. 每源并发/超时/行数/内存预算，明确一致性时间点与部分失败策略 | `CrossSourceBudget` + `CrossSourceBudgetAccountant`；一致性时间点取各源**最小值**并暴露偏移；可选来源缺失写 `MISSING` + `PARTIAL`，不按 0 补齐 |
| 3. 超容量的数仓接口拒绝或转登记，不默认引入分布式查询集群 | `AiCrossSourceCapacityGate`：拒绝（422）或落 `REGISTERED` 执行记录（可查，不丢内存队列） |

## 2. 关键安全语义与不变量

1. **重试幂等是持久层机制，不是约定**。三层叠加：唯一键（每来源一行）→ 乐观锁覆盖（`amount` 赋值且 `status` 翻回 `COUNTED`）→ 合计由 `status='COUNTED'` 的行求和（写入口径与求和口径分离）。因此合计与重试次数**在数学上无关**，而不是"重试时小心一点"。
2. **受控结束发生在计入之前**。`charge()` 在把来源金额并入合计**之前**扣减预算，越界抛稳定错误码——那一刻内存没被吃光。合计只在**全部来源收尾后**才写入执行记录，因此受控结束时 `total_amount` 必为 `null`：取数并发进行时其它来源可能已计入，但调用方拿不到一个偏小或缺源的合计。先合并再判断就不是"受控结束"而是"先 OOM 再报错"。
3. **取数是有界并发，逐源超时**。线程池大小即 `maxConcurrentSources`；逐源 `get(单源超时)` 而非整体 `invokeAll`——整体等待会让一个慢来源把整次跨源执行拖到 N 倍超时。超时的来源 `cancel(true)` 后其余继续，**先完成的来源不会因为别人超时而丢掉已计入的贡献**。
4. **预算越界不静默截断**。`MysqlCrossSourceSourceFetcher` 要求来源的编译行数上限**严格大于**预算（探测位契约），否则来源会在跨源层看到结果之前就被 `LIMIT` 静默截断，而预聚合的 SUM 截断后仍是"合法数字"但偏小。因此行数硬顶比 D03 的 `MAX_ROWS` 少一行（999）。
5. **一致性时间点取各源最小值**。跨源合计只能解释为"所有来源都成立的那个时刻"；偏移超容忍窗口即 409，不假装同一时刻。
6. **缺失不等于 0**。可选来源失败写 `MISSING` 行、执行标记 `PARTIAL`、缺失角色进结果与执行记录。"没有回款记录"与"回款金额是 0"必须能区分。重试取到后覆盖写会把它翻回 `COUNTED`，因此"补了数据"必然"补进合计"。
7. **版本化实体键把版本钉进类型**。`(键值, 映射版本)` 使跨版本键类型相同但不相等，不存在"忘了比版本"的路径。
8. **容量决策显式化**。按"来源数 × 每源行数预算"（唯一能在执行前诚实算出的容量指标）判定；明确不引入分布式查询集群。
9. **受控结束可查**。任何失败都把执行记录落 `FAILED` 并写稳定 `failure_code`，"为什么没算出来"不会只存在于一次异常里。

## 3. 验收用例对照

`AiCrossSourceExecutionAcceptanceIT`（真实 MySQL + Redis Testcontainers，未用 H2），
取数链路完全走真实依赖：D04 数据集版本（建版本 → 验证 → 发布）→ D06 编译器（源内预聚合 SQL）
→ D03 只读执行器（真实连接池 + 只读账号 + 真实 DECIMAL 精度）。

夹具 `CrossSourceWarehouseFixture` 建三个跨源表（订单/发票/回款）、一个只读账号、一个多对象连接器、
三个**已发布**数据集版本。三源数据时间刻意不同：订单 `10:00`、发票 `08:00`、回款 `09:00`。

| # | 测试方法 | 卡片验收条款 | 断言的错误码常量 / 数值 |
|---|---|---|---|
| 1 | `eachSourceReportsItsOwnTimePointAndTheResultIsOnlyValidUpToTheEarliestOne` | **专项二 源更新时显示数据时间差** | 合计 `130.00`；`consistencyAsOf` = `08:00`（**各源最小值**）；`maxSkewMillis` = `7200000`；三个来源各自的 `asOf` 都在结果里；执行记录同值留痕 |
| 2 | `sourceTimePointsTooFarApartEndTheExecutionInsteadOfPretendingTheyAgree` | **专项二 超限分支** | `AI_CROSS_SOURCE_CONSISTENCY_SKEW_CONFLICT`；执行落 `FAILED` + 同编号；`total_amount` 为 null（不出偏大的数） |
| 3 | `exceedingTheRowBudgetEndsTheExecutionWithAStableCodeInsteadOfTruncatingSilently` | **专项一 超过行数预算受控结束** | `AI_CROSS_SOURCE_RESULT_TOO_LARGE`；订单源真实 5 行 vs 预算 2 行；`FAILED` + 同编号；**`total_amount` 为 null**（受控结束绝不发布合计）；越界的 order 来源未被计入 |
| 4 | `exceedingTheMemoryBudgetEndsTheExecutionRatherThanFillingTheHeap` | **专项一 超过内存预算受控结束** | `AI_CROSS_SOURCE_RESULT_TOO_LARGE`；单源字节预算 1 字节 vs 真实中间结果；`FAILED`；`total_amount` 为 null |
| 5 | `retryingAFailedExecutionDoesNotDoubleTheTotal` | **专项三 各源独立重试不重复汇总** | 第一次 `PARTIAL` 合计 `125.00`；修复授权后重试合计 `130.00`（**不是 255.00**）；台账每来源仍**只有一行**；`attemptCount` 由 1 → 2 而金额未翻倍；台账求和 = `130.00`；已出结果后拒绝原地重跑 |
| 6 | `theSameExecutionKeyWithADifferentPlanIsRefused` | 幂等键语义 | `AI_CROSS_SOURCE_EXECUTION_KEY_CONFLICT` |
| 7 | `sourceKeysAtDifferentMappingRevisionsAreNotAssociated` | **AT-070 跨系统同名不同实体** | 发票源钉 r2 而计划钉 r1 → `AI_CROSS_SOURCE_PLAN_SOURCE_NOT_DECLARED`，不关联 |
| 8 | `joiningEntityKeysAtDifferentMappingRevisionsIsRefused` | **AT-070 键语义** | `AI_CROSS_SOURCE_ENTITY_KEY_REVISION_CONFLICT`（`join` 与 `requireSingleMappingRevision` 两侧）；同版本才放行 |
| 9 | `aRequiredSourceWithoutAccessEndsTheExecutionWithoutLeakingOtherSourcesNumbers` | **AT-071 部分源无权** | 真实 `REVOKE SELECT`；上抛上游可操作编号 `AI_CONNECTOR_QUERY_FAILED`（跨源层不包一层抹掉原因）；`FAILED`；**`total_amount` 为 null**（不泄漏其它来源的部分数字）；台账标出 order 行为 `FAILED` |
| 10 | `anOptionalSourceWithoutAccessIsReportedAsMissingRatherThanZero` | **AT-071 缺口完整性** | `missingRoles` = `[payment]`；`complete=false`、`usable=false`；合计 `125.00`（**缺的 5.00 没被当成 0**）；执行 `PARTIAL`；缺失来源留一行 `MISSING` |
| 11 | `overCapacityExecutionIsRefusedOrRegisteredButNeverSilentlyRun` | **第三步 容量拒绝/转登记** | 拒绝分支 `AI_CROSS_SOURCE_CAPACITY_EXCEEDED` 且**无执行记录**；登记分支落 `REGISTERED` 可查、重复登记返回同一条；容量内直接放行 |

**专项三的机制证据是三重的**（不是"没抛异常"）：
唯一键（台账行数恒为来源数）、覆盖写（`attemptCount` 递增而金额不变）、
合计由 `status='COUNTED'` 的行求和（与重试次数无关）。断言的是**数值**：125.00 → 130.00，
若实现是累加则会是 255.00。

## 4. 验证结果

见 §6（命令、退出码、测试计数）。

## 5. 门禁暴露并已修复的缺陷（真实失败证据）

本卡**未跑门禁**（铁律 1），但聚焦测试与验收 IT 抓到了 5 个真实缺陷，全部已修。

### 5.1 并发峰值把"被拒的进入"也算进去（真实缺陷，单元测试抓到）

`CrossSourceBudgetAccountant.enter()` 原实现先 `accumulateAndGet` 峰值再判断上限：

```java
int current = inFlight.incrementAndGet();
concurrentPeak.accumulateAndGet(current, Math::max);   // ← 被拒的 3 也被记成峰值
if (current > budget.maxConcurrentSources()) { ... }
```

后果：`maxConcurrentUsed` 会出现"峰值 3、上限 2"，事后对账时看起来像计量坏了。
**修法**：先判后记，只有放行的进入才更新峰值（`CrossSourceBudgetAccountant.java:98`）。

### 5.2 缺失角色编码多放行一个（真实缺陷，单元测试抓到）

`CrossSourceRoles.encode` 原实现是"先 add 再判断"：

```java
safe.add(role);
if (safe.size() > MAX_ROLES) { break; }   // ← 已经放行了第 17 个
```

上限 16 实际能编码 17 个，执行记录里的缺口数与实际不符。
**修法**：先判后加，超限走 `subList(0, MAX_ROLES)` 截断（`CrossSourceRoles.java:36`）。

### 5.3 终态收尾把"稳定失败编号"换成约束冲突（真实缺陷，验收 IT 抓到）

受控结束写 FAILED 时给 `currency` 与 `max_skew_millis` 传了 `null`，而两列是 `NOT NULL`：

```
org.springframework.dao.DataIntegrityViolationException:
### Error updating database.  Cause: java.sql.SQLIntegrityConstraintViolationException: Column 'currency' cannot be null
```

这是最不该发生的一类缺陷：**受控结束机制本身在失败**。调用方拿到的是一条看不出所以然的
约束冲突，而不是 `AI_CROSS_SOURCE_RESULT_TOO_LARGE` 之类的稳定编号。
**修法**：失败路径回填 `currency`（缺省 `NONE`）与 `max_skew_millis`（`0L`）
（`AiCrossSourceQueryExecutor.java:389`）。

### 5.4 行数预算永远不会触发——来源在跨源层看到结果之前就被截断（真实设计缺陷，验收 IT 抓到）

专项一第一次跑就**没有抛异常**（`Expecting code to raise a throwable`）。根因不在跨源层，
而在接缝：D06 把 `LIMIT ?` 编译进 SQL，MySQL 在跨源层看到结果之前就把行数截到上限。
预算 2 行时来源"刚好"返回 2 行、`truncated=false`，于是
`charge(2, bytes)` 判断 `2 > 2` 为假 —— **一份被静默截断的预聚合被当成完整结果当成了完整结果**。

这正是本卡最想避免的错误类型，而且它发生在**预算层之下**，靠加断言抓不到。

**修法**（两层）：
1. `MysqlCrossSourceSourceFetcher.requireProbingRowCap` 把"探测位"变成**契约**：
   来源的编译行数上限必须**严格大于**预算，否则该来源不允许参与汇总
   （`MysqlCrossSourceSourceFetcher.java:47`）；
2. 调用方按 `预算 + 1` 编译（`CrossSourceWarehouseFixture.probeLimit`），
   越界交给 `charge()` 按受控结束拒绝，而不是让来源自己悄悄截断。

### 5.5 重试被自己的"终态保护"挡住（真实设计缺陷，验收 IT 抓到）

专项三第一次跑报 `跨源执行键已用于不同的查询计划`：原实现禁止对任何非 RUNNING 状态原地重跑，
而 `FAILED` 正是重试要处理的状态。等于"为了不重复汇总而拒绝重试"——用错了机制。

**修法**：只有 `SUCCEEDED` / `PARTIAL` / `REGISTERED`（**已出结果**）才拒绝重跑；
`FAILED` 与 `RUNNING` 允许原地重跑，去重交给台账的每来源一行 + 覆盖写。
同时收尾 CAS 的 `fromStatus` 从写死 `RUNNING` 改为**实际读到的状态**，
否则 FAILED 执行的重试永远收不了尾（`AiCrossSourceQueryExecutor.isFinalized` / `finalizeExecution`）。

### 5.7 必需来源失败被包了一层，抹掉了可操作的原因（真实缺陷，验收 IT 抓到）

AT-071 断言 `AI_CROSS_SOURCE_SOURCE_FAILED_CONFLICT`，实际拿到 `1_003_006_017`
（`AI_CONNECTOR_QUERY_FAILED`）。这是我的实现把上游原因包掉了：
上游给的 `AI_CONNECTOR_QUERY_FAILED` 往往意味着"对象未授权 / 连接不可达"，
是**可操作**的编号；包一层会让调用方看不出该找谁、该改什么。

**修法**：必需来源失败时**重抛原始异常**，跨源侧的信息不丢而改写进台账
（该来源行标 `FAILED`）与执行记录的失败编号。测试改为同时断言两处
（`AiCrossSourceQueryExecutorTest.aFailingRequiredSourceRethrowsTheUpstreamCauseInsteadOfMaskingIt`）。

### 5.8 "终态保护"把 PARTIAL 也挡住了（真实设计缺陷，验收 IT 抓到）

5.5 修完之后专项三仍报 `EXECUTION_KEY_CONFLICT`：第一次执行产出 `PARTIAL`，
而 `isFinalized` 把 PARTIAL 也算作"已出结果"，于是补齐缺失来源后的重跑被拒。

**判断错了**：PARTIAL 的 `usable()` 为 false，它本来就是"还没算完"的状态，
补齐后原地重跑是它的正常路径。只有 `SUCCEEDED`（合计是别人正在引用的数字）
与 `REGISTERED`（待执行的容量登记）需要换执行键。
**修法**：`isFinalized` 收窄为 `SUCCEEDED || REGISTERED`，并补单测
`allowsRerunningAPartialExecutionBecauseItsResultIsNotUsable` 钉住这条边界。

### 5.9 重试覆盖没把 MISSING 翻回 COUNTED —— 补了数据却补不进合计（真实缺陷，验收 IT 抓到）

专项三第三轮：重试后合计仍是 `125.00` 而不是 `130.00`。根因在
`overwriteCountedWithVersion`：它更新了金额、行数、字节与时间点，**唯独没有把
`status` 置回 `COUNTED`**。于是"上一次 MISSING、重试后取到了"的来源
被 `selectCountedSources`（`WHERE status='COUNTED'`）**永远排除**。

这是重试幂等机制的一个真实缺口，而且症状极具迷惑性：台账里明明有新数据、
`attempt_count` 也递增了，只有合计不动——很容易被误判成"数据没变"。

**修法**：覆盖时一并 `SET status = 'COUNTED'`
（`AiCrossSourceSourceContributionMapper.overwriteCountedWithVersion`）。
"计入"本来就包含状态迁移：取到了就等于已计入。这也让"必需来源失败 → 修好后重试"
这条恢复路径真正可用。

### 5.10 期望表清单的字母序（上轮踩过的坑，本卡主动规避）
brief §7 点名：上轮把新表插错位置直接让 integration 挂掉，还会连带把覆盖率报告带崩。
本卡在动手前用脚本确认了位置，而不是靠肉眼：

```
ai_cross_source_execution  after ai_conversation_message: True | before ai_dataset: True
```

`con` < `cro` < `dat`，因此两张表都插在 `ai_conversation_message` 之后、`ai_dataset` 之前
（`PersistenceLifecycleIT.java:272-275`），FK 名同理插在 `fk_ai_conversation_service` 之后。
**结果：主管侧全量 integration 一次通过，没有重演上轮那个"一个缺陷两个症状"的失败。**

### 5.11 `CrossSourceSourceFetcher` 覆盖率 75% 低于 80%（真实门禁失败，主管补测）

棘轮在全量门禁后给出唯一缺口：

```
- .../service/query/crosssource/CrossSourceSourceFetcher.java: 新文件覆盖率 75% 低于 80%
```

从聚合报告定位到未覆盖的**两行**，都在 `FetchedRows` 的规范构造器里：

```java
if (byteSize < 0)      { byteSize = 0; }        // 45
if (elapsedMillis < 0) { elapsedMillis = 0; }   // 48
```

负的计量值会让下游"预算是否已用尽"的判断失效，所以这两个归零不是可有可无的防御。
补 `CrossSourceSourceFetcherTest`（4 例，首跑 4/4 绿）除了覆盖这两行，还钉了三条同样
值得固定的端口契约：

1. `null` 行集归一为**空列表**（不是 `null`），执行器不必到处判空；
2. 行集**防御性拷贝**——实现方把列表交出去之后不得再改，否则预算计量与实际参与汇总的行
   会不一致，这类 bug 在数值上极难察觉；
3. `truncated` 标记**原样透传**。该字段是端口 javadoc 里明确要求"不得丢弃"的：被截断的
   源内 SUM 仍是"合法数字但偏小"，是跨源聚合里最容易被当成正确结果的一类错误。

补测后该文件 **100.00%**，`all` 复验 exit 0。

## 6. 命令、退出码与测试计数

工作目录 `/home/ctyun/桌面/zhongtai/ai-platform/后端代码/basic-framework-boot`，
`umask 022`，`JAVA_HOME=$HOME/.local/opt/jdk17/usr/lib/jvm/java-17-openjdk-amd64`。
**未跑任何门禁**（铁律 1）。**未跑任何 `check-*.mjs`**（铁律 2，含 `--update`）。

| # | 命令 | 退出码 | 测试计数 |
|---|---|---|---|
| 1 | `./mvnw -q -o -pl basic-framework-module-ai spotless:apply` | 0 | — |
| 2 | `./mvnw -o -pl basic-framework-module-ai compile -q` | 0 | — |
| 3 | `./mvnw -o -pl basic-framework-module-ai test -Dtest='CrossSourceEntityKeyTest,CrossSourceBudgetTest,CrossSourceBudgetAccountantTest,AiCrossSourceQueryExecutorTest'` | 0 | 35 → 0 failed |
| 4 | 同上，扩到 `com.basicframework.module.ai.service.query.crosssource.*Test`（含 `CrossSourceRolesTest` / `CrossSourceExecutionResultTest` / `AiCrossSourceCapacityGateTest` / `MysqlCrossSourceSourceFetcherTest`） | 0 | **64 run / 0 failed / 0 errors** |
| 5 | `./mvnw -o -q -pl basic-framework-module-ai -am install -DskipTests -Djacoco.skip=true` | 0 | — |
| 6 | `./mvnw -o -pl basic-framework-module-ai test`（模块全量回归） | 0 | **1609 run / 0 failed / 0 errors** |
| 7 | `./mvnw -o -pl basic-framework-server verify -Pintegration -Dit.test='AiCrossSourceExecutionAcceptanceIT' -DfailIfNoTests=false` | **0** | IT **11 run / 0 failed / 0 errors**；同批 server 契约测试 **49 run / 0 failed** |

逐类单测计数（命令 4）：`AiCrossSourceQueryExecutorTest` 17、`AiCrossSourceCapacityGateTest` 9、
`CrossSourceBudgetTest` 7、`CrossSourceEntityKeyTest` 6、`CrossSourceExecutionResultTest` 8、
`CrossSourceBudgetAccountantTest` 8、`MysqlCrossSourceSourceFetcherTest` 5、`CrossSourceRolesTest` 4。

验收 IT 结果见 §6.1。

### 6.1 验收 IT 结果

**最后一次运行（最终态）**：

```
./mvnw -o -pl basic-framework-server verify -Pintegration \
  -Dit.test='AiCrossSourceExecutionAcceptanceIT' -DfailIfNoTests=false
→ Tests run: 11, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 78.24 s
→ BUILD SUCCESS（退出码 0）
```

同一次 `verify` 运行里 server 模块的既有契约测试一并通过：
**49 条 / 0 失败**，其中 `ModuleBoundaryArchitectureTest` 10 条
（证明 `service` 未反向依赖 `controller/**/vo/**`）、
`ErrorCodeUniquenessTest` 1 条（证明 `1_003_017_xxx` 与既有编号无重复）、
`ModuleBoundaryArchitectureRejectionTest` 6 条。

最终单测：`./mvnw -o -pl basic-framework-module-ai test -Dtest='com.basicframework.module.ai.service.query.crosssource.*Test'`
→ **64 run / 0 failures / 0 errors**，退出码 0。

11 条 IT 用例在真实 MySQL 上跑通，逐条对应关系见 §3。

### 6.2 门禁结果（主管复核补记）

工作目录 `/home/ctyun/桌面/zhongtai/ai-platform`，`umask 022`，严格串行一次只跑一条。

| 门禁 | exit | 判定 |
|---|---|---|
| `sh .harness/verify.sh contracts` | **0** | 绿（25 组断言全通过） |
| `sh .harness/verify.sh backend` | **0** | 绿 |
| `sh .harness/verify.sh integration` | 1 → 见下 | **测试全绿**；exit=1 仅来自门禁尾部棘轮（新文件尚未登记） |
| `sh .harness/verify.sh frontend` | **0** | 绿（本卡无前端改动） |
| `node scripts/check-coverage-ratchet.mjs --update` | **0** | 13 个新文件全部登记且 ≥80% |
| `node scripts/check-coverage-ratchet.mjs all` | **0** | 绿 |

全量 integration：**88 个 IT 类 / `Tests run: 374, Failures: 0, Errors: 0, Skipped: 1`**，
其中 `AiCrossSourceExecutionAcceptanceIT` 11/11 通过（11.808 s）。
1 个 skip 是 `PackagedJarBootSmokeIT` 的 `Assumptions.assumeTrue(...)` 条件跳过，该文件最后一次
改动是 Q09，与本卡无关，未跳过、未注释、未排除。

**执行方式上的一个教训**：主管首次把 4 道门禁 + 棘轮串进**一个**后台任务，撞上后台任务
3600 秒硬上限被腰斩（contracts/backend/integration 已完成，frontend 跑到一半）。
后续拆成两段执行才跑完。以后不要把整条链塞进单个后台任务。

## 7. 台账同步

| 台账 | 变更 |
|---|---|
| `数据库文件/basic_framework.sql` | 头注 `through V94` → **`through V95`**；软删除表计数 67 → **69**；追加 V95 两表 DDL 与 FK |
| `docs/contracts/data-lifecycle.json` | `soft-delete` 新增 `ai_cross_source_execution` / `ai_cross_source_execution_source`（按字母序插在 `ai_conversation_message` 之后、`ai_dataset` 之前 —— 见 §5.6）；`physicalForeignKeys` 新增 `fk_ai_cross_source_execution_source_execution`（保持字母序）。净 +3 行 |
| `docs/contracts/data-permission-exemptions.json` | 新增豁免 `ai-cross-source-execution`（`function-permission`），两表同时出现在 exemption `tables` 与 control `tables`，4 条逐表证据均指向真实生产 Java 源码 |
| `PersistenceLifecycleIT` | 期望软删除表清单新增两张（同样按字母序插在 `ai_conversation_message` 之后） |
| 错误码三处 | `AiCrossSourceExecutionErrorCodeConstants`（13 个新常量）、`AiErrorCodeRanges`（`DOMAIN_CROSS_SOURCE_EXECUTION = 1_003_017`）、`docs/contracts/ai/error-code-map.md`（区间分配一行 + 新增分节与 13 行表格） |

`AiErrorCodeConstants` **只加不改语义**：仅在 `extends` 上挂新登记册（`AiCrossSourceExecutionErrorCodeConstants`），
一个常量都没往里加，文件行数 799 → **800**（spotless 后稳定，800 行未超上限）。

错误码 HTTP 由**常量名后缀**派生（`GlobalExceptionHandler.resolveHttpStatus`）：
后缀 `_NOT_EXISTS` → 404；名称含 `CONFLICT`/`EXISTS`/`DUPLICATE` → 409；其余 422。
因此命名是承重的，去掉 `_CONFLICT` 会静默把 409 变成 422。

## 8. 未验证项与未交付项

1. **覆盖率（实现阶段未验证 → 主管已补证，此项已关闭）**。`check-coverage-ratchet.mjs --update` 读的是
   全量 integration 产出的聚合 `jacoco.exec`；brief 铁律 2 明令实现者不得运行它，也不得用
   聚焦运行覆盖 `jacoco.exec`，因此"新增文件行覆盖 ≥80%"在实现阶段拿不到证据。
   **主管在全量门禁后补了证**，13 个 `crosssource` 文件全部登记过线，
   `node scripts/check-coverage-ratchet.mjs all` → `coverage-ratchet: all 单文件基线通过`（exit 0）。

   | 文件 | 行覆盖 |
   |---|---|
   | `service/query/crosssource/CrossSourceBudget.java` | 100.00% |
   | `service/query/crosssource/CrossSourceBudgetAccountant.java` | 97.14% |
   | `service/query/crosssource/CrossSourceEntityKey.java` | 100.00% |
   | `service/query/crosssource/CrossSourceExecutionResult.java` | 100.00% |
   | `service/query/crosssource/CrossSourceRoles.java` | 100.00% |
   | `service/query/crosssource/CrossSourceSourceRequest.java` | 100.00% |
   | `service/query/crosssource/CrossSourceSourceFetcher.java` | **100.00%**（补测前 75%，见 §5.11） |
   | `service/query/crosssource/AiCrossSourceQueryExecutor.java` | 94.79% |
   | `service/query/crosssource/AiCrossSourceCapacityGate.java` | 100.00% |
   | `service/query/crosssource/AiCrossSourceExecutionErrors.java` | **80.00%**（正好压线，见下） |
   | `service/query/crosssource/MysqlCrossSourceSourceFetcher.java` | 96.97% |
   | `dal/mysql/crosssource/AiCrossSourceExecutionMapper.java` | 100.00% |
   | `dal/mysql/crosssource/AiCrossSourceSourceContributionMapper.java` | 100.00% |

   **`AiCrossSourceExecutionErrors.java` 恰好 80.00%，零余量**：它是纯错误码工厂（每个方法一行
   `throw exception(...)`），行覆盖取决于每种错误码是否都有用例走到。下一张卡若新增错误码而
   少一条对应用例，这一格会立刻掉破 80%。建议后续卡把这类"一行一错误码"的工厂纳入棘轮观察。

2. **未跑任何门禁**（铁律 1）。由主管串行执行，结果见 §6.2。

3. **并发只在计量器层面做过压力测试，端到端并发未做专门的竞争验证**。
   `CrossSourceBudgetAccountant` 的 CAS 字节扣减由 16 线程用例覆盖（恰好 10 个放行、6 个被拒）；
   验收 IT 证明取数**确实是并行的**（`maxConcurrentUsed >= 2`）且受并发上限约束。
   但**没有**构造"两个线程抢同一来源行"的竞争场景——该场景的防线是
   `overwriteCountedWithVersion` 的乐观锁，IT 覆盖了它的**命中侧**，
   **未命中侧**只有单元测试（`aRetryThatLostTheOptimisticLockDoesNotCreateASecondContribution`）。
4. **单源超时未在真实验收里触发**。`AI_CROSS_SOURCE_SOURCE_TIMEOUT_CONFLICT` 有登记且
   `retryable()` 把它判为可重试，`awaitAll` 也实现了逐源 `get(timeout)` 与 `cancel(true)`，
   但验收 IT 没有真的让一个查询跑到超时（真实 MySQL 上稳定复现超时需要刻意构造慢查询，
   会让 IT 变脆）。该分支目前只有编译期与错误出口层面的覆盖。
5. **来源被截断（`RESULT_TRUNCATED_CONFLICT`）未在真实 MySQL 上触发**。
   D03 的 `truncated` 标记需要上游真的返回超过 `maxRows` 的行集；
   真实链路上 D06 的 `LIMIT` 先一步收窄，因此该分支目前只有单元测试覆盖
   （`AiCrossSourceQueryExecutorTest.refusesTruncatedSourceResultsInsteadOfSummingIncompleteData`）。
   触发不了本身是有意义的：它说明正常链路上不会静默截断，但这不等于该分支已被端到端验证。
6. **没有 Controller 端点与管理端页面**。卡片 §5 的必产是执行器、预算控制、统一结构与容量测试，
   不含协议层；且本卡不新增权限码，因此 `permission-catalog.json` 与
   `前端代码/.../ai-contracts` 均无改动。代价是执行入口目前只能由服务层/IT 使用，
   运维没有可视化入口。若后续要页面，应在补 Controller 端点时一并加，不能只加前端。
7. **容量判据只能用预估扇出行数**（来源数 × 每源行数预算）。真实基数要等查询跑完才知道，
   那时连接与内存已经花掉了。转登记只是**记录**，本卡**没有实现**把登记项重新执行的调度。
8. **V95 只在本卡 IT 的全量迁移链里跑过**（`verify -Pintegration` 会执行全量 Flyway 链，
   已确认 V95 成功），但**未验证**在"带历史数据"的既有库上做增量升级。
   本卡是新增表、不改动既有表结构，风险面很小，但我没有实际演练过。
9. **多来源币种换算仍未实现**。本卡按 Y03 的上游结论执行（多币种无换算规则禁止求和，
    已由 Y03 拦截），但"声明了换算规则之后如何换算"这一环**不在本卡、也未验证**。
10. **未改其它卡在 `service/query` / `adapter/connector` 里的既有实现**。
    本卡对这两处的全部改动都是**新增文件**（见 §9）。

## 9. 范围声明

**结论：全部生产代码改动都在 brief §2 / 卡片 §2 的允许路径内，但有 5 处需要主会话知情**，
均属"领取新迁移号"这一授权的必要配套，按 Y02/Y03 的既有先例处理：

| 路径 | 卡片 §2 是否列出 | 理由 |
|---|---|---|
| `.../module-ai/service/query/crosssource/**`（11 个新文件） | ✅ `service/query` | 本卡主体：执行器、预算、实体键、统一结果、容量准入 |
| `.../module-ai/dal/dataobject/crosssource/*DO.java` | ❌ 未列出 | 新表必须有 DO。**Y02 的卡片同样没有列出 `dal/`，但 Y02 的 commit（`2f4176a`）创建了 `dal/dataobject/semantic/*DO.java` 与 `dal/mysql/semantic/*Mapper.java`**——本卡沿用同一先例：DO/Mapper 是"领取新迁移号"的必要映射层 |
| `.../module-ai/dal/mysql/crosssource/*Mapper.java` | ❌ 未列出 | 同上 |
| `.../module-ai/enums/AiCrossSourceExecutionErrorCodeConstants.java` | ❌ 未列出 | brief §3 **明令**"必须新起一个登记册并让主文件 `extends` 它" |
| `.../db/migration/V95__ai_cross_source_execution.sql` | ❌ 未列出 | 卡片 §2 末句显式授权"DB 仅允许领取新迁移号和同步最新快照"；brief §3 指定 V95 |
| `.../server/src/test/.../integration/AiCrossSourceExecutionAcceptanceIT.java` + `CrossSourceWarehouseFixture.java` | 补充允许"同一变更对应的 src/test" | brief §5 明确要求验收 IT 放在该目录 |
| `数据库文件/basic_framework.sql` | ❌ 未列出 | 快照同步（brief §3 / §4 要求四处台账） |
| `docs/contracts/ai/error-code-map.md` / `data-lifecycle.json` / `data-permission-exemptions.json` | ✅ `docs/contracts` | 错误码三处 + 四处台账同步 |
| `docs/adr/0054-*.md` | ✅ `docs/adr` | 卡片要求 ADR |
| `.../server/src/test/.../integration/PersistenceLifecycleIT.java` | 补充允许"同一变更对应的 src/test" | brief §4 要求同步期望表清单 |

**对其它卡既有实现的改动**（逐个列出）：

| 文件 | 改了什么 | 理由 |
|---|---|---|
| `enums/AiErrorCodeConstants.java` | **只在 `extends` 上挂新登记册**，一个常量都没加 | brief §3 明令；文件 799 → 800 行，仍未超上限 |
| `enums/AiErrorCodeRanges.java` | 新增 `DOMAIN_CROSS_SOURCE_EXECUTION = 1_003_017` 与注释行 | brief §3 明令 |

`service/query` 与 `adapter/connector` 里**其它卡留下的实现没有被改动**：
`AiCompiledQueryExecutor`、`QueryPlanSqlCompiler`、`AiCrossSourceQueryPlanValidator`、
`CrossSourceQueryPlan`、`AiMysqlConnectorService`、`AiMysqlReadOnlyExecutor`、
`AiMysqlSqlGuard` 全部只被**调用**，没有改一行。
`CrossSourceBudget.HARD_MAX_SOURCE_ROWS` 引用了 D03 的 `AiMysqlQueryRequest.MAX_ROWS`
（只读引用，未修改 D03）。

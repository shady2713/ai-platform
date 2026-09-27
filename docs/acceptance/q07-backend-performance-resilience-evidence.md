# Q07 性能与韧性验收 —— 后端切片（完成证据）

| 项目 | 内容 |
|---|---|
| 任务卡 | [Q07 验证容量、慢消费者与任务故障恢复](../ai-platform/tasks/Q07.md) |
| 本切片范围 | 后端验收用例（真实 MySQL/Redis Testcontainers）+ NFR-04 实测 + 本报告（`docs/acceptance`、`docs/operations`）；**不含**前端/浏览器切片（由并行切片交付，见 [前端 + 运维切片报告](q07-frontend-resilience-and-ops.md)，本报告不引用其结果） |
| 状态 | **部分通过**：AT-016/017/018/039 通过（真实测量，19 例中 18 例绿）；**AT-059 有一项不通过**（并发超发：24 并发抢 5 个名额，3 次复跑实测 14、8·9·14、6·8·12 个生效；修复需改生产代码，未授权，见 §6）。NFR-04 受理 P95 189/217 ms（≤500）、状态查询 P95 91/128 ms（≤300）达标 |
| 需求 | FR-09/10/11/31/34；NFR-04/05（阈值取自[产品需求 §6](../ai-platform/02-product-requirements.md) 的已评审值） |
| 依赖 | Q02（配额占位与账本）、O03（租约）、O04（执行）、O05（事件/取消）、O06（查询/重试/清理）、D07（归一化）、D11（黄金集） |
| 工作副本 | `/home/ctyun/桌面/zhongtai/ai-platform` |
| 环境 | Linux 6.8.0-138-generic（x86_64）、16 vCPU、31 GiB 内存（测试 JVM `maxHeap` 8024 MiB）、Java 17.0.20；Testcontainers `mysql:8.4.11` + `redis:7.4.11`（镜像摘要与 `AbstractPersistenceIntegrationTest` 一致）；Docker daemon 可用 |
| 证据层级 | 集成（真实 MySQL/Redis；模型边界在容量用例中按 NFR-04 原文以"Mock 模型固定响应"替换，其余全部真实） |

## 1. 变更文件清单

| 文件 | 说明 |
|---|---|
| `后端代码/basic-framework-boot/basic-framework-server/src/test/java/com/basicframework/server/integration/AiRunResilienceAcceptanceIT.java` | 新增 9 例：AT-016 取消与晚到完成竞争（12 轮并发竞争 + 两种确定性顺序 + 8 路取消风暴）、AT-017 慢消费者（450 事件有界拉取与重连续读、保留窗口过期转快照、卡住消费者下服务可用）、AT-018 进程重启与租约恢复（5 个任务崩溃→恢复→新 worker 完成 + 业务唯一性 + 旧 worker 迟到被拒） |
| `.../integration/AiApiCompletenessAcceptanceIT.java` | 新增 4 例：AT-039 上游第二页 503、重复游标、条目预算截断，以及"完整两页"对照组；断言 `completeStatistics()` 与稳定原因，且截断行的数值不得冒充完整总额 |
| `.../integration/AiQuotaConcurrencyAcceptanceIT.java` | 新增 3 例：AT-059 并发争抢不超发（24 并发 / 上限 5 / 3 轮）、崩溃后租约到期回收、释放风暴幂等且不残留 |
| `.../integration/AiPlatformCapacityIT.java` | 新增 3 例：NFR-04 实测（20 并发受理 P95、20 并发状态查询 P95、20 并发完整运行 + 平台开销拆分），原始测量写入 `target/q07-performance/nfr04-raw-measurements.txt` |
| `docs/acceptance/q07-backend-performance-resilience-evidence.md` | 本报告 |
| `docs/operations/q07-capacity-and-failure-injection.md` | 可重复测量命令、故障注入记录、容量建议 |

未改动：`src/main/**`（生产代码）、迁移与 `数据库文件/**`、`docs/contracts/**`、`.harness/**`、根 `scripts/**`、前端 `packages/**`/`apps/**`。

**路径偏离说明（需协调人确认）**：Q07 §2 列出的测试目录是
`basic-framework-module-ai/src/test/java/com/basicframework/module/ai/{performance,integration}`。
本切片把集成用例放在 `basic-framework-server/src/test/java/com/basicframework/server/integration/`，
原因是：Testcontainers 依赖、Spring 测试装配（`@SpringBootTest` + Flyway + 动态数据源）与
`AbstractPersistenceIntegrationTest` 只存在于 server 模块；module-ai 的 `pom.xml` 无 Testcontainers，
把用例放进 module-ai 需要改 `pom.xml`（依赖版本/父 POM 变更须先列入卡片，本卡未列）。
仓库现有 70+ 个 AI 集成用例全部位于该 server 目录（Q07 §2 的"补充允许：同一变更对应的 src/test"覆盖本情形）。
未在 module-ai 的 `performance`/`integration` 目录留空目录或占位文件。

## 2. 现有覆盖盘点（哪些 AT 已由谁覆盖、本次补了什么）

| AT | 既有覆盖 | 本次补什么 |
|---|---|---|
| AT-016 取消与晚到完成并发 | O05：`AiRunEventIT.cancelWritesTerminalEventAndTerminatesTheTask`（取消写终态事件、重复取消 409）；O04：`AiRunExecutionIT.lateExecutionCannotOverwriteTheTerminalState`（终态后重复执行 409）；`AiRunEventServiceImplTest.cancelLosesTheRaceToAnotherTerminalWrite`（Mockito 单测层竞争）；C02 前端侧（组件级） | **真实 MySQL 并发竞争**：取消与终态写入同时发生（12 轮，两种顺序都真实出现过），断言"恰好一个生效、终态唯一、助手结果恰好一份、版本只推进一次、终态事件不重复"；另加 8 路取消风暴与两种确定性顺序 |
| AT-017 慢消费者 | O05：`AiRunEventIT.replayOutsideTheRetainedWindowFails...`（窗口过期与快照出口，单测另有 `replayIsBoundedAndAdvancesBySeq`）；前端切片：真实浏览器慢速/乱序回放、断流、连接重置 | **服务端有界性与可用性**：450 条积压下单次拉取严格 ≤200（实测 200/200/50/0，共 4 次拉取，无丢无重）；落后窗口转快照后从窗口内续读；卡住消费者期间 20 个并发探测（状态查询）全部成功（实测最慢 28–39 ms），积压行数不被改写 |
| AT-018 进程重启与任务租约 | O03：`AiTaskLeaseIT`（租约期内不重复领取、并发领取唯一、过期恢复、旧 worker 迟到被拒、重试上限、身份重建） | **重启恢复的业务面**：5 个已受理任务在"崩溃→租约过期→恢复 Job→新 worker 领取"后全部恢复并完成（尝试次数 2、代次 2）；业务唯一性（一运行一任务、一幂等记录、一助手结果）；重启后重放同一幂等键复用原运行、不新增运行/任务 |
| AT-039 API 分页截断/失败 | D07：单测（项目预算截断、失败上游保留原因、重复游标降级）；D11/`AiGoldenSetAcceptanceIT.apiEntryReportsPartialWhenPaginationIsTruncated`（页数上限 → PARTIAL） | **集成层失败与截断语义**：上游第二页 503 → FAILED + `HTTP_503` + `completeStatistics()=false`，且行值只有第一页的 300.00（不是完整的两页 450.00）；重复游标 → PARTIAL + `repeated-cursor`；条目预算 1 → PARTIAL + `item-limit`；"完整两页 → COMPLETE"作对照组 |
| AT-059 并发配额与异常释放 | Q02：`AiQuotaServiceTest`（Mockito 层：上限、幂等重入、到期回收、续租失败语义、释放幂等）、`AiUsageLedgerIT`（真实 MySQL：到期回收后可再次获取） | **真实并发争抢**：24 并发抢 5 个名额，3 轮 × 3 次复跑全部**超发**（14/5、8·9·14/5、6·8·12/5，见 §3.3）；崩溃（租约到期）后名额可重新占满；20 路释放风暴幂等、无残留、名额可复用 |

## 3. 逐条 AT：命令、退出码与实测结果

统一前置（本机、授权副本）：

```bash
cd /home/ctyun/桌面/zhongtai/ai-platform/后端代码/basic-framework-boot
umask 022
export JAVA_HOME=$HOME/.local/opt/jdk17/usr/lib/jvm/java-17-openjdk-amd64
# 改测试后必须先格式化，否则门禁在 spotless:check 处停止
./mvnw -q -o -pl basic-framework-server spotless:apply
```

### 3.1 AT-016 / AT-017 / AT-018

```bash
./mvnw -o -pl basic-framework-server verify -Pintegration -Dit.test='AiRunResilienceAcceptanceIT'
```

| 项 | 结果 |
|---|---|
| 退出码 | **0**（BUILD SUCCESS） |
| 用例 | **9 例通过 / 0 失败**（`target/failsafe-reports/com.basicframework.server.integration.AiRunResilienceAcceptanceIT.txt`） |
| 原始测量 | 各次运行的控制台 `Q07-MEASURE` 行：`AT-016 race rounds=12` 的 `cancelWins/completionWins` = 2/10、0/12、9/3、3/9；`AT-017 backlog=450 pulls=4 boundedBatch=200`；`AT-017 stalledBacklog=300 probes=20 worstProgressMs=28..39`；`AT-018 recoveredTasks=5 recoveryScanMs=47..85 recoveryAttempt=2` |

**AT-016（终态一致、无重复结果）**：12 轮"取消线程 vs 终态写入线程"同栅栏起跑，每轮断言
`cancelled ^ completed == true`（恰好一个生效）、运行状态等于胜者、运行版本恰好推进到 1、
助手消息数恰为 `completed ? 1 : 0`、任务状态与胜者一致、终态事件恰为取消胜出时的 1 条 CANCELLED。
多次实跑的胜者分布不同（`cancelWins/completionWins` = 2/10、0/12、9/3），说明两种顺序都真实出现过，
且断言对任意交错成立。确定性用例另行固定两种顺序：
取消先 → 晚到完成写 0 行、不写助手结果、任务 `FAILED/CANCELLED`；完成先 → 晚到取消稳定
`AI_RUN_ALREADY_TERMINAL`、不追加事件。取消风暴（8 路）只有 1 路生效、只落 1 条终态事件。

**AT-017（有界内存、转重连/查询、服务可用）**：先落 450 条事件（远大于服务端单批上限 200）。
`replay(..., limit=10_000)` 每次严格返回 ≤200 条（实测 200/200/50，末次为空），断言"至少 3 次拉取"；
按 seq 累积后共 450 条、严格升序、无重复（客户端按 seq 去重成立）。保留期清理掉前 300 条后，
客户端停在 seq=10 时拿到稳定错误 `AI_RUN_EVENT_WINDOW_EXPIRED`，快照给出 `latestSeq=450`、
`earliestSeq=301`，从 300 续读拿到 150 条（"转查询"出口可用）。卡住消费者（只拉一批就停止）期间，
20 个并发状态查询全部成功（最慢 28–39 ms，上限断言 3000 ms），积压事件的库内行数保持 300 不变。

**AT-018（重启恢复、业务唯一性）**：5 个运行各 1 个任务，worker 领取后"崩溃"（不心跳、不落库），
租约逐个置为过期 → `countActiveLeases()==0` → 新进程的恢复扫描（`AiTaskRecoveryJob` 实例）把 5 个任务
全部放回 QUEUED 且 `lease_owner IS NULL`（恢复扫描 47–85 ms）→ 新 worker 一次领取 5 个
（`attempt=2`、`epoch=2`）并全部完成。断言：每个运行只有 1 个任务、1 条助手结果、状态 SUCCEEDED；
重启后重放同一幂等键返回**原运行**（`reused=true`），运行数与幂等记录数都保持 5。
另一用例：旧 worker 的落库命中租约栅栏 0 行，且走真实执行入口 `AiRunExecutionService.execute`
直接 `AI_RUN_ALREADY_TERMINAL`——不重跑模型、不写第二份结果。

> **"重启"是模拟，不是真重启 JVM**：按 Q07 卡片与本次任务约定，用"租约过期 + 恢复 Job + 新 worker 领取"
> 模拟进程重启（`AiTaskRecoveryJob` 以新实例执行、worker 标识切换）。真实 JVM/容器重启与
> 可执行 jar 重启语义不在本切片验证范围（见 §7）。

**fail → pass 过程（真实发生过）**：本类首轮运行 9 例全红，两类原因分别是——会话业务键前缀
`conv-` 不符合 `AiFieldRules.PATTERN_CONVERSATION_KEY`（`^conv_[A-Za-z0-9_-]{3,35}$`），以及
AT-018 里"一次 claim 取走全部可领取任务"导致后续 `claimTaskOf` 找不到任务；另有一处用例误用内部
写入器（用**最新**运行快照调用 `AiRunTerminalWriter`），暴露了"绕过执行入口时乐观锁不构成防线"，
已改为走真实执行入口 `execute`。修好后 9/9 通过。

### 3.2 AT-039

```bash
./mvnw -o -pl basic-framework-server verify -Pintegration -Dit.test='AiApiCompletenessAcceptanceIT'
```

| 项 | 结果 |
|---|---|
| 退出码 | **0** |
| 用例 | **4 例通过 / 0 失败**（报告同名 txt） |
| 关键断言 | 对照组两页 → `COMPLETE` 且 450.00；第二页 503 → `FAILED`、`reason=HTTP_503`、`completeStatistics()=false`、`pages=2`、行值 300.00（≠ 完整 450.00）；重复游标 → `PARTIAL`、`reason=repeated-cursor`、上游只被调用 2 次；条目预算 1 → `PARTIAL`、`reason=item-limit`、`sourceItems=1`、行值 300.00 |

**fail → pass 证据（负向对照）**：把断言临时改成"失败也必须 COMPLETE / 可以宣称完整"以及
"截断原因应为 no-more-pages"，同命令定向复跑得到 2 例失败，实测值分别是
`expected: "COMPLETE" but was: "FAILED"`、`expected: "no-more-pages" but was: "item-limit"`
（见 §5 负向对照记录）。恢复断言后复跑 4/4 通过——说明这些断言是"载荷"的，不是空跑。
AT-039 的 **UI 证据**属前端切片（其报告 §3 已给浏览器实测），本报告只承担 API 侧。

### 3.3 AT-059

```bash
./mvnw -o -pl basic-framework-server verify -Pintegration -Dit.test='AiQuotaConcurrencyAcceptanceIT'
```

| 项 | 结果 |
|---|---|
| 退出码 | **1**（BUILD FAILURE：3 例中 1 例失败） |
| 通过的 2 例 | `crashedHolderReleasesItsSlotThroughLeaseExpiry`（崩溃→租约到期回收→名额可重新占满；被回收占位续租返回 false）、`releaseStormIsIdempotentAndLeavesNoStuckSlot`（20 路并发释放同一批占位，`activeCount=0`、无残留、名额可再次占满） |
| **失败的 1 例** | `concurrentAcquireNeverOversubscribesTheConfiguredLimit`：24 并发争抢 `limit=5`，3 轮全部**超发** |

原始测量（失败信息内，三次复现）：

```
运行 1：round=0 winners=14 activeCount=14 activeRows=14 limit=5（首轮即超发，断言在 round=0 停止）
运行 2：round=0 winners=8  round=1 winners=9  round=2 winners=14（limit=5）
运行 3：round=0 winners=6  round=1 winners=8  round=2 winners=12（limit=5）
```

结论：**AT-059"不超发"不通过**。`winners == activeCount == 表中的有效占位行数`，说明超发是真实落库的，
不是计数口径问题。同一命令定向复跑 3 次，每次 3 轮全部超发（14/5、8·9·14/5、6·8·12/5），不是偶发抖动。

根因（代码走查，`AiQuotaServiceImpl.acquire`，`basic-framework-module-ai/src/main/java/.../service/quota/`）：
先 `countActive(...) < limit` 再 `insert`，两语句之间没有锁或原子约束；不同 `invocation_id` 之间
不存在唯一键冲突，因此并发下多个事务能同时读到"还有空位"的快照并各自插入。
`ai_quota_lease.lease_key` 唯一键只保证**同一调用**不重复占位，不保证并发上限。
Q02 证据中"并发请求不突破配置（服务层 + 唯一键兜底）"的推论在真实并发下不成立（其 IT 是顺序调用，
未做并发争抢）。

**需要生产代码改动（本卡未授权，未改）**，任选其一：

1. 把"判定 + 占位"合成一条原子语句，例如
   `INSERT INTO ai_quota_lease (...) SELECT ... WHERE (SELECT COUNT(*) FROM ai_quota_lease WHERE application_id=? AND state='ACTIVE' AND lease_until>?) < ?`
   （`INSERT ... SELECT` 在 InnoDB RR 下对读取行加锁，可串行化同应用的并发占位）；
2. 增加"应用配额计数行"，用单条 `UPDATE ... SET used = used + 1 WHERE used < :limit` 做 CAS 占位
   （需要新迁移与快照同步）；
3. 或对应用中立行（如 `ai_application` 行）先 `SELECT ... FOR UPDATE` 再计数插入（需要应用行存在，成本最高）。

修复后本用例应转为通过；在此之前，AT-059 的"不超发"结论保持**不通过**。
补充事实（与并行切片一致）：`AiQuotaService.acquire` 目前在 `src/main` 内没有调用方
（管理端只用 `activeCount`），所以该超发尚不能从对外请求路径触发，但服务契约与 AT-059 已要求
"不超发"，属必须在接线前修掉的缺陷。

### 3.4 NFR-04 基础性能（20 并发 run）

```bash
./mvnw -o -pl basic-framework-server verify -Pintegration -Dit.test='AiPlatformCapacityIT'
```

| 项 | 结果 |
|---|---|
| 退出码 | **0** |
| 用例 | **3 例通过 / 0 失败** |
| 原始结果 | `后端代码/basic-framework-boot/basic-framework-server/target/q07-performance/nfr04-raw-measurements.txt`（每次运行覆盖写入，保留最近一次运行的 10 行原始记录；`clean verify` 会清空，重跑命令见运营文档） |

机器与夹具（同一次运行记录）：`availableProcessors=16`、`maxHeapMiB=8024`、
`os=Linux 6.8.0-138-generic`、`arch=amd64`、`java=17.0.20`、`mysql:8.4.11`、`redis:7.4.11`、
并发 20、固定模型响应 50 ms。

两次独立运行（同一命令、同一机器）：

| 测量项 | 样本 | 运行 1 P50/P95/P99/max | 运行 2 P50/P95/P99/max | 阈值 | 结论 |
|---|---:|---|---|---|---|
| 平台受理（`AiRunService.accept`，20 并发） | 40 | 156 / **189** / 196 / 196 ms | 196 / **217** / 236 / 236 ms | P95 ≤ 500 ms（NFR-04） | **达标**（两次） |
| 状态查询（`AiTaskService.progress`，20 并发 ×10 轮） | 200 | 10 / **91** / 111 / 118 ms | 9 / **128** / 141 / 150 ms | P95 ≤ 300 ms（NFR-04） | **达标**（两次） |
| 完整运行端到端（固定 50 ms 模型响应，20 并发） | 20 | 340 / 355 / 355 / 355 ms | 447 / 463 / 466 / 466 ms | 预算上限 120 s（仅作护栏） | 全部 SUCCEEDED（两次） |
| 平台开销（端到端 − 固定模型 50 ms） | 20 | 290 / 305 / 305 / 305 ms | 397 / 413 / 416 / 416 ms | 无已评审阈值 | 单独记录（见下） |

两次运行的差值（受理 P95 189→217 ms、执行端到端 355→466 ms）说明本机数字有 10–30% 的运行间波动
（容器、JVM 预热、其它进程），因此**结论只按阈值判定，不把单次数字当成容量承诺**。

口径说明：
- **受理口径**：计时区间是 `runService.accept(...)`（幂等判定 + 运行/任务/消息落库 + 版本固定）；
  会话票据签发（每线程一次）在计时区间之外。测量前做 5 次串行预热（连接池/语句缓存），预热不计入样本
  ——NFR-04 未规定预热口径，此处显式记录。
- **状态查询口径**：计时区间是 `taskService.progress(runId)`（按主体过滤 + 任务/结果引用查询）。
- **模型与数据源耗时单独记录**：模型边界按 NFR-04 原文替换为"Mock 模型固定响应"（`@MockitoBean`，
  固定 50 ms + 固定文本），因此"端到端 − 50 ms"即平台自身开销（身份重建、发布版本重新判定、
  输入回放、上下文构建、外发策略、终态写入 + 1 次模型往返之外的数据库往返）。数据源（MySQL 连接器）
  的耗时不在本次测量范围（本用例链路是文本运行，不触发连接器）。
- **硬件可比性**：NFR-04 的建议基线是 8 vCPU/16 GB；本次在 **16 vCPU/31 GiB**（测试 JVM 堆上限 8 GiB）
  上测得。硬件优于基线，**达标结论只对本机配置成立，未在 8 vCPU/16 GB 环境复测**；不承诺该数字适用于
  其它硬件或真实模型延迟。

## 4. 负向对照（证明断言"载荷"，即先失败后通过）

同一条命令定向复跑（`-Dit.test='类#方法+方法'`），只把断言临时改成"错误结论"，观察失败，再恢复：

| 对照 | 临时断言 | 实测失败信息 | 恢复后 |
|---|---|---|---|
| AT-016 | `cancelled ^ completed` 断言为 false | `Expecting value to be false but was true` | 9/9 通过 |
| AT-017 | 单次拉取返回全部 450 条 | `expected: 450 but was: 200` | 9/9 通过 |
| AT-018 | 恢复后仍是第 1 次尝试 | `expected: 1 but was: 2` | 9/9 通过 |
| AT-039（失败页） | 失败也必须 `COMPLETE` 且可宣称完整 | `expected: "COMPLETE" but was: "FAILED"` | 4/4 通过 |
| AT-039（截断） | 截断原因应为 `no-more-pages` | `expected: "no-more-pages" but was: "item-limit"` | 4/4 通过 |

负向对照运行退出码为 1（预期红），恢复后退出码为 0。AT-059 的"先失败"即 §3.3 的真实失败，属**未修复**，
不是负向对照。

## 5. 复跑与稳定性

最终状态由一条合并命令复跑（4 个类、19 例）：

```bash
./mvnw -o -pl basic-framework-server verify -Pintegration -Dit.test='AiRunResilienceAcceptanceIT,AiApiCompletenessAcceptanceIT,AiPlatformCapacityIT,AiQuotaConcurrencyAcceptanceIT'
#   → 退出码 1：18 例通过，1 例失败（AT-059 超发，见 §3.3）
#   → AiRunResilienceAcceptanceIT 9/9、AiApiCompletenessAcceptanceIT 4/4、AiPlatformCapacityIT 3/3、AiQuotaConcurrencyAcceptanceIT 2/3
```

| 命令 | 次数 | 退出码 | 备注 |
|---|---:|---|---|
| `AiRunResilienceAcceptanceIT` | 6 | 1 → 1 → 0 → 1（负向对照，预期红）→ 0 → 0（合并复跑） | 两次红是**用例缺陷**（会话业务键前缀不合规、AT-018 一次领取语义 + 误用内部写入器），修好后稳定通过；AT-016 胜者分布在不同运行中不同（2/10、0/12、9/3、3/9），断言对任意交错成立 |
| `AiApiCompletenessAcceptanceIT` | 4 | 0 → 1（负向对照，预期红）→ 0 → 0（合并复跑） | 含负向对照与恢复后复验 |
| `AiPlatformCapacityIT` | 2 | 0 → 0（合并复跑） | 两次均达标；数字有 10–30% 运行间波动（见 §3.4） |
| `AiQuotaConcurrencyAcceptanceIT` | 3 | 1 → 1 → 1（合并复跑） | AT-059 超发稳定复现（14/5、8·9·14/5、6·8·12/5），非偶发 |

未观察到"负载敏感"导致的不稳定用例（超时/偶发失败）。

## 6. 未验证项与未通过项

1. **AT-059 不超发：不通过**（见 §3.3）。需要改生产代码（`AiQuotaServiceImpl`），本卡未授权，未改；
   停在该结论并等授权。
2. **真实 JVM/容器重启**：AT-018 用"租约过期 + 恢复 Job + 新 worker"模拟重启，未做真实进程重启与
   可执行 jar 重启（Q07 §2 的 `Testcontainers+jar` 完整形态未做；本卡未授权新的 jar 冒烟脚本）。
3. **长时间/大规模压测**：未做（无法在本机真实执行且不伪造数字）。本次只做 20 并发（NFR-04 的并发维度）
   与 450 条积压的有界性断言；**没有**做 24 小时稳定性、数千并发、真实模型长尾延迟、
   真网关/多机/外部模型压测。AT-017 的"有界内存"是通过"单次拉取严格 ≤200 + 服务端不保留未确认缓冲
   （卡住消费者不改变积压、不阻塞其它请求）"的行为断言证明的，不是 JVM 堆直方图或容器 RSS 曲线。
4. **AT-017 的 JVM 内存上界**：未用 `jcmd`/`-Xmx` 压出 OOM 边界；"有界"只到"每次拉取有界 + 无服务端积压缓冲"。
5. **背压推送**：服务端是"轮询重放 + 心跳注释 + 连接 5 分钟上限"的有界实现（O05），没有做"服务端主动推送
   与背压"的验收（该能力不存在，不应假装已验证）。
6. **AT-039 的 UNKNOWN 分支**：AT-039 文案含 "PARTIAL/UNKNOWN"；后端归一化的完整性闭集是
   `COMPLETE/PARTIAL/FAILED`（`AiNormalizedResult`），本次未构造出"UNKNOWN"结论（未找到产生路径）；
   UI 侧的 UNKNOWN 显示由前端切片覆盖。此差异如实记录，不声称已覆盖 UNKNOWN。
7. **2 vCPU/8 GB 等更差硬件与 CI 环境**：未测；本报告数字只对本机配置成立。
8. **数据源（MySQL 只读连接器）耗时**：NFR-04 要求"模型与数据源耗时单独记录"，本次链路不含数据源调用，
   未测；D03/D06 的证据有其自身耗时口径。

## 7. 与卡片"必须产出"的对应

| 卡片 §5 产出 | 本切片交付 |
|---|---|
| 可重复压测脚本 | 4 个 IT 类（Maven 一条命令可重复执行，含 NFR-04 测量与故障注入）；命令与原始结果路径见 [运营文档](../operations/q07-capacity-and-failure-injection.md) |
| 原始结果 | `basic-framework-server/target/q07-performance/nfr04-raw-measurements.txt`（每次运行覆盖）+ 本报告表格 + `target/failsafe-reports/*.txt` |
| 故障注入记录与容量建议 | [docs/operations/q07-capacity-and-failure-injection.md](../operations/q07-capacity-and-failure-injection.md)（取消风暴、断流、窗口过期、租约过期/恢复、崩溃占位、释放风暴；容量建议与判读边界）。队列积压的可观测手段见并行切片的 [queue-backlog-and-resilience-observability.md](../operations/queue-backlog-and-resilience-observability.md)（不重复） |

## 8. 需要串行处理的事项

1. **AT-059 修复授权**（改 `basic-framework-module-ai/src/main/java/.../service/quota/AiQuotaServiceImpl.java`，
   方案见 §3.3；如选"计数行"方案还需新迁移 + 快照同步 + 生命周期台账，属 Q02/Q09 路径）。修复后本用例应转绿。
2. **确认测试目录偏离**（§1 末）：接受 `basic-framework-server/src/test/.../integration/`，或另行授权改
   `basic-framework-module-ai/pom.xml` 以支持 module-ai 内的 Testcontainers。
3. **覆盖率棘轮**：本切片只新增测试文件（不计入覆盖率棘轮），未新增生产文件；`integration` 门禁的
   棘轮步骤由协调人统一跑 `.harness/verify.sh`（我不跑整套）。
4. **门禁与提交**：由协调人执行 `.harness/verify.sh contracts/backend/integration` 与提交
   （我不 commit/push）。`AiQuotaConcurrencyAcceptanceIT` 会让 integration 门禁变红——这是 AT-059
   真实不通过的可见状态；若要临时绿，需先授权修复生产代码，而不是放宽断言。

## 3.3.b 状态更新（主管追加，2026-09-27）

**AT-059 已修复并回归通过**（本次为越界修复：改动含 `service/quota` 之外的 2 个 DAL Mapper，已登记供复核）：

- 方案：`AiQuotaServiceImpl.acquire` 在 count+insert 前取**应用行悲观锁**（`SELECT ... FOR UPDATE`；应用行缺失时用 `ai_quota_lease` 的 `#quota-gate#` 闸门行），计数改用**加锁读**；不需要迁移/快照/台账。
- **对照实验**（证明加锁读是必要而非保险）：只加应用行锁、计数用普通读 → 依然超发（`winners=13/24/24`）；改为 `FOR UPDATE` 计数 → 修复后三轮均 `winners=5 activeCount=5 activeRows=5 limit=5`。
- 修复前失败输出（退出码 1）：`Expecting actual: 15 to be less than or equal to: 5`；修复后：`AiQuotaConcurrencyAcceptanceIT` 3/3 通过。
- 回归：`AiQuotaServiceTest` 9/9、`AiQuotaConcurrencyAcceptanceIT+AiUsageLedgerIT+AiRunResilienceAcceptanceIT` 14/14、module-ai 全模块 858/858。
- 风险：同一应用的 acquire 在毫秒级临界区串行（跨应用零干扰）；调用方应在**独立短事务**内调用 acquire；闸门行会长期存在一条 `#quota-gate#`（不参与占用口径）。

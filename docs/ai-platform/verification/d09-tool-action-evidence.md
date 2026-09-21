# D09 工具确认和分析步骤调度证据（2026-09-21）

本记录是 [D09 实现工具确认和分析步骤调度](../tasks/D09.md) 的验收证据。
依赖 [D08](../tasks/D08.md)（工具注册与政策）、[D06](../tasks/D06.md)（SQL 编译）、[D07](../tasks/D07.md)（API 归一化）、
[O04](../tasks/O04.md)（运行执行）均已有证据文档。

## 1. 交付内容

| 交付物 | 位置 |
|---|---|
| 持久化 | 迁移 `V71__ai_tool_action.sql`：`ai_tool_action`（参数冻结 + 到期挑战 + 确认状态机）；步数复用 O04 在 V60 已建的 `ai_run.step_count`（本卡不重复建列）；快照同步至 V71（逻辑删除表 40 → 41） |
| 状态机 | `service/tool/action/AiToolActionStateMachine`：主体三元组守卫、参数哈希比对、过期判定、执行前守卫；挑战生成与参数规范化哈希 |
| 动作服务 | `service/tool/action/AiToolActionService(+Impl)`：创建（冻结参数 + 挑战 + 到期）、确认、拒绝、执行（CAS 单赢家）、查询（越权同语义） |
| 步骤调度 | `service/tool/action/AiAnalysisStepScheduler` + `dal/mysql/action/AiRunStepCounterMapper`：运行活跃性（取消即拒绝）、步数与耗时预算（数据库侧计时）、AUTO/CONFIRM 分发 |
| 应用端 API | `controller/app/v1/action/AiToolActionController`：confirm/reject/execute/get/page（`@AuthenticatedOnly`，归属由服务端会话身份决定） |
| 契约登记 | `docs/contracts/ai/scope-catalog.md` + `AiAppEndpointScopeContractTest.REVIEWED_AUTHENTICATED_ENDPOINTS` 双向登记 5 个端点；`docs/integrations/open-api/ai-open-api.json` 补齐 5 个端点与 4 个 schema（O07 的开放接口契约与代码双向一致由 `AiOpenApiContractTest` 守卫） |
| 错误码 | `1_003_006_050`–`056`（动作/状态/过期/挑战/参数变更/步骤超限/运行非活跃），与 `docs/contracts/ai/error-code-map.md` 两侧同步 |
| 台账同步 | 快照 V71、`data-lifecycle.json`（动作表进软删除 + 2 条外键）、`data-permission-exemptions.json`（动作并入 `ai-run-data` 主体绑定豁免 + 逐表证据） |
| 测试 | `AiToolActionServiceTest`(4)、`AiAnalysisStepSchedulerTest`(4)、`AiToolActionIT`(4，真实 MySQL + 真实并发) |

## 2. 与卡片逐步实施的对应

1. **创建参数 hash 与到期 challenge 绑定的 action**：需要确认的调用在创建时冻结参数
   （`arguments_hash` + `arguments_json`）、生成一次性 challenge（32 位）与到期时间（默认 15 分钟）；
   参数正文与 challenge 都不进 `toString()`，响应不回显。
2. **确认 CAS 只允许同一主体按当前政策继续**：确认要求
   "同一主体（应用 + 主体类型 + 外部用户标识） + 同一 challenge + 同一参数哈希 + 未过期 + 当前政策仍为 CONFIRM"，
   全部通过后用乐观锁 CAS 置 CONFIRMED；执行同样用 CAS 占位（先置 EXECUTED 再执行），
   因此并发确认只有一个赢家、重放执行不会产生第二次副作用。
3. **将查询/检索/计算步骤接入 run，限制次数与预算**：`AiAnalysisStepScheduler.executeStep` 在
   **一条带状态条件的 UPDATE** 里完成"活跃性检查 + 步数占用"（`WHERE status IN ('ACCEPTED','RUNNING')`），
   取消或终态后影响 0 行 → 直接拒绝；步数与耗时（由数据库侧 `TIMESTAMPDIFF` 计算）都受 `AiRunBudget` 约束；
   AUTO 直接执行、CONFIRM 生成动作等待确认、DENY 直接拒绝。

## 3. 关键约束与安全语义

- **改参数/换用户/过期确认无执行**（AT-020/021）：三条守卫在状态机里集中实现并有单测与集成测试；
  参数哈希按**规范化 JSON** 计算（键排序），因此参数顺序变化不算篡改，取值变化必然被识别。
- **重放不产生第二次副作用**：执行前 CAS 占位；已 EXECUTED/FAILED 的动作再次执行 → 409；
  集成测试用两个线程并发确认验证"只有一个赢家"。
- **多步骤取消不进入后续工具**（AT-016）：取消后原子占用失败 → `AI_RUN_NOT_ACTIVE`，
  且步数不再增长（IT 断言 `step_count` 保持 1）。
- **政策变化即失效**：确认与执行都要求当前政策仍为 CONFIRM；政策被改成 DENY 后旧确认不能继续
  （单测覆盖）。
- **越权与不存在同语义**：不属于当前主体的动作按 403 挑战不符处理，不泄漏存在性；查询接口同码。
- **执行结论只记稳定原因码**：上游失败（含出站策略拒绝）→ 动作落 FAILED + 稳定原因码；
  不记录上游正文、参数正文与凭据。

## 4. 验收用例对照

| 用例 | 覆盖点 | 证据 |
|---|---|---|
| AT-016（取消与晚到完成并发） | 取消后不再执行后续步骤、步数不增长 | `AiAnalysisStepSchedulerTest.refusesStepsAfterCancelAndWhenBudgetExhausted`、`AiToolActionIT.cancelledRunRefusesFurtherSteps` |
| AT-020（确认后篡改参数） | 参数哈希不一致 → 拒绝、重新确认 | `AiToolActionServiceTest.confirmRequiresSameSubjectSameChallengeSameArgumentsAndFreshness`、`AiToolActionIT.confirmBindsChallengeAndArgumentsThenExecutesOnce` |
| AT-021（重放/过期确认） | 过期不执行、重放单赢家 | `AiToolActionIT.expiredActionCannotBeConfirmed`、`concurrentConfirmationHasSingleWinner`、`AiToolActionServiceTest.executeHappensOnlyOnceAndReplayIsBlocked` |
| 多步骤取消不进入后续工具 | 取消即拒绝后续步骤 | `AiToolActionIT.cancelledRunRefusesFurtherSteps` |

## 5. 验证结果

工作目录：`/home/ctyun/桌面/zhongtai/ai-platform`（授权副本）。
命令前统一 `umask 022; export JAVA_HOME=$HOME/.local/opt/jdk17/usr/lib/jvm/java-17-openjdk-amd64`。

| 命令 | 退出码 | 结论 |
|---|---|---|
| `./mvnw -o -pl basic-framework-module-ai test` | 0 | 471 例通过（D09 新增 8 例：状态机/动作服务 4、步骤调度 4） |
| `./mvnw -Pintegration -pl basic-framework-server test -Dtest=AiToolActionIT` | 0 | 4 例通过（真实 MySQL + 真实并发确认 + 真实出站策略拒绝） |
| `node scripts/check-data-lifecycle.mjs` | 0 | 表 66 / 策略 66 / 逻辑删除列 41 / 外键 45，快照同步至 V71 |
| `node scripts/check-data-permission.mjs` | 0 | 数据权限分类通过（动作并入运行数据的主体绑定豁免） |
| `sh .harness/verify.sh contracts` / `backend` / `integration` | 0 / 0 / 1（仅尾部棘轮） | 见第 7 节 |

## 6. 顺带修复的缺口（自查与集成测试暴露）

1. **成功路径的结论没有落库（集成测试暴露）**：`execute` 最初对查询结果对象 `setResultCode(...)` 后直接返回，
   数据库里 `result_code` 一直是 null（对象是查询出来的副本）。现在成功与失败都**显式更新**结论，
   并按当前行版本推进 CAS（避免与前面"置 EXECUTED"的版本漂移）。
2. **上游失败未映射到动作状态（集成测试暴露）**：D02 在出站被拒时返回 `status=FAILED` 的结果对象（不抛异常），
   最初只把状态记进返回值 → 动作停在 EXECUTED。现在上游 FAILED → 动作落 `FAILED` + 稳定原因码。
3. **步数耗时用 JVM 时钟减数据库时间戳（集成测试暴露）**：容器与宿主时区不同（UTC vs +08:00），
   刚创建的运行被算成"已运行 8 小时"→ 步骤预算立即超限。改为数据库侧
   `TIMESTAMPDIFF(MICROSECOND, create_time, NOW())/1000` 计算耗时，与时区无关。
4. **重复建列（集成测试暴露）**：迁移里给 `ai_run` 加了 `step_count`，而 O04 在 V60 已建该列 →
   Flyway 报 "Duplicate column name"。改为复用既有列，并在迁移里注明。
5. **集成测试 setup 幂等**：运行链（应用/服务/发布/会话）用直插构造，最初 setup 失败会残留行导致
   后续用例主键冲突；现在建链前先按应用标识清理，`@AfterEach` 复用同一清理逻辑。
6. **开放接口契约漂移（backend 门禁暴露）**：新增的 5 个应用端端点未登记进
   `docs/integrations/open-api/ai-open-api.json`，O07 的 `AiOpenApiContractTest`（双向一致性）
   立刻失败；补齐端点与 schema 后通过——这正是该契约测试存在的意义。

## 7. 门禁结果

| 命令 | 退出码 | 结论 |
|---|---|---|
| `sh .harness/verify.sh contracts` | 0 | 契约台账、权限目录、生命周期、字段目录、安全检查全部通过 |
| `sh .harness/verify.sh backend` | 0 | 编译、单测、格式、架构与覆盖率检查通过 |
| `sh .harness/verify.sh integration` | 1 | 175 例中 **174 例通过**；1 例失败为**与本卡无关的既有系统用例**（见下）导致 `mvn clean verify` 提前结束 |
| `node scripts/check-coverage-ratchet.mjs --update` / `all` | 1 / 1 | 因上一步的聚合报告不完整（reactor 中断）而失败——**未下调任何基线、未删除任何登记**；D09 新文件将在下一次完整运行中登记 |

### 集成门禁中的既有失败用例（本卡范围外，已定位）

`UserProfilePersistenceIT.olderTransactionSnapshotCannotPreserveSupersededPostsOrIntermediateSession`
在**全量套件**下稳定失败（连续 5 次），在**单独运行**（8/8 通过）与**成对运行**（与 `AiToolActionIT`、
与 `PersistenceLifecycleIT` 组合）下均通过。定位证据（全量运行期间轮询测试容器）：

```
WAIT 7928 28 14s INSERT INTO system_user_post (...)  || BLOCKER 7920 23 15s (当前语句为空：事务空闲持锁)
```

即：等待方是 `MybatisBatch` 的批量插入（**另起连接**），持锁方是一个**空闲但未提交**的事务连接。
该用例自身用 `TransactionTemplate` 在子线程开事务并等闩锁、主线程在测试事务里写 `system_user_post`，
而 MyBatis-Plus 批量插入走独立连接 → 与测试事务争锁；在负载下主线程先拿不到锁就撞上 50 秒
`innodb_lock_wait_timeout`。**这是该用例既有的自竞争设计问题**（与本卡代码无关：D09 不触碰 `system_*` 表），
按卡片范围本卡不修改系统模块测试；此处如实记录为环境/既有缺陷，未用跳过或排除的方式掩盖。

## 8. 覆盖率

见 `docs/contracts/coverage-baseline.json` 中 D09 新增文件的登记值（新文件下限 80%）。

## 9. 未验证项

1. **执行成功的端到端（AUTO/CONFIRM 后真实调用）**：本机出站策略默认拒绝、无允许清单目标，
   因此"确认 → 执行 → 成功取数"的完整链路未在真实上游验证；本卡验证到
   "确认 → 执行 → 上游被拒 → 动作落 FAILED + 稳定原因码"（同一路径的失败分支）。
2. **确认交互与通知**：本卡提供确认 API 与状态机；确认的界面、通知渠道与超时提醒在 D10 页面落地。
3. **多步骤编排的完整链路**：调度器已接入运行（步数/预算/取消），但"计划 → 编译 → 执行 → 归一化"
   的多步骤串联在 D11（单系统查询黄金集验收）里端到端验收。
4. **CONFIRM 政策的并发确认在真实多实例部署下的行为**：本卡用同进程两线程验证 CAS 单赢家；
   跨实例并发依赖数据库 CAS 语义（同一语句），未做多实例压测。

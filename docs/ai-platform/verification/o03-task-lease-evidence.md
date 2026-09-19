# O03 可恢复任务领取与租约证据（2026-09-19）

本记录是 [O03 实现可恢复任务领取与租约](../tasks/O03.md) 的验收证据。

## 1. 交付内容

| 交付物 | 位置 |
|---|---|
| 持久化 | 迁移 `V61__ai_run_task_lease.sql`：`ai_run_task` 增加租约列（`lease_owner`/`lease_expires_time`/`heartbeat_time`/`claimed_epoch`/`max_attempts`/`last_error_code`）与 `idx_ai_run_task_lease`；注册 `infra_job` 32 `aiTaskRecoveryJob`（每分钟）；快照同步至 V61 |
| 任务调度器 | `service/task/AiTaskService(+Impl)`：`claim`（CAS 领取）、`heartbeat`（续租）、`finish`（落库并释放租约）、`recoverExpiredLeases`（恢复）、`countActiveLeases`、`rebuildIdentity`（重建可信身份） |
| 领取与租约 SQL | `dal/mysql/task/AiTaskClaimMapper`：`selectClaimable`、`claim`、`heartbeat`、`finish`、`recoverExpired`、`countActiveLeases` 六条带栅栏的单语句条件更新 |
| 恢复 JobHandler | `job/AiTaskRecoveryJob`：每分钟扫描过期租约并放回待领取队列（未达上限）或置 FAILED（达到上限） |
| 边界测试 | `AiTaskServiceImplTest`(8)、`AiTaskLeaseIT`(5，真实 MySQL + 并发 + 重启恢复语义) |

## 2. 与卡片逐步实施的对应

1. **建立 task 及 CAS/行锁 claim、租约心跳和重试等待**：领取是"先读候选、再按期望代次做条件更新"
   （`status='QUEUED' AND claimed_epoch = 期望代次`），成功者把状态置 RUNNING、写入租约到期时间、
   代次 +1、尝试次数 +1；重试等待用 `next_attempt_time`（NULL 表示立即可领取）。
2. **外部调用在短事务外进行，worker 重建当前身份**：`claim`/`heartbeat`/`finish` 各自是一个短事务
   （单条条件更新，不持有行锁），调用方在事务之外执行模型/连接器调用；
   每次执行前用 `rebuildIdentity(runId)` 经 A06 的 `AiExecutionContextFactory` 从服务端事实重建身份与范围，
   不继承任何请求线程上的旧身份（IT 断言主体被撤销后重建直接失败）。
3. **重启扫描过期租约并按幂等属性恢复**：`AiTaskRecoveryJob` 每分钟调用 `recoverExpiredLeases`，
   用一条条件更新处理 `lease_expires_time < NOW()` 的行——未达上限回 QUEUED 并进入重试等待，
   达到上限置 FAILED；IT 用"领取后不落库、把租约置为过期"模拟进程中断，验证任务能被重新领取并完成。
4. **续租和最终落库携带领取代次/version 栅栏；旧 worker 迟到不能覆盖新 worker**：
   `heartbeat`/`finish` 都要求 `lease_owner = 本次 owner AND claimed_epoch = 本次代次 AND status='RUNNING'`；
   租约过期被接管后代次已递增，旧 worker 命中 0 行（IT 断言旧 worker 的续租与落库都失败、终态保持不变）。
5. **短事务结束后才执行网络调用；模拟受理后进程中断，验证持久任务能恢复且内部结果唯一**：
   受理（O02）产生唯一的首任务（`uk_ai_run_task_kind`）；IT 模拟"领取后进程中断 → 租约过期 → 恢复 → 新 worker 接管"，
   全程只有一条任务行、一次终态写入。

## 3. 关键约束与安全语义

- **不重复领取**：有效租约期内第二个 worker 领不到（IT 断言空结果）；两个 worker 并发领取时只有一个成功
  （真实 MySQL 并发 IT 断言合计 1 条租约）。
- **重试有上限**：`attempt_count` 达到 `max_attempts`（默认 3）后恢复扫描把任务置 FAILED，
  不再回到队列（IT 断言两次领取 + 两次过期后状态为 FAILED 且无人可再领取）。
- **栅栏不可绕过**：所有续租/落库都以 (owner, epoch) 为条件，应用层无法用旧凭据覆盖新结果。
- **身份不继承**：worker 的身份与范围来自服务端事实重建；主体撤销、应用停用、范围收窄都会在这里失败。
- **时钟口径统一**：租约到期、心跳、重试等待的比较全部在数据库侧（`NOW()`）完成；
   首任务的 `next_attempt_time` 写入 NULL（立即可领取），避免 JVM 与数据库时钟偏差把任务挡在等待里。

## 4. 验证结果

工作目录：`/home/ctyun/桌面/zhongtai/ai-platform`（授权副本）。

| 命令 | 退出码 | 结论 |
|---|---|---|
| `./mvnw -o -pl basic-framework-module-ai test` | 0 | 269 例通过（O03 新增 8 例：领取/续租/落库/恢复/身份重建/Job 汇总） |
| `./mvnw -Pintegration -pl basic-framework-server test -Dtest=AiTaskLeaseIT` | 0 | 5 例通过（真实 MySQL：租约期内不重复领取、并发领取唯一、过期恢复与旧 worker 迟到被拒、重试上限置 FAILED、身份重建与撤销后拒绝） |
| `./mvnw -Pintegration -pl basic-framework-server test -Dtest='PersistenceLifecycleIT,RemovedCapabilityMigrationIT'` | 0 | 6 例通过（新增 Job 后的种子任务清单与任务计数同步更新） |
| `node scripts/check-data-lifecycle.mjs` | 0 | 生命周期台账与快照一致（57 张表 / 36 条外键 / 32 张软删除） |

## 5. 顺带修复的依赖缺口

**首任务的 `next_attempt_time` 写入 JVM 时间会与数据库时钟比较错位**（O02 遗留）：
`ai_run_task.next_attempt_time` 由 JVM 的 `LocalDateTime.now()` 写入，而领取条件是
`next_attempt_time <= NOW()`（数据库时钟）。两者分属不同时钟，容器与宿主存在毫秒级偏差时
新建的任务会短暂"不可领取"（本卡 IT 首轮即命中：领取候选为空）。
现改为写入 NULL（语义是"立即可领取"），租约与重试等待的比较全部在数据库侧完成，消除跨时钟比较。

另外两处既有 IT 断言需要随新增 Job 同步：`PersistenceLifecycleIT` 的"种子任务清单"与
`RemovedCapabilityMigrationIT` 的"infra_job 计数"（7 → 8）。两处都是对既有事实的枚举，
新增一个已注册的恢复 Job 后必须一起更新，否则会掩盖"任务未被同步到 Quartz"这类真问题。

## 6. 门禁结果

| 命令 | 退出码 | 结论 |
|---|---|---|
| `sh .harness/verify.sh contracts` | 0 | 契约台账、权限目录、生命周期、字段目录、安全检查全部通过 |
| `sh .harness/verify.sh backend` | 0 | 编译、单元测试、格式、架构与覆盖率检查通过 |
| `sh .harness/verify.sh integration` | 1 → 0 | 41 个 IT 类 / 116 例全绿（含 `AiTaskLeaseIT` 5 例）；唯一失败是尾部棘轮（新文件未登记，属预期），`--update` 后复验通过 |
| `node scripts/check-coverage-ratchet.mjs --update` / `all` | 0 / 0 | 新增登记 2 个文件（均 100%），无基线下调、无登记删除，复验通过 |

## 7. 覆盖率

新增/变化的受测文件（数字取自 server 的 jacoco aggregate 报告）：

| 文件 | 覆盖率 |
|---|---|
| `service/task/AiTaskServiceImpl.java` | 100% |
| `job/AiTaskRecoveryJob.java` | 100% |
| `dal/mysql/task/AiTaskClaimMapper.java` | 由真实 MySQL 的 IT 覆盖（领取/续租/落库/恢复四条语句都被执行） |
| `dal/dataobject/run/AiRunTaskDO.java`（新增租约字段） | 由 O02/O03 的 IT 覆盖（读写租约列的路径都被执行） |

## 8. 未验证项

1. **真实执行与结果落库**：本卡只负责"领取—心跳—落库"的调度骨架；运行步骤、上下文、模型调用与
   结果写入属 O04，事件流与取消属 O05。
2. **跨节点全局调度**：领取是数据库 CAS，多实例部署天然互斥；但"任务分配到哪个节点"不做亲和性保证，
   跨区域队列与优先级调度属后续容量规划范围。
3. **监控告警阈值**：恢复 Job 只记录恢复条数；失败率、租约超时与队列堆积的告警阈值属 Q 系列运维范围。

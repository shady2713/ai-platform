# O06 任务查询、重试与清理证据（2026-09-20）

本记录是 [O06 实现任务查询、重试和清理](../tasks/O06.md) 的验收证据。

## 1. 交付内容

| 交付物 | 位置 |
|---|---|
| 任务管理接口 | `controller/app/v1/task/AiTaskController`：`GET /ai/task/progress`、`GET /ai/task/page`、`POST /ai/task/retry`，全部 `@AuthenticatedOnly` 并登记到 scope 目录 |
| 查询与重试服务 | `service/task/AiTaskService(+Impl)` 新增 `progress`（状态 + 事件序号 + 结果引用 + 可重试性）、`pageProgress`（按主体分页）、`retry`（重建身份 + 可重试性校验 + 乐观锁 CAS）、`cleanup`（按批次与保留期清理） |
| 进度与清理 DTO | `service/task/dto/AiRunProgressDTO`（只给标识与摘要，`toString` 不含正文）、`AiRetentionCleanupResultDTO`（按类别计数） |
| 保留 JobHandler | `job/AiRetentionCleanupJob`（每小时，`infra_job` 33）+ `dal/mysql/task/AiRetentionCleanupMapper`：事件 → 任务 → 幂等 → 运行 → 消息 → 会话 的**带守卫批量删除** |
| 持久化 | 迁移 `V64__ai_retention_cleanup.sql`：两个清理用索引 + 清理 Job 注册；快照同步至 V64 |
| 台账同步 | `PersistenceLifecycleIT` 与 `RemovedCapabilityMigrationIT` 的任务清单/计数同步（8 → 9 条内置任务） |
| 错误码 | `1_003_004_007 AI_TASK_NOT_RETRYABLE`，与 `docs/contracts/ai/error-code-map.md` 两侧同步 |
| 边界测试 | `AiTaskQueryRetryCleanupTest`(9)、`AiTaskControllerTest`(4)、`AiTaskQueryRetryCleanupIT`(3，真实 MySQL) |

## 2. 与卡片逐步实施的对应

1. **按主体查询进度和结果引用**：`progress`/`pageProgress` 按当前主体过滤运行（越权与不存在同语义），
   返回运行状态、已执行步数、最新事件序号、任务状态/尝试次数/下次可尝试时间/最近失败原因码，
   以及**结果引用**（助手消息编号 + 正文摘要）——结果正文仍走会话接口并按当前授权判定，
   进度接口不复制正文（单测直接断言响应字段名单里没有 `content`/`output`/`prompt`）。
   重放窗口过期时（O05 的 `AI_RUN_EVENT_WINDOW_EXPIRED`）正是靠这里的 `status + latestSeq` 引导恢复。
2. **人工重试校验当前权限与 retryable**：`retry` 先 `rebuildIdentity`（A06 重建身份与范围，
   失权/应用停用/范围收窄都直接拒绝），再按任务与运行状态判定可重试性；
   可重试时把任务放回 QUEUED、清空租约、**重置尝试计数**（人工决定，重新获得自动重试预算），
   并用任务行与运行行两道乐观锁 CAS 落库（失败即状态冲突，不留半成品）。
3. **过期事件/会话/任务清理有批次、保留策略和审计**：`cleanup(retention, batchSize, maxBatches)`
   按批次推进（单批上限 1000、批次数上限由调用方给出，一轮无事可做即停止）；
   清理顺序与引用方向一致：事件 → 任务 → 幂等记录 → 运行 → 消息 → 会话；
   审计由 `infra_job_log`（Job 执行日志）记录，摘要字符串按类别给出实际清理条数。

## 3. 关键约束与安全语义

- **UNKNOWN 写任务不可普通重试**：结果未知（UNKNOWN）的任务拒绝普通重试，
  因为重复执行可能产生第二份副作用；稳定错误 `AI_TASK_NOT_RETRYABLE` 说明"请核对后新建运行"。
  排队中/执行中/已成功的任务同样不可重试（单测与 IT 逐项断言）。
- **清理失败不会删除仍有用引用**：每一步删除都带"仍被引用则不删"的守卫——
  非终态运行的事件与任务不删；运行行还带幂等记录、事件或未终态任务时不删；
  会话与其消息还被任何运行引用时不删。外键 RESTRICT 是最后一道兜底（本卡 IT 首轮就命中了
  幂等记录引用运行导致的删除失败，随后把守卫补全）。
- **清理只处理终态且超过保留期的行**：非终态运行的任何数据都不会被清理（IT 断言存活）。
- **保留期语义**：幂等键的保留期结束后，同一幂等键重放会产生新运行（幂等记录已被清理）——
  这是幂等键的常规生命周期，保留窗口由 `basic-framework.ai.retention.window`（默认 30 天）控制。
- **进度不含正文**：结果引用只给消息编号与摘要；日志与响应不出现提示词、模型输出正文或凭据。

## 4. 验证结果

工作目录：`/home/ctyun/桌面/zhongtai/ai-platform`（授权副本）。

| 命令 | 退出码 | 结论 |
|---|---|---|
| `./mvnw -o -pl basic-framework-module-ai test` | 0 | 306 例通过（O06 新增 13 例：查询/重试/清理 9、控制器 4） |
| `./mvnw -Pintegration -pl basic-framework-server test -Dtest=AiTaskQueryRetryCleanupIT` | 0 | 3 例通过（真实 MySQL：进度与结果引用且主体隔离、失败任务可重试而 UNKNOWN 不可、清理保留仍被引用的行） |
| `./mvnw -o -pl basic-framework-server test -Dtest='PersistenceLifecycleIT,RemovedCapabilityMigrationIT'` | 0 | 6 例通过（新增清理 Job 后的种子任务清单与计数同步） |
| `node scripts/check-data-lifecycle.mjs` / `check-permission-catalog.mjs` | 0 / 0 | 生命周期台账与权限目录通过 |

## 5. 顺带修复的依赖缺口

**清理语句的引用顺序与写法**（本卡内部两处自纠）：
1. 幂等记录通过外键引用运行（RESTRICT），必须在运行行之前清理——首轮 IT 直接以
   `DataIntegrityViolation` 暴露该缺口，随后补上"幂等 → 运行"这一步；
2. MySQL 的多表 DELETE（含 `DELETE alias FROM ... JOIN ...`）**不允许 LIMIT**，
   而清理必须能按批次推进，因此全部改写为"单表 DELETE + 子查询守卫 + LIMIT"。

## 6. 门禁结果

| 命令 | 退出码 | 结论 |
|---|---|---|
| `sh .harness/verify.sh contracts` | 0 | 契约台账、权限目录、生命周期、字段目录、安全检查全部通过 |
| `sh .harness/verify.sh backend` | 0 | 编译、单元测试、格式、架构与覆盖率检查通过 |
| `sh .harness/verify.sh integration` | 1 → 0 | 44 个 IT 类 / 127 例全绿（含 `AiTaskQueryRetryCleanupIT` 3 例）；唯一失败是尾部棘轮（新文件未登记，属预期），`--update` 后复验通过 |
| `node scripts/check-coverage-ratchet.mjs --update` / `all` | 0 / 0 | 新增登记 3 个文件（均 100%），`AiTaskServiceImpl` 保持 100%，无基线下调 |

## 7. 覆盖率

| 文件 | 覆盖率 |
|---|---|
| `service/task/AiTaskServiceImpl.java` | 100%（O06 新增分支全部覆盖，基线未下调） |
| `controller/app/v1/task/AiTaskController.java` | 100% |
| `job/AiRetentionCleanupJob.java` | 100% |
| `service/task/dto/AiRetentionCleanupResultDTO.java` | 100% |
| `dal/mysql/task/AiRetentionCleanupMapper.java` | 由真实 MySQL 的 IT 覆盖（六条删除语句都被执行） |

## 8. 未验证项

1. **清理的跨批续跑**：单次调用最多执行 `maxBatches` 轮，剩余数据由下一次 Job 触发继续清理；
   跨批续跑与积压告警阈值属 Q 系列运维范围。
2. **附件/文件引用**：本卡清理运行事件/任务/运行/会话与消息；业务文件（`ai_file_binding`）
   的保留与清理属 F/K/D 系列的文件生命周期范围。
3. **人工重试的二次评测**：重试只重新排队执行；是否需要重新评测（Q 系列评测门槛）由调用方决定。
4. **浏览器端进度展示**：进度接口已就绪，前端轮询/SSE 消费在 Q06 浏览器验收中补齐。

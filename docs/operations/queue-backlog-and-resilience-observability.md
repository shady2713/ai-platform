# 队列积压与韧性可观测量（运维说明）

| 项目 | 内容 |
|---|---|
| 任务卡 | [Q07](../ai-platform/tasks/Q07.md)（前/运维切片：监控可解释队列积压） |
| 状态 | 文档交付：只读观察手段与判读口径。**本机没有 MySQL/Redis/后端进程**（`docker ps` 无容器、3306/48080 无监听），文中 SQL 与接口均**未在本机执行**，属"可执行命令 + 判读方法"，不是运行结果 |
| 适用版本 | 迁移 `V83` 及以前（V60/V61/V63/V81/V82 的列与表名） |
| 相关文档 | [验收目录 AT-017/018/059](../ai-platform/08-testing-acceptance.md)、[部署文档 §2 actuator 暴露面](../deployment.md#2-健康检查与-actuator-暴露面)、[安全信号](../security/security-signals.md)、[O03 租约证据](../ai-platform/verification/o03-task-lease-evidence.md)、[O05 事件证据](../ai-platform/verification/o05-run-event-evidence.md)、[O06 清理证据](../ai-platform/verification/o06-task-query-retry-cleanup-evidence.md)、[Q02 配额证据](../ai-platform/verification/q02-usage-ledger-quota-evidence.md) |

## 1. 这份文档回答什么

"运行/任务积压了"这句话在 AI 中台里有**四种完全不同的原因**，处置方式互不通用：

1. **没有消费者**（任务在排队，但没人领）——加容量没用，要先接 worker；
2. **消费者在跑但很慢**（上游模型慢、连接器慢）——看租约心跳与耗时，不是队列深度问题；
3. **租约/恢复链路失效**（恢复 Job 停用、时钟错位、重试预算耗尽）——任务永远不回到队列；
4. **被限流/配额拒**（429）——请求根本没进入执行链路。

本文给出**能从系统里读到的事实**（表、Job 日志、管理端接口、应用日志），以及"这些事实各自能证明什么、不能证明什么"。
所有指标名、阈值都以"仓库里真实存在"为前提：**AI 模块目前没有自定义业务指标**（见 §2），所以本文不引用任何不存在的指标名。

## 2. 事实边界（先说清楚有什么、没有什么）

| 观察面 | 是否存在 | 事实来源 |
|---|---|---|
| AI 业务指标（队列深度、租约数、配额占位、事件延迟） | **不存在** | 代码走查：`basic-framework-module-ai/src/main` 内无 `MeterRegistry`/`Counter`/`Gauge`/`@Timed`。`/actuator/prometheus` 只有框架级 JVM/HTTP/安全信号计数 |
| 框架级健康检查与抓取端点 | 存在，但生产默认不暴露抓取 | `application-prod.yaml` 暴露 `health` + 受令牌保护的 `prometheus`；抓取默认关闭（`PROMETHEUS_ENABLED`）。细节不在此重复，见[部署文档 §2](../deployment.md#2-健康检查与-actuator-暴露面) |
| 现有告警规则 | 只有安全信号（T1/T2/T3） | `ops/prometheus/security-signals.rules.yml`；阈值唯一来源约定见[安全信号](../security/security-signals.md) |
| 任务队列/租约/重试状态 | 存在（MySQL） | `ai_run_task`（V60 + V61 租约列） |
| 运行与事件推进 | 存在（MySQL） | `ai_run`（V60，含 V63 的 `event_seq`）、`ai_run_event`（V63） |
| 配额占位 | 表存在，**运行链路尚未调用 `acquire`** | `ai_quota_lease`（V81）；`AiQuotaService.acquire` 在 `src/main` 内除接口/实现外无调用方，管理端只用 `activeCount`（见 §8.1） |
| 恢复/清理 Job 执行留痕 | 存在 | `infra_job`（32 = `aiTaskRecoveryJob` 每分钟、33 = `aiRetentionCleanupJob` 每小时）、`infra_job_log`（`result` 为中文摘要） |
| 管理端只读查询接口 | 存在（需权限码） | `/ai/observability/run/{page,get,timeline}`（`ai:observability:query`）、`/ai/usage/{page,summary,service-summary,quota-active}`（`ai:usage:query`） |
| SSE 连接数/慢消费者指标 | **不存在** | 服务端是"按 seq 轮询重放 + 心跳注释 + 连接时长上限 5 分钟"的有界实现，没有发布订阅计数（O05 证据 §8.2） |

> 结论：**队列积压目前只能从数据库状态 + Job 日志 + 应用日志解释**，不能用"看板指标"解释。要让积压变成可告警指标，需要新增指标或 DB exporter（见 §8.4，不在本切片允许路径内）。

## 3. 可观测量总表

| # | 可观测量 | 数据来源 | 能解释什么 | 不能解释什么 |
|---|---|---|---|---|
| O1 | 队列深度与最老等待时间 | `ai_run_task.status='QUEUED'` + `next_attempt_time` | 是否在积压、积压多久 | 为什么积压（要配合 O3/O4/O6） |
| O2 | 尝试次数与重试预算 | `attempt_count`/`max_attempts`/`last_error_code` | 反复失败、预算耗尽、被置 FAILED 的任务 | 单次失败的根因（要查运行详情/日志） |
| O3 | 租约与心跳 | `lease_owner`/`lease_expires_time`/`heartbeat_time`/`claimed_epoch` | 是否有人在跑、是不是慢、租约是否过期 | 上游为什么慢（要查账本耗时与端点日志） |
| O4 | 恢复 Job 是否在跑 | `infra_job` id=32 + `infra_job_log.result` | 过期租约是否被回收；"恢复 0 条"也是有效信息 | 队列是否积压（要配合 O1） |
| O5 | 事件序号推进 | `ai_run.event_seq`、`ai_run_event.max(seq)`、`create_time` | 运行是否有新事件落库 | **不能**证明卡死：长模型调用期间本来就没有事件（见 §5 第 4 步） |
| O6 | 运行状态分布与终态比例 | `ai_run.status` + `update_time` | 有没有大批量卡在 ACCEPTED/RUNNING | 是不是模型效果问题（平台确定性与模型效果分开，见 AT-016 说明） |
| O7 | 配额占位与残留 | `ai_quota_lease.state/lease_until/holder_ref`；`/ai/usage/quota-active` | 占位是否被长期持有、是否有残留 holder | **未接入时表为空**：空≠没有超发，只说明"没启用/没接线" |
| O8 | 事件重放窗口过期 | 错误码 `1_003_004_006`（HTTP 409，`AI_RUN_EVENT_WINDOW_EXPIRED`）；判据见 §4.3 | 客户端续读"太旧"了（保留期清理或长时间离线） | 客户端是否重连（客户端行为要看浏览器侧，见 AT-014/017 用例） |
| O9 | 保留期清理量 | `infra_job_log`（33 的 `result`）、应用日志 | 事件/任务/运行是否被清理、清理了多少 | 是否丢数据（清理只处理终态且超保留期，见 §7.2） |

## 4. 查询与观察命令

> 只读原则：以下 SQL 全部是 `SELECT`；在生产库执行前先确认使用**只读账号**，不要用应用账号做人工写入。
> 时间比较全部在数据库侧（`NOW()`），与代码口径一致（O03 的租约/重试判定都在 DB 时钟下）。

### 4.1 队列深度与等待时间（O1）

```sql
-- 按状态看任务分布
SELECT status, COUNT(*) AS tasks, MIN(create_time) AS oldest_create
FROM ai_run_task
WHERE deleted = 0
GROUP BY status;

-- 真正"可领取但没人领"的深度与最老等待（与 selectClaimable 同口径）
SELECT COUNT(*) AS claimable,
       MIN(create_time) AS oldest_queued,
       TIMESTAMPDIFF(SECOND, MIN(create_time), NOW()) AS oldest_wait_seconds
FROM ai_run_task
WHERE deleted = 0
  AND status = 'QUEUED'
  AND (next_attempt_time IS NULL OR next_attempt_time <= NOW());
```

### 4.2 尝试次数与重试预算（O2）

```sql
-- 接近/耗尽重试预算的任务（达到 max_attempts 后恢复 Job 会置 FAILED，不再回队列）
SELECT id, run_id, status, attempt_count, max_attempts, next_attempt_time, last_error_code, update_time
FROM ai_run_task
WHERE deleted = 0 AND status IN ('QUEUED', 'RUNNING') AND attempt_count >= max_attempts
ORDER BY update_time ASC
LIMIT 50;

-- 失败原因码分布（稳定词表：TOOL_BUDGET_EXCEEDED / STEP_BUDGET_EXCEEDED / CANCELLED …）
SELECT last_error_code, COUNT(*) AS tasks
FROM ai_run_task
WHERE deleted = 0 AND last_error_code IS NOT NULL
GROUP BY last_error_code
ORDER BY tasks DESC;
```

### 4.3 租约与心跳（O3、O8）

```sql
-- 有效租约数（与 AiTaskService.countActiveLeases 同口径）
SELECT COUNT(*) AS active_leases
FROM ai_run_task
WHERE deleted = 0 AND status = 'RUNNING' AND lease_expires_time > NOW();

-- 过期但仍 RUNNING：等恢复 Job 回收（正常情况下 1 分钟内消失）
SELECT COUNT(*) AS expired_running
FROM ai_run_task
WHERE deleted = 0 AND status = 'RUNNING' AND lease_expires_time < NOW();

-- 逐个看：谁在跑、心跳多久没动、租约还剩多久
SELECT id, run_id, lease_owner, claimed_epoch, attempt_count,
       heartbeat_time, lease_expires_time,
       TIMESTAMPDIFF(SECOND, heartbeat_time, NOW()) AS since_heartbeat_seconds
FROM ai_run_task
WHERE deleted = 0 AND status = 'RUNNING'
ORDER BY lease_expires_time ASC
LIMIT 50;
```

> **不要问数据库要"积压总量"**：服务端没有队列长度指标，`ai_run_task` 计数就是唯一事实。

### 4.4 运行与事件推进（O5、O6）

```sql
-- 还在非终态的运行（按更新时间正序：最久没动的排前面）
SELECT id, run_key, application_id, service_id, status, step_count, event_seq,
       create_time, update_time
FROM ai_run
WHERE deleted = 0 AND status IN ('ACCEPTED', 'RUNNING')
ORDER BY update_time ASC
LIMIT 50;

-- 事件推进对照：event_seq 是"已分配序号"，event_rows/max_seq 是"已落库事件"
SELECT r.id, r.run_key, r.status, r.event_seq, r.update_time,
       COALESCE(e.event_rows, 0) AS event_rows, e.max_seq, e.last_event_time
FROM ai_run r
LEFT JOIN (
    SELECT run_id, COUNT(*) AS event_rows, MAX(seq) AS max_seq, MAX(create_time) AS last_event_time
    FROM ai_run_event
    WHERE deleted = 0
    GROUP BY run_id
) e ON e.run_id = r.id
WHERE r.deleted = 0 AND r.status IN ('ACCEPTED', 'RUNNING')
ORDER BY r.update_time ASC
LIMIT 50;
```

判读（重要，避免误报卡死）：

- `event_seq` 与 `event_rows` **在正常情况下一致**（seq 在运行行的行锁内分配、与事件同事务提交，O05）；
- **事件不推进 ≠ 卡死**：事件只在"运行状态变化"时写入，一次长模型调用期间 seq 本来就不动；
- 运行是否还活着，看**任务侧**的事实（O3 的 `heartbeat_time`）而不是事件侧；
- `update_time` 不动 + 租约已过期 + 恢复 Job 正常 → 属于"worker 失联，已由恢复 Job 回收"的正常路径。

### 4.5 配额占位（O7）

```sql
-- 当前有效占位（与 activeCount 同口径：未释放且未到期）
SELECT application_id, COUNT(*) AS active_slots, MIN(lease_until) AS earliest_expiry
FROM ai_quota_lease
WHERE state = 'ACTIVE' AND lease_until > NOW()
GROUP BY application_id;

-- 残留占位（已过期但没被显式释放；不计入 activeCount，靠懒回收）
SELECT id, lease_key, application_id, service_id, holder_ref, lease_until, update_time
FROM ai_quota_lease
WHERE state = 'ACTIVE' AND lease_until < NOW()
ORDER BY lease_until ASC
LIMIT 50;
```

也可用管理端接口（需 `ai:usage:query`）：`GET /admin-api/ai/usage/quota-active?applicationId=<编号>`，返回 `activeCount(applicationId)`。

### 4.6 恢复/清理 Job 是否在跑（O4、O9）

```sql
-- Job 是否启用（status=1）与调度表达式
SELECT id, name, handler_name, cron_expression, status
FROM infra_job
WHERE id IN (32, 33);

-- 最近的恢复/清理执行记录（result 是中文摘要，例如"恢复过期租约任务 N 条"）
SELECT job_id, handler_name, begin_time, end_time, duration, status, result
FROM infra_job_log
WHERE handler_name IN ('aiTaskRecoveryJob', 'aiRetentionCleanupJob')
ORDER BY id DESC
LIMIT 20;

-- 事件/任务/运行的保留期清理量（33 的 result 里逐项有数）
SELECT begin_time, result
FROM infra_job_log
WHERE handler_name = 'aiRetentionCleanupJob'
ORDER BY id DESC
LIMIT 20;
```

### 4.7 应用日志（O4、O9）

| 日志片段 | 出处 | 含义 |
|---|---|---|
| `[execute][恢复过期租约任务 (N) 条]` | `AiTaskRecoveryJob` | 本轮回收了多少过期租约任务；持续为 N>0 表示有 worker 反复失联 |
| `[execute][清理运行事件 (…) 条、任务 (…) 条、幂等记录 (…) 条、运行 (…) 条、消息 (…) 条、会话 (…) 条]` | `AiRetentionCleanupJob` | 保留期清理明细；异常放大时要看是否与积压同源（例如终态爆发） |
| 请求日志中的 429（`AI_QUOTA_EXCEEDED` 1_003_001_005 / `AI_RUN_BUDGET_EXCEEDED` 1_003_004_004） | 控制器异常处理 | 被限流的请求**没有**进入队列，不要与积压混为一谈 |
| 409 `AI_RUN_EVENT_WINDOW_EXPIRED`（1_003_004_006） | `AiRunEventServiceImpl.replay` | 客户端续读位置早于最早事件（`position < earliest.seq - 1`），应转读运行快照 |

### 4.8 管理端只读接口（O5、O6、O9 的界面口径）

| 接口 | 权限码 | 用途 |
|---|---|---|
| `GET /admin-api/ai/observability/run/page` | `ai:observability:query` | 按应用/服务/状态/主体/时间窗筛选运行（服务端过滤后分页） |
| `GET /admin-api/ai/observability/run/get` | `ai:observability:query` | 运行详情：步骤数、失败原因码、耗时分解（未知计量单列）、可重试性 |
| `GET /admin-api/ai/observability/run/timeline` | `ai:observability:query` | 事件时间线（有界；只给块类型与有无，不给正文） |
| `POST /admin-api/ai/observability/run/retry` | `ai:observability:retry` | 人工重试（`UNKNOWN`/仍在执行一律 422 拒绝） |
| `GET /admin-api/ai/usage/page`、`/summary`、`/service-summary`、`/quota-active` | `ai:usage:query` | 用量与占位（`summary` 显式给出"来源未知"条数，不把 UNKNOWN 当 0） |
| `GET /actuator/health` | 无（部署探测） | 进程存活；**不含**队列信息 |

## 5. 判读顺序（先排除"没有消费者"）

1. **有没有人在领任务？** 在本仓库的 `src/main` 里，`AiTaskService.claim` 的调用方只有评估运行
   （`AiEvalRunServiceImpl.claimAndExecute`，worker 名 `eval-worker-<evalRunId>`）；
   `RUN_STEP` 类型的用户运行任务没有仓库内的常驻消费者。
   → 如果 `ai_run_task` 里 `QUEUED` 持续增长、`attempt_count = 0`、`lease_owner IS NULL`，
   **第一结论是"没有消费者/消费者未接入"，不是"容量不足"**（Q07 §3 第 1 步的并发压测前提也在这里）。
2. **恢复链路是否在跑？** 看 `infra_job` id=32 的 `status`，再看 `infra_job_log` 最近一条
   `aiTaskRecoveryJob` 的时间。若停用或长时间没有记录：过期租约不会被回收，任务会永远停在 RUNNING。
3. **是不是慢（而不是死）？** `status='RUNNING'` 且 `lease_expires_time > NOW()`、`heartbeat_time` 在推进
   → 有活着的 worker 在跑；此时积压由"吞吐 < 到达"造成，看 §6 的阈值建议与上游耗时（账本 `duration_ms`）。
4. **事件不推进能不能当卡死证据？** 不能。事件只在状态变化时写入；长调用期间 `event_seq` 不动是正常的。
   卡死证据要来自"租约过期 + 心跳停止 + 恢复 Job 回收"这一组事实（O3 + O4）。
5. **重试预算是否耗尽？** `attempt_count >= max_attempts`（默认 3）→ 恢复 Job 会置 `FAILED`（不再回队列），
   界面上是不可重试的失败（`UNKNOWN` 结果未知的任务另有单独语义，需人工核对后新建运行）。
6. **是不是根本没进队列？** 429（配额/预算）在受理或执行前置就被拒绝；用访问日志与错误码计数区分
   （注意 §8.1：当前 `ai_quota_lease` 不产生 429，配额 429 只在接线后才会出现）。

## 6. 阈值建议（**未接线，仅供判读**）

下表是判读建议，用来判断"要不要人工介入"。**它们不是告警规则**：
告警阈值的唯一来源约定是 `ops/prometheus/*.rules.yml`（该目录不在本切片允许路径内，见 §8.4）。

| 判据 | 建议阈值 | 为什么 | 自动化现状 |
|---|---|---|---|
| `claimable` 深度（O1） | > 0 且 `oldest_wait_seconds > 60` 持续 5 分钟 | 正常领取循环是秒级；持续 1 分钟无人领取说明消费者缺失或停顿 | 无指标、无规则 |
| `expired_running`（O3） | > 0 持续 > 2 分钟 | 恢复 Job 每分钟跑一次，正常应在 1 分钟内回收 | 无指标、无规则 |
| 恢复 Job 停更（O4） | 最近一条 `aiTaskRecoveryJob` 记录 > 3 分钟前 | cron 为每分钟；3 倍周期未见记录即可疑（Job 日志清理 Job 每天清理日志，见 §7.2） | 无指标、无规则 |
| 重试预算耗尽占比（O2） | `attempt_count >= max_attempts` 的任务 > 0 | 这些任务会转 FAILED，不再自动恢复 | 无指标、无规则 |
| 非终态运行滞留（O6） | `status IN ('ACCEPTED','RUNNING')` 且 `update_time` 早于 15 分钟前 > 0 | 运行若无人执行会一直滞留 | 无指标、无规则 |
| 配额残留占位（O7） | `state='ACTIVE' AND lease_until < NOW()` 的行年龄 > 1 小时 | 懒回收不清行、只影响"是否有残留 holder"判断 | 无指标、无规则 |
| 窗口过期（O8） | `AI_RUN_EVENT_WINDOW_EXPIRED` 速率 > 0 且持续存在 | 说明有客户端长期离线到窗口外；窗口本身由保留期清理决定 | 无指标、无规则 |
| 订阅连接时长 | 单连接 < 300 秒（服务端上限） | 超过说明客户端没按 afterSeq 续读（该上限是设计值，见 O05） | 无指标 |

**部署方要真正告警时**：先在 `ops/prometheus/*.rules.yml` 增规则（并同步 `docs/security/security-signals.md` 这类语义归属文档），
或用一个只读 DB exporter 把 §4.1–4.5 的查询暴露成 gauge；两者都需要新的允许路径（§8.4）。

## 7. "数据不足"时怎么表述

这一节是硬要求：**不同数据可得性下的结论强度不同**，不能把"看不到"写成"没问题"。

### 7.1 表为空 / 无流量

- `ai_run_task` 为空：可以说"当前没有任务记录"；**不能**说"队列健康"（可能是没有流量、或功能没接入）。
- `ai_quota_lease` 为空：**必须**补一句"该表在运行链路接入 `acquire` 之前不会产生数据"（§8.1），
  因此"占位为 0"不能作为"没有超发"的证据。

### 7.2 保留期与清理造成的"看不到"

- 事件/任务/运行按保留期清理：窗口默认 `basic-framework.ai.retention.window=P30D`，
  批次 `batch-size=200` × `max-batches=10`（每小时一次），只清理**终态且超保留期**的行，且带"仍被引用则不删"守卫。
  → 任何"30 天前的情况"都可能已被清理，属于设计行为，不是数据丢失。
- `infra_job_log` 自身每天被"任务日志清理 Job"（id=27，`0 0 0 * * ?`）清理。
  → "查不到恢复 Job 历史"可能是日志保留策略导致，先看时间窗，再下结论。
- 事件被清理后 `earliestSeq` 前移，"窗口过期"可能只是客户端离线太久（O8），不是服务端丢事件。

### 7.3 时钟

- 租约/重试/清理判定全部在数据库侧（`NOW()`）；**应用与数据库时钟不一致**会让"界面显示的耗时/时间"与库内判定不一致
  （Q03 已登记该边界）。引用 `TIMESTAMPDIFF(... NOW())` 的结果时，先确认数据库时钟正确（NTP）。

### 7.4 采样窗口

- 本文所有判据都需要"两个时间点"的对比（例如 `claimable` 深度与 `oldest_wait_seconds` 的变化），
  单次查询只能说明瞬时状态。结论句应写明观察窗口，例如"在 10:00–10:05 每 30 秒采样，claimable 稳定在 120 且
  oldest_wait_seconds 单调增"。

### 7.5 指标缺失

- 问"队列延迟 P95 是多少"：**当前答不了**——没有直方图/指标，只有 §4 的 SQL 快照。
  正确表述是"用 §4.1 的两个时间点估计等待时间，未做分位数统计"，而不是编一个数字。

### 7.6 不能用 skip/猜测代替的结论

- 没有真实库/后端时（例如本机），只能给出**可执行命令与判读方法**，并明确"未执行"。
  本文即为此类：本机 `docker ps` 无容器、3306/48080 无监听，所有查询都未运行。

## 8. 已知缺口与需要后续处理的事项

### 8.1 配额占位未被运行链路使用（会影响"429 可解释"的归因）

- 事实：`AiQuotaService.acquire` 在 `src/main` 内只有接口与实现，**没有调用方**；
  管理端只用 `activeCount`（`AiUsageController` 的 `/ai/usage/quota-active`）。
- 影响：生产上 `ai_quota_lease` 不会被占用，`AI_QUOTA_EXCEEDED`（1_003_001_005）也不会由这套占位产生。
  → 看到 429 时先确认它来自哪一层（预算 `AI_RUN_BUDGET_EXCEEDED` 1_003_004_004、上游限流、网关限流），
  不要默认"配额占位生效中"。
- 处置：接线属后端/运行链路范围（不在本切片允许路径）。

### 8.2 `RUN_STEP` 任务在仓库内没有常驻消费者

- 事实：`AiTaskService.claim` 的仓库内调用方只有评估运行（`eval-worker-*`）。
- 影响：用户运行的 `ai_run_task` 会一直 `QUEUED`；§5 第 1 步的判据要用上。
- 附带风险（代码走查，**未经运行验证**）：评估 worker 领取的是"最老可领取任务"，若最老的是**用户运行的**任务，
  它会 `heartbeat(lease, 1)`（把租约压到 1 秒）后交回；但 `claim` 已经 `attempt_count + 1`，
  重复发生会消耗用户任务的重试预算。判读时注意"`attempt_count` 增长但 `last_error_code` 为空"这一组合。

### 8.3 慢消费者只有服务端"有界实现"，没有指标

- 服务端不推送：订阅 = 按 seq 重放（单批 ≤ 200，控制器与服务层一致）+ 心跳注释 + 连接 5 分钟上限；
  因此"慢消费者"挤压的是连接与重放读取，不是服务端内存队列。
- 前端侧已用真实浏览器用例覆盖（AT-014/AT-017：慢速重复下发、非终态断流、连接重置、重连去重），
  证据见 [Q07 前端切片验收报告](../acceptance/q07-frontend-resilience-and-ops.md)。

### 8.4 要变成告警需要新允许路径

以下都**不在**本切片允许路径内，需要另开卡或扩范围：

1. `ops/prometheus/*.rules.yml` 增加 AI 队列/租约/配额规则，并在语义文档登记；
2. 新增 AI 业务指标（Micrometer）或只读 DB exporter（把 §4.1–4.5 暴露为 gauge）；
3. 运行链路接入 `acquire`/`renew`/`release`（否则配额不可观测）；
4. `RUN_STEP` 消费者（worker）接线。

### 8.5 前端侧相关缺口（已登记在验收报告）

- 应用层没有自动重连循环（断流后停在"执行中"，需宿主/页面再触发；客户端 `afterSeq` 语义是可用的）；
- （**已修**）窗口过期错误码常量曾与冻结契约不一致（前端 `1003004009` vs 契约/后端 `1_003_004_006`），
  曾导致"转读快照"分支在真实错误码下不触发（现前端已改为 `1003004006`）；连接重置等传输层失败显示浏览器原始报文而不是稳定原因码。

## 9. 来源清单

| 结论 | 来源 |
|---|---|
| 任务表列与状态、`uk_ai_run_task_kind` | `后端代码/.../basic-framework-server/target/classes/db/migration/V60__ai_run.sql`、`V61__ai_run_task_lease.sql` |
| 领取/心跳/落库/恢复的 SQL 条件 | `.../dal/mysql/task/AiTaskClaimMapper.java`（`selectClaimable`/`claim`/`heartbeat`/`finish`/`recoverExpired`/`countActiveLeases`） |
| 恢复 Job cron 与日志格式、配置默认值 | `.../job/AiTaskRecoveryJob.java`（`retry-delay-seconds:5`、`batch-size:200`）、`.../job/AiRetentionCleanupJob.java`（`retention.window:P30D`、`batch-size:200`、`max-batches:10`） |
| Job 登记行与日志表 | `数据库文件/basic_framework.sql`（`infra_job` 32/33、`infra_job_log`）；`V61`/`V64` 插入语句 |
| 事件 seq 分配与重放窗口判据 | `V63__ai_run_event.sql`、`.../service/event/AiRunEventServiceImpl.java` |
| 配额表与 `acquire` 语义 | `V81__ai_usage_ledger_and_quota.sql`、`.../service/quota/AiQuotaService.java` |
| 管理端接口与权限码 | `.../controller/admin/{observability,usage}/*.java`（Q03/Q02 证据） |
| 指标缺失 | 代码走查：`basic-framework-module-ai/src/main` 无 Micrometer 用法 |
| actuator 暴露面 | `application{-local,-test,-prod}.yaml`、[部署文档 §2](../deployment.md#2-健康检查与-actuator-暴露面) |
| 安全信号阈值归属 | `ops/prometheus/security-signals.rules.yml`、[安全信号](../security/security-signals.md) |
| SSE 有界实现与 5 分钟上限 | [O05 证据 §2/§8](../ai-platform/verification/o05-run-event-evidence.md) |
| 保留期清理的"仍被引用则不删"守卫 | `V64__ai_retention_cleanup.sql`、[O06 证据](../ai-platform/verification/o06-task-query-retry-cleanup-evidence.md) |

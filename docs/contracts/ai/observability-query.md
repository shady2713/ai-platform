# 运行监控查询契约（Q03）

本契约固定 Q03 交付的运行监控接口：**谁看、看什么、怎么筛、什么不给**。
实现见 `后端代码/basic-framework-boot/basic-framework-module-ai/src/main/java/com/basicframework/module/ai/controller/admin/observability/`，
前端消费方见 `前端代码/basic-framework-admin/apps/web-ele/src/{api/ai/observability,views/ai/observability}`。

## 1. 权限与端点（管理端通道 `/admin-api`）

| 方法与路径 | 权限码 | 语义 |
|---|---|---|
| `GET /ai/observability/run/page` | `ai:observability:query` | 运行分页（服务端按条件过滤后再分页） |
| `GET /ai/observability/run/get` | `ai:observability:query` | 运行详情（步骤、失败原因、耗时分解、可重试性） |
| `GET /ai/observability/run/timeline` | `ai:observability:query` | 事件时间线（有界，升序） |
| `POST /ai/observability/run/retry` | `ai:observability:retry` | 人工重试（独立动作，单独权限点） |

查看与操作分开鉴权：无 `ai:observability:retry` 的账号仍可查询；界面按钮的可见性与这两个权限码一致，
但**服务端始终校验**（界面隐藏不是安全边界）。

## 2. 查询参数

`/run/page`（`AiRunMonitorPageReqVO`，继承分页参数）：

| 参数 | 说明 |
|---|---|
| `applicationId` / `serviceId` | 精确匹配；不传表示不过滤 |
| `status` | 运行状态闭集：`ACCEPTED`/`RUNNING`/`SUCCEEDED`/`FAILED`/`CANCELLED`；其它值直接 422 |
| `subjectType` | `APP`/`USER`；其它值直接 422 |
| `from` / `to` | 按**受理时间**的半开区间（含起、不含止），本地时间 `YYYY-MM-DDTHH:mm:ss`；`from >= to` 直接 422 |
| `pageNo` / `pageSize` | 分页（倒序：编号大的在前） |

筛选一律在服务端执行；界面不得"取一页再自行筛选"（否则总数与结果都不成立）。

`/run/timeline`：`runId`（必填）、`afterSeq`（不含，缺省 0）、`limit`（缺省 100，**上限 200**，超出按上限收敛）。

`/run/get`：`runId`（必填）。

`/run/retry`：`runId`、`version`（运行行乐观锁版本，取自 `/run/get` 的 `runVersion`）；版本不一致返回 409。

## 3. 响应字段（只给标识与计量元数据）

`AiRunMonitorRespVO`（列表行）：`runId`、`runKey`、`applicationId`、`serviceId`、`releaseId`、`subjectType`、
`status`、`stepCount`、`latestSeq`、`taskStatus`、`attemptCount`、`lastErrorCode`、`retryable`、
`retryBlockedReason`、`createTime`、`updateTime`。

`AiRunMonitorDetailRespVO`（详情）额外给：`dataLevel`、`modelEndpointId`、`endpointConfigRevision`、
`contentHash`、`conversationId`、`resultMessageId`、`resultDigest`、`taskId`、`taskKind`、`nextAttemptTime`、
`runVersion`、`timing`。

**不回**：提示词与响应正文、外部用户标识明文、端点地址与凭据、事件块正文（`blockJson`）。模型端点只给编号与
配置修订，凭据状态仍走端点接口（AT-011）。

### 耗时分解 `timing`

| 字段 | 口径 |
|---|---|
| `totalDurationMs` | 受理到终态；仍在执行按当前时刻计算；时间缺失或倒挂留空 |
| `modelDurationMs` | 用量账本中该运行的耗时**实测值之和**（上游报告/平台估算） |
| `modelInvocationCount` | 该运行的计量条数 |
| `unknownUsageCount` | 其中"来源未知"的条数（这些调用没有耗时/用量事实） |
| `retrievalDurationMs` / `businessApiDurationMs` | 首期**未单独计量**，恒为空（不填 0 冒充实测，AT-060） |
| `unmeasuredStages` | 尚未计量的稳定阶段名（当前固定为 `RETRIEVAL`、`BUSINESS_API`） |

### 事件时间线 `AiRunTimelineRespVO`

`seq`、`status`、`blockType`、`schemaVersion`、`blockPresent`、`createTime`。
`blockPresent` 只表示"该事件带结果块"，正文不在本接口返回；需要正文的场景走受权业务接口（会话消息、报表读取）。

## 4. 可重试性（与重试命令同一判据）

`retryable = true` 仅当：任务存在、任务状态为 `FAILED`、且运行未处于 `SUCCEEDED`/`CANCELLED`。
其余情况给出 `retryBlockedReason`（稳定说明）：

| 情况 | 说明 |
|---|---|
| 任务 `UNKNOWN` | 结果未知，重复执行可能产生第二份副作用 → 人工核对后新建运行 |
| 任务 `RUNNING` | 仍在执行（或租约未过期） |
| 任务 `SUCCEEDED` | 已成功 |
| 任务其它状态 | `任务状态为 <status>` |
| 运行已结束 | 运行已结束（SUCCEEDED/CANCELLED） |

重试成功后的状态迁移：任务回 `QUEUED`、尝试次数清零、运行回 `ACCEPTED`；两行各自乐观锁 CAS，
任一步失败整体回滚（不会出现"任务已排队、运行还是 FAILED"的半成品）。重试**不写运行事件**（与应用端 O06 一致）。

## 5. 错误与 HTTP 状态

| 情形 | 错误码 | HTTP |
|---|---|---|
| 筛选字面量非法/时间窗倒挂 | `AI_REQUEST_INVALID` | 422 |
| 运行不存在（含越权同语义） | `AI_RUN_NOT_FOUND` | 422（既有错误码口径，见 `docs/contracts/ai/error-code-map.md`） |
| 任务不可重试 | `AI_TASK_NOT_RETRYABLE` | 422（消息里带稳定原因） |
| 版本不一致 / CAS 失败 | `AI_STATE_CONFLICT` | 409 |

## 6. 用量控制面（Q02 交付、Q03 提供页面）

| 方法与路径 | 权限码 | 语义 |
|---|---|---|
| `GET /ai/usage/page` | `ai:usage:query` | 账本分页（应用/服务/时间窗） |
| `GET /ai/usage/summary` | `ai:usage:query` | 按计量来源聚合，含 `unknownInvocations` |
| `GET /ai/usage/service-summary` | `ai:usage:query` | 按服务聚合 |
| `GET /ai/usage/quota-active` | `ai:usage:query` | 当前有效配额占位数（未释放且未到期） |

## 7. 变更规则

字段语义的变更（新增阶段名、把未计量阶段改为实测、改变重试判据）必须先改本契约与
`docs/contracts/ai/error-code-map.md`（如涉及新错误码），再改实现与前端；
重试判据与应用端 O06 的差异必须在此登记（当前两者同判据，代码落点见 Q03 证据的边界说明）。

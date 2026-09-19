# O02 持久运行与幂等受理证据（2026-09-19）

本记录是 [O02 实现持久run和幂等受理](../tasks/O02.md) 的验收证据。

## 1. 交付内容

| 交付物 | 位置 |
|---|---|
| 持久化 | 迁移 `V60__ai_run.sql`：`ai_run`（受理即固定发布版本/端点配置版本/内容摘要 + 请求摘要 + 状态）、`ai_run_idempotency`（(应用,主体,幂等键) 唯一 + 请求摘要 + 运行引用）、`ai_run_task`（首任务 + 重试等待列）；7 条物理外键；快照同步至 V60（逻辑删除表 29 → 32） |
| 运行受理 API | `controller/app/v1/run/AiRunController`：`POST /ai/run/accept`、`GET /ai/run/{get,page}`，全部 `@AuthenticatedOnly` 并登记到 scope 目录 |
| 服务层 | `service/run/AiRunService(+Impl)`：受理（幂等复用 / 同键异摘要 409 / 冲突回读）、运行读取与分页；`AiRunAcceptanceWriter`（事务内建立幂等记录 + 运行 + 首任务） |
| 请求摘要 | `domain/runtime/AiRunRequestDigest`：只由服务/会话/消息/附件/业务上下文算出，附件顺序无关、字段带长度前缀，**排除 traceId 与票据 token** |
| 台账同步 | `data-lifecycle.json`（3 张表进软删除策略 + 7 条物理外键）、`data-permission-exemptions.json`（新豁免 `ai-run-data`，`subject-bound` + 逐表证据）、`PersistenceLifecycleIT` 预期表清单、`docs/contracts/ai/scope-catalog.md` 登记 3 个登录即可访问端点 |
| 边界测试 | `AiRunRequestDigestTest`(5)、`AiRunServiceImplTest`(10)、`AiRunControllerTest`(4)、`AiRunAcceptanceIT`(5，真实 MySQL，含 Mapper 主体过滤与乐观锁)、`AiRunAcceptanceConcurrencyIT`(1，10 并发) |

## 2. 与卡片逐步实施的对应

1. **校验服务/主体/输入并生成 run 快照**：受理先校验幂等键（16–128 位）、消息长度、附件数量与长度、
   业务上下文（JSON 对象 + 长度）、数据分级（L1–L4），再解析**服务端主体**并校验服务属于同一应用；
   随后解析发布版本：会话已固定则走 S03 的 `resolvePinnedRun`（按当前授权重新判定固定版本），
   否则按别名解析（`resolveForNewRun`）并把结果写回会话（首个 run 固定会话版本）。
   运行行冻结 releaseId、端点配置版本与内容摘要——运行链路只用快照，不重新读草稿。
2. **建立 idempotency 唯一键与正文 hash**：`ai_run_idempotency` 上
   (应用, 主体类型, 外部用户标识, 幂等键) 唯一；请求摘要由 `AiRunRequestDigest` 计算（64 位十六进制）。
3. **202 返回 run 引用，同键同正文复用，异正文 409**：受理返回 `runId/runKey/status/releaseId/releaseVersion/reused`；
   命中幂等时 `reused=true` 且**不调用写入器**（单测用 `verify(writer, never())` 断言"不重新发起模型调用"）；
   同键异摘要直接 409，不返回旧结果。
4. **幂等摘要排除 traceId/token，包含服务/会话/消息/附件/context；事务内同时建立幂等记录、run 和首任务**：
   摘要只由这五个业务字段算出（traceId 与 token 不在入参里，从构造上不可能进入摘要）；
   `AiRunAcceptanceWriter#create` 在一个事务内插入幂等记录、运行与 `RUN_STEP` 首任务（载荷只存摘要）。
5. **数据库唯一冲突读取原记录并比较摘要**：内层事务撞唯一键后**在事务之外**回读赢家记录并比较摘要——
   放在同一事务里会被 REPEATABLE READ 的快照挡住（看不到并发赢家刚提交的行），
   因此写入与回读被拆到 `AiRunAcceptanceWriter`（内层事务）与 `AiRunServiceImpl`（外层编排）；
   摘要相同返回原 run，摘要不同 409，回读不到记录（例如运行键碰撞）同样按冲突结束，不返回假成功。

## 3. 关键约束与安全语义

- **受理幂等**：并发 10 个同键同正文请求只产生 1 个运行、1 条幂等记录、1 个首任务（真实 MySQL 并发 IT 断言）。
- **事务失败无半成品**：幂等记录、运行与首任务同事务；冲突与校验失败路径上行数不变（IT 断言 1/1/1）。
- **固定版本不是权限副本**：会话已固定版本时仍按**当前**授权重新判定（`resolvePinnedRun`），
   失权即拒绝受理；运行行不保存授权结论。
- **归属由服务端身份决定**：受理与读取都按当前主体过滤，越权与不存在同语义（IT 用第二个主体断言 404 与空分页）。
- **响应不含秘密**：受理与读取的 VO 字段名单里没有 `message`/`businessContext`/`idempotencyKey`/`inputDigest`/凭据/token
  （控制器单测直接断言字段名单）；队列与幂等记录都只保存摘要，不保存正文。
- **服务与会话一致**：会话已绑定服务时，受理请求的服务必须一致，否则拒绝而不是静默换版本。

## 4. 验证结果

工作目录：`/home/ctyun/桌面/zhongtai/ai-platform`（授权副本）。

| 命令 | 退出码 | 结论 |
|---|---|---|
| `./mvnw -o -pl basic-framework-module-ai test` | 0 | 261 例通过（O02 新增 19 例：摘要 5、服务 10、控制器 4） |
| `./mvnw -o -pl basic-framework-server test -Dtest=AiAppEndpointScopeContractTest` | 0 | 应用端端点策略与 scope 目录一致（运行端点已登记） |
| `./mvnw -Pintegration -pl basic-framework-server test -Dtest='AiRunAcceptanceIT,AiRunAcceptanceConcurrencyIT'` | 0 | 6 例通过（真实 MySQL：受理固定版本与三行同事务、幂等复用、同键异摘要 409、越权拒绝、Mapper 主体过滤与乐观锁、10 并发仅一个运行） |
| `node scripts/check-data-lifecycle.mjs` / `check-data-permission.mjs` | 0 / 0 | 生命周期台账（57 张表 / 36 条外键 / 32 张软删除）与数据权限分类通过 |

## 5. 顺带修复的依赖缺口

1. **跨卡接缝复用**：O01 的 `loadRunContext` 已按当前授权重新判定固定版本，O02 直接复用它完成
   "会话固定版本 + 受理前重新鉴权"，因此运行受理不需要再实现一套会话版本读取逻辑。
2. **修复 A04 的时间窗口用例抖动**：`AiTicketAttemptThrottleTest#windowExpiryRestoresAccess` 原来用
   50ms 窗口 + 80ms 等待断言"窗口内仍拦截、过期后恢复"，在门禁满载时线程调度延迟会吃掉整个窗口，
   导致偶发失败（本卡门禁首轮即命中）。现把窗口改为 2 秒、等待改为 2.1 秒：断言语义不变
   （窗口内仍拦截、过期后恢复），只是不再依赖亚秒级调度精度。

## 6. 门禁结果

| 命令 | 退出码 | 结论 |
|---|---|---|
| `sh .harness/verify.sh contracts` | 0 | 契约台账、权限目录、生命周期、字段目录、安全检查全部通过 |
| `sh .harness/verify.sh backend` | 0 | 编译、单元测试、格式、架构与覆盖率检查通过 |
| `sh .harness/verify.sh integration` | 1 → 0 | 40 个 IT 类 / 111 例全绿（含 O02 的 6 例）；唯一失败是尾部棘轮（新文件未登记，属预期），`--update` 后复验通过 |
| `node scripts/check-coverage-ratchet.mjs --update` / `all` | 0 / 0 | 新增登记 8 个文件（最低 91.67%），无基线下调、无登记删除，复验通过 |

## 7. 覆盖率

新增受测文件（数字取自 server 的 jacoco aggregate 报告）：

| 文件 | 覆盖率 |
|---|---|
| `domain/runtime/AiRunRequestDigest.java` | 91.67% |
| `service/run/AiRunServiceImpl.java` | 92.22% |
| `service/run/AiRunAcceptanceWriter.java` | 100% |
| `dal/mysql/run/AiRunMapper.java`、`AiRunIdempotencyMapper.java`、`AiRunTaskMapper.java` | 100% |
| `controller/app/v1/run/AiRunController.java` | 100% |
| `service/run/dto/AiRunAcceptResultDTO.java` | 100% |

## 8. 未验证项

1. **执行与状态流转**：本卡只到"已受理（ACCEPTED）+ 首任务 QUEUED"；运行执行、步数与预算、终态写入属 O04。
2. **任务领取与租约**：任务的 CAS/行锁领取、心跳、重启恢复与重试上限属 O03；本卡只建立首任务与重试等待列。
3. **事件流与取消**：SSE 事件、重放与取消属 O05；任务查询与人工重试属 O06。
4. **配额与限流**：按主体/应用的受理配额与限流属 Q 系列。

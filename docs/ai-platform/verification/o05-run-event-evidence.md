# O05 SSE 事件、重放与取消证据（2026-09-19）

本记录是 [O05 实现SSE事件、重放和取消](../tasks/O05.md) 的验收证据。

## 1. 交付内容

| 交付物 | 位置 |
|---|---|
| 持久化 | 迁移 `V63__ai_run_event.sql`：`ai_run.event_seq`（行锁内递增的序号分配点）+ `ai_run_event`（seq 唯一、状态、受控结果块、契约版本）；快照同步至 V63（逻辑删除表 32 → 33） |
| 事件服务 | `service/event/AiRunEventService(+Impl)`：`append`（分配序号 + 写入，随调用方事务提交）、`replay`（按 seq 有界重放 + 窗口过期检测）、`snapshot`（窗口过期时的明确出口）、`cancel`（显式取消：终态 + 终态事件 + 终止任务） |
| 事件映射 | `dal/mysql/event/AiRunEventMapper`：`selectAfterSeq`/`selectFirst` + `allocateSeq`（`UPDATE ai_run SET event_seq = event_seq + 1`，同语句完成读改写） |
| SSE 与取消 API | `controller/app/v1/run/AiRunController`：`GET /ai/run/events`（SSE：先鉴权再开流、afterSeq 重放、心跳是注释、终态即结束、错误关闭订阅）、`POST /ai/run/cancel` |
| 契约对齐 | 事件字段与 `docs/contracts/ai/run-event.schema.json`（RunEvent v1）逐字段对应：`schemaVersion/seq/runId/status/block/createdAt` |
| 台账同步 | `data-lifecycle.json`（新表进软删除策略 + 外键）、`data-permission-exemptions.json`（新豁免 `ai-run-event-data`，`subject-bound` + 逐表证据）、`PersistenceLifecycleIT` 预期表清单、`docs/contracts/ai/scope-catalog.md` 登记 2 个登录即可访问端点 |
| 错误码 | `1_003_004_006 AI_RUN_EVENT_WINDOW_EXPIRED`，与 `docs/contracts/ai/error-code-map.md` 两侧同步 |
| 边界测试 | `AiRunEventServiceImplTest`(7)、`AiRunControllerTest`(+3，共 8)、`AiRunEventIT`(5，真实 MySQL + 并发) |

## 2. 与卡片逐步实施的对应

1. **seq 事件存储/有界订阅/带 Bearer 的 fetch 流**：事件写入 `ai_run_event`（seq 唯一），
   重放与订阅都按 `afterSeq` 推进并限制单批条数（服务层 200、控制器 200）；
   SSE 端点使用框架既有的 MEMBER 会话（Bearer 票据）鉴权，客户端以 fetch 流消费。
2. **认证在开流前，之后错误用 terminal 事件**：`events` 方法先做归属判定与快照读取（失败按普通 HTTP 错误返回，
   不开匿名流），随后才创建 `SseEmitter`；开流之后的失败不再改变 HTTP 状态，而是关闭订阅
   （`completeWithError`）并以已落库的终态事件表达结果。
3. **afterSeq 重放与显式 cancel，清理断线连接**：重放按 seq 升序补发；
   取消是显式接口（乐观锁版本），写入 CANCELLED 终态 + 终态事件并终止任务；
   连接断开/超时由 `onError`/`onTimeout` 关闭发射器（有界连接时长 5 分钟，到期可换票续读）。
4. **seq 使用数据库并发安全分配，与状态/持久事件一致提交；心跳用 SSE 注释且不推进 seq**：
   序号由 `UPDATE ai_run SET event_seq = event_seq + 1` 在运行行的行锁内分配（同语句读改写），
   `append` 不自己开事务，由调用方把"状态 + 事件"放在同一事务提交；
   心跳是 `SseEmitter.event().comment(...)`，不写事件表、不推进序号（IT 断言 `ai_run.event_seq` 等于事件条数）。
5. **区分连接到期与运行失败**：连接到期只关闭订阅（运行状态不变），客户端换票后按 `afterSeq` 续读；
   运行失败是运行状态与终态事件的变化，二者互不混淆。
6. **过期重放窗口返回明确错误并转 run 快照**：`replay` 检测到客户端位置早于保留窗口时返回
   `AI_RUN_EVENT_WINDOW_EXPIRED`，并由 `snapshot` 给出当前状态与最新/最早序号，
   绝不用"新 POST 偷偷重跑"（那会产生第二个运行；受理幂等键会拦住，但语义上必须显式）。

## 3. 关键约束与安全语义

- **序号不重复**：并发 4 个追加请求拿到 4 个不同序号（真实 MySQL 并发 IT 断言无重复、`COUNT(DISTINCT seq)` 一致）。
- **状态与事件一致**：事件在调用方事务内写入，订阅者不会看到"事件说成功、库里还是 RUNNING"。
- **越权与不存在同语义**：订阅、重放、快照与取消都按当前主体过滤运行（IT 用第二个主体逐项断言 404）。
- **事件不含正文**：事件只保存受控结果块；`blockJson` 从 `toString()` 排除，提示词与模型输入正文不入事件表。
- **取消是显式动作**：已终态运行取消返回 409 且不追加事件（IT 断言事件条数不变）。

## 4. 验证结果

工作目录：`/home/ctyun/桌面/zhongtai/ai-platform`（授权副本）。

| 命令 | 退出码 | 结论 |
|---|---|---|
| `./mvnw -o -pl basic-framework-module-ai test` | 0 | 293 例通过（O05 新增 10 例：事件服务 7、控制器 3） |
| `./mvnw -Pintegration -pl basic-framework-server test -Dtest=AiRunEventIT` | 0 | 5 例通过（真实 MySQL：序号顺序与推进、并发追加不重复、窗口过期报错与快照出口、取消写终态事件并终止任务、越权拒绝） |
| `node scripts/check-data-lifecycle.mjs` / `check-data-permission.mjs` | 0 / 0 | 生命周期台账（57 张表 / 37 条外键 / 33 张软删除）与数据权限分类通过 |

## 5. 顺带修复的依赖缺口

本卡未发现需要顺带修复的既有缺口。事件序号直接复用运行行（`ai_run.event_seq`）作为分配点，
避免为"每运行一个计数器"再引入一张表或应用层锁；`append` 刻意不自己开事务，
从构造上保证"状态 + 事件"不会拆成两次提交。

## 6. 门禁结果

| 命令 | 退出码 | 结论 |
|---|---|---|
| `sh .harness/verify.sh contracts` | 0 | 契约台账、权限目录、生命周期、字段目录、安全检查全部通过 |
| `sh .harness/verify.sh backend` | 0 | 编译、单元测试、格式、架构与覆盖率检查通过 |
| `sh .harness/verify.sh integration` | 1 → 0 | 43 个 IT 类 / 124 例全绿（含 `AiRunEventIT` 5 例）；唯一失败是尾部棘轮（新文件未登记，属预期），`--update` 后复验通过 |
| `node scripts/check-coverage-ratchet.mjs --update` / `all` | 0 / 0 | 新增登记 2 个文件（98.68% / 100%），无基线下调、无登记删除，复验通过 |

## 7. 覆盖率

| 文件 | 覆盖率 |
|---|---|
| `service/event/AiRunEventServiceImpl.java` | 98.68% |
| `dal/mysql/event/AiRunEventMapper.java` | 100% |
| `controller/app/v1/run/AiRunController.java` | 100%（SSE 与取消路径均被覆盖） |

## 8. 未验证项

1. **浏览器端 SSE 消费**：本卡交付服务端 SSE 与契约对齐的事件源；真实浏览器/前端消费
   （含断线重连、换票续读）在 Q06 的浏览器验收中补齐。
2. **服务端推送模型**：当前订阅是"按 seq 轮询重放 + 心跳注释"的有界实现（连接时长上限 5 分钟），
   没有引入内存发布订阅；跨实例实时推送与背压策略属后续容量规划范围。
3. **事件保留期清理**：窗口过期已能正确报错，但按保留期清理事件的任务属 O06（任务查询、重试与清理）。
4. **流式 token 增量**：事件模型支持结果块，但逐 token 增量输出（M03 的流式接口）尚未接入运行执行链路。

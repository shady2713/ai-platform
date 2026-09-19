# O04 文本运行与有界步骤证据（2026-09-19）

本记录是 [O04 接入文本运行与有界步骤](../tasks/O04.md) 的验收证据。

## 1. 交付内容

| 交付物 | 位置 |
|---|---|
| 文本 run 执行器 | `service/run/AiRunExecutionService(+Impl)`：身份重建 → 固定版本重新判定 → 输入回放 → 上下文 → 模型 → 终态落库，全程受预算约束 |
| 预算与状态 | `domain/runtime/AiRunBudget`（步数 / 总耗时 / 工具次数上限，默认 8 / 120s / 4）、`service/run/dto/AiRunExecutionResultDTO`（终态、步数、工具次数、耗时、稳定原因；正文不进 `toString`） |
| 终态原子写入 | `service/run/AiRunTerminalWriter`：助手消息 + 运行终态在**同一事务**内落库，并带运行行乐观锁与任务租约两道栅栏 |
| 受控工具接口 | `service/run/AiToolExecutor`（工具实现必须按当前运行身份重新判定授权）+ `AiNoopToolExecutor`（当前没有工具实现 → 任何工具调用都"明确不支持"） |
| 持久化 | 迁移 `V62__ai_run_data_level.sql`：`ai_run.data_level`（受理时声明的数据分级随运行持久化，执行阶段的外发策略按它判定）；快照同步至 V62 |
| 边界测试 | `AiRunExecutionServiceImplTest`(8)、`AiRunTerminalWriterTest`(5)、`AiRunExecutionIT`(3，真实 MySQL) |

## 2. 与卡片逐步实施的对应

1. **串联请求校验/上下文/模型/结果落库**：执行前校验运行处于可执行状态（ACCEPTED/RUNNING，终态直接 409）、
   重建执行身份（A06）、按当前值重新判定固定发布版本（S03）、从会话回放用户消息（O01 落库的受控业务数据）、
   用上下文构造器拼装提示词（S04）、经统一调用编排发起模型调用（M05），
   最后把助手消息与运行终态写入（短事务）。
2. **限制步数、总耗时与工具预算**：`AiRunBudget` 统一给出三项上限；步数用尽 → `AI_RUN_BUDGET_EXCEEDED`（步数）、
   耗时用尽 → 同上（耗时）、工具次数用尽 → 同上（工具次数）；每次失败都先把运行写成 FAILED 再抛出，
   不存在"预算耗尽但状态仍是 ACCEPTED"的中间态。
3. **模型 tool-call 只交受控执行接口，终态写入原子化**：模型返回的 `toolCalls` 只被当作数据，
   逐个交给 `AiToolExecutor.find(toolKey)`；**没有实现时直接以 `AI_TOOL_UNSUPPORTED` 结束**，
   不静默跳过工具继续生成（静默跳过会让调用方以为工具真的执行过）。终态写入由
   `AiRunTerminalWriter` 在一个事务里完成，并带运行行乐观锁与任务租约栅栏。

## 3. 关键约束与安全语义

- **上游异常无假成功**：`ModelException` 统一收敛为平台错误码，运行写 FAILED、任务写 FAILED，
  助手消息不写入（IT 断言 `role='assistant'` 计数为 0）。
- **无工具实现时明确不支持**：`AiNoopToolExecutor` 让"没有工具实现"成为显式结论，而不是静默降级。
- **终态不会被晚到回调覆盖**：运行行乐观锁使重复执行/取消后的写入命中 0 行；
  任务租约栅栏使旧 worker 的落库命中 0 行（O03 的 IT 已断言）。IT 断言重复执行返回
  `AI_RUN_ALREADY_TERMINAL` 且状态与版本不变。
- **身份先于调用**：执行前重建身份，主体撤销/应用停用/范围收窄都会在模型调用之前失败（IT 断言撤销后拒绝）。
- **正文不进日志**：执行结果 DTO 排除 `outputText`，提示词与输出只进会话消息（受控业务数据）。
- **外发等级来自受理**：`ai_run.data_level` 在执行阶段决定外发策略的判定等级，
  不在执行时猜测默认值。

## 4. 验证结果

工作目录：`/home/ctyun/桌面/zhongtai/ai-platform`（授权副本）。

| 命令 | 退出码 | 结论 |
|---|---|---|
| `./mvnw -o -pl basic-framework-module-ai test` | 0 | 282 例通过（O04 新增 13 例：执行器 8、终态写入与工具接缝 5） |
| `./mvnw -Pintegration -pl basic-framework-server test -Dtest=AiRunExecutionIT` | 0 | 3 例通过（真实 MySQL：上游不可用 → 稳定错误 + 运行/任务 FAILED 且无助手消息、晚到执行不覆盖终态、身份撤销后拒绝） |
| `node scripts/check-data-lifecycle.mjs` | 0 | 生命周期台账与快照一致（V62 的 `data_level` 已同步） |

## 5. 顺带修复的依赖缺口

**受理时声明的数据分级没有持久化**（O02 遗留）：O02 只把 `dataLevel` 用于入参校验，
运行行没有该列，导致执行阶段无法按正确等级做外发策略判定（要么猜默认值，要么跳过策略）。
本卡新增 `ai_run.data_level`（V62，历史行默认 `L2_INTERNAL`）并在受理时写入；
同时把**用户消息**的落库补进受理事务（O02 只保存了请求摘要，执行阶段无处回放输入）：
消息作为受控业务数据只保存一份（会话消息表），运行通过 `source_run_id` 引用它。

## 6. 门禁结果

| 命令 | 退出码 | 结论 |
|---|---|---|
| `sh .harness/verify.sh contracts` | 0 | 契约台账、权限目录、生命周期、字段目录、安全检查全部通过 |
| `sh .harness/verify.sh backend` | 0 | 编译、单元测试、格式、架构与覆盖率检查通过 |
| `sh .harness/verify.sh integration` | 1 → 0 | 42 个 IT 类 / 119 例全绿（含 `AiRunExecutionIT` 3 例）；唯一失败是尾部棘轮（新文件未登记，属预期），`--update` 后复验通过 |
| `node scripts/check-coverage-ratchet.mjs --update` / `all` | 0 / 0 | 新增登记 5 个文件（最低 90.43%），无基线下调、无登记删除，复验通过 |

## 7. 覆盖率

新增/变化的受测文件（数字取自 server 的 jacoco aggregate 报告）：

| 文件 | 覆盖率 |
|---|---|
| `service/run/AiRunExecutionServiceImpl.java` | 90.43% |
| `service/run/AiRunTerminalWriter.java` | 100% |
| `service/run/AiNoopToolExecutor.java` | 100% |
| `service/run/AiToolExecutor.java` | 100% |
| `domain/runtime/AiRunBudget.java` | 93.33% |

## 8. 未验证项

1. **工具执行**：受控工具接口已就位，但**没有**工具实现（D08/D09 接入）；当前任何工具调用都以
   "明确不支持"结束，因此"工具结果回填与多步循环"尚未验证。
2. **流式与事件**：本卡只实现非流式文本执行；SSE 事件、重放与取消属 O05。
3. **无会话的一次性运行**：执行阶段要求运行绑定会话（输入从会话回放），无会话运行返回
   `AI_RUN_NOT_EXECUTABLE`；一次性运行的输入承载方式属后续切片。
4. **真实模型成功路径**：本机与 CI 未装配模型客户端，端到端只验证到"上游不可用 → 稳定失败"；
   成功路径由单元测试（模型调用被桩住）覆盖。

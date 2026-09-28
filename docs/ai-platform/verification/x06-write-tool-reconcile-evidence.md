# X06 开放受控业务写工具与结果核对 — 完成证据

本记录是 [X06 受控业务写工具与结果核对](../tasks/X06.md) 的验收证据。
依赖 [D09](../tasks/D09.md)（工具确认与分析步骤调度）、[D08](../tasks/D08.md)（工具注册与政策）、
[Q02](../tasks/Q02.md)（用量账本与配额）、[F09](../tasks/F09.md)（受控出站边界）均已有证据文档。

工作副本：`/home/ctyun/桌面/zhongtai/ai-platform`（授权副本）。命令前统一
`umask 022; export JAVA_HOME=$HOME/.local/opt/jdk17/usr/lib/jvm/java-17-openjdk-amd64`，
工作目录 `后端代码/basic-framework-boot`（除注明外）。

## 1. 交付内容

| 交付物 | 位置 |
|---|---|
| 持久化 | 迁移 `V87__ai_tool_write_action.sql`：`ai_tool_action` 增列（`tool_type`/`idempotency_param`/`idempotency_key`/`verify_source_ref`/`verify_param`/`attempt_epoch`/`verified_at`/`verified_by`/`verify_result`/`verify_evidence`）+ 唯一键 `uk_ai_tool_action_business`（同工具 + 同业务幂等键最多一条"可能已生效"的动作）；快照同步至 V87 |
| 写绑定声明 | `service/tool/AiToolWriteBinding`：版本输出 schema 的保留键 `write`（`idempotencyParam`/`reconcileOperation`/`reconcileParam`）解析与校验；幂等键必须是输入 schema 声明的**必填字符串**参数，核对查询必须与写操作不同 |
| 写工具闸门 | `service/tool/AiToolWriteGate`：发布期（政策不得 AUTO、写操作必须声明幂等键参数、核对操作必须已发布且声明核对参数）、执行期（冻结绑定与当前已发布版本逐项一致）、核对期（核对查询仍按业务键过滤） |
| 受控执行入口 | `service/tool/AiToolExecutor`：新增 `executeWrite(decision, idempotencyKey)` 与 `executeReconcile(connectorId, operationKey, arguments)`；通用 `execute(decision)` 明确拒绝写判定（`AI_TOOL_WRITE_REQUIRES_CONFIRMATION`） |
| 结果判定 | `service/tool/action/AiToolWriteOutcome`：APPLIED / REJECTED（能证明未发出或上游 4xx）/ UNKNOWN（超时、连接中断、3xx/5xx、响应不可用、未知异常）——判定方向是保守而不是乐观 |
| 动作服务 | `service/tool/action/AiToolActionServiceImpl`：写动作登记业务幂等键并按键复用（同键不同参数 409）；执行 CAS `CONFIRMED → EXECUTING(attempt_epoch+1)` 后落 `EXECUTED/FAILED/UNKNOWN`；`reconcile`（PROGRAM/MANUAL，CAS 单赢家）；执行前状态过期即释放业务键（惰性落 EXPIRED） |
| 状态机 | `service/tool/action/AiToolActionStateMachine`：新增 `requireReconcilable`（只有 EXECUTING/UNKNOWN 可核对） |
| 步骤调度 | `service/tool/action/AiAnalysisStepScheduler`：写/读 CONFIRM 动作创建结果区分"新建"与"幂等复用"（复用不重新发挑战、不再产生第二个动作），步骤结果新增 `OUTCOME_IDEMPOTENT_REUSE` 与 `actionStatus` |
| 应用端 API | `controller/app/v1/action`：新增 `POST /ai/action/reconcile`（`AiToolActionReconcileReqVO`），动作响应补 `toolType`/`idempotencyKey`/`attemptEpoch`/核对结论与证据 |
| 业务模拟器 | `basic-framework-server` 测试夹具 `AiBusinessWriteSimulator`：**有状态**收款业务系统（按 `payment_no` 真实记账、同键不重复入账、按键查询核对）+ 故障注入（入账后超时/未入账超时/入账后连接中断/入账后 5xx/核对查询 5xx/上游 400）；非模拟器目标交给真实 `GuardedExternalHttpClient` |
| 契约登记 | `docs/contracts/ai/error-code-map.md`（`1_003_006_057`–`063`，两侧同步）、`docs/contracts/ai/scope-catalog.md` + `AiAppEndpointScopeContractTest.REVIEWED_AUTHENTICATED_ENDPOINTS`（reconcile 端点双向登记）、`docs/integrations/open-api/ai-open-api.json`（端点 + `AiToolActionReconcileReq` schema + `AiToolAction` 新字段/状态） |
| 前端（控制面） | `apps/web-ele/src/views/ai/tool/modules/versions.vue` + `data.ts`：写工具版本必须声明**业务幂等键参数**与**核对查询**（界面校验 + 并入输出 schema 的保留键 `write`），并把"WRITE 不可发布"的过期文案改为 X06 规则；`modules.test.ts` 增写工具用例 |
| 测试 | 单测：`AiToolWriteBindingTest`(3)、`AiToolWriteGateTest`(4)、`AiToolWriteOutcomeTest`(4)、`AiToolActionServiceTest`(+3 共 8)、`AiAnalysisStepSchedulerTest`(+1 共 5)、`AiToolPolicyGateTest`(+1 共 10)、`AiToolActionControllerTest`(+1 共 5)、`AiToolActionMapperTest`(+1 共 5)、`AiToolServiceImplTest`(+1 共 6)；集成：`AiToolWriteExecutionIT`(8)、`AiToolWriteReconcileIT`(7)（真实 MySQL/Redis + 真实业务模拟器）；前端：`apps/web-ele/src/views/ai/tool` 14 例 |

## 2. 与卡片逐步实施的对应

1. **为每个写操作登记 AUTO/CONFIRM/DENY、当前权限、参数摘要、有效期与业务幂等键**
   - 政策矩阵：读工具 `AUTO/CONFIRM/DENY` 不变；写工具 `CONFIRM`/`DENY` 可发布，**`AUTO` 一律拒绝**
     （`AI_TOOL_WRITE_POLICY_UNSUPPORTED`）——写调用必须人工确认，模型选择工具不构成授权（FR-25）。
   - 每次写动作落库：`policy`（创建时政策）、`arguments_hash`（参数摘要）、`expires_at`（有效期）、
     `idempotency_param`/`idempotency_key`（业务幂等键）、`verify_source_ref`/`verify_param`（登记的核对查询）；
     执行前用**当前**已发布版本重新判定政策与绑定（"当前权限"在执行前复核，变化即拒绝）。
2. **执行前再鉴权且只消费一次确认，超时转 UNKNOWN 并调用登记的查询核对接口**
   - 执行前：主体归属 → 工具注册表（未注册/未审核（草稿）/停用一律拒绝）→ 当前政策仍为 CONFIRM →
     写绑定未变化 → 参数按 schema 重新校验 → CAS `CONFIRMED → EXECUTING`（尝试代数 +1，只有一个赢家）。
   - 结果判定见 `AiToolWriteOutcome`：只有"能证明请求没发出"或上游 4xx 才记 FAILED；
     其余一律 UNKNOWN，`AiToolActionService.reconcile(PROGRAM)` 调用**动作冻结的**核对查询（按业务键）收敛。
3. **增加人工核对/补偿说明与 UI，禁止通用自动重放写请求**
   - 后端提供 `POST /ai/action/reconcile`：`PROGRAM`（程序核对）/`MANUAL`（人工结论 + ≤200 字符说明）；
     只有 EXECUTING/UNKNOWN 可核对，核对是 CAS，终态不可改写。
   - 自动重放在结构上不可表达：写判定走通用执行入口被拒（`AI_TOOL_WRITE_REQUIRES_CONFIRMATION`）；
     写入口必须有业务幂等键；执行入口只接受 `CONFIRMED`（EXECUTING/UNKNOWN 不能被再次执行）；
     核对未生效只落 FAILED，重新发起必须走**新的**确认动作。
   - 控制面 UI：工具版本表单在 `WRITE` 类型下强制填写业务幂等键参数与核对查询（缺项在提交前拦住，
     后端发布期校验更严格），并把绑定并入输出 schema 的 `write` 段；过期文案（"首期不可发布"）已更正。
   - 核对/补偿说明的**交互界面**（`packages/ai-chat-ui/src/action`）未交付，见第 8 节第 2 条：
     后端 API 已可用，缺的是前端组件与浏览器验收。

## 3. 关键安全语义与不变量

- **同一业务意图最多一次副作用**：三层叠加——(a) 唯一键 `uk_ai_tool_action_business`
  （同工具 + 同业务键，且排除"确定无副作用"的终态 CANCELLED/EXPIRED/FAILED）；
  (b) 执行 CAS 消费确认一次（`attempt_epoch`）；(c) 上游收到的写请求带业务幂等键（模拟器按同键不重复入账，
  用例断言"入账次数恰为 1"）。
- **过期不产生副作用、也不永久堵键**：确认/执行对过期一律拒绝；已过期但仍是 PENDING/CONFIRMED 的动作
  在重新发起时惰性落 EXPIRED 释放业务键（执行中/未定/已执行的状态永远占键）。
- **改参数/改绑定必须重新确认**：确认要求参数哈希一致；执行要求冻结绑定与当前已发布版本一致
  （`AI_TOOL_WRITE_BINDING_CHANGED`）。
- **未注册/未审核/停用一律拒绝**：`requirePublishedVersion`（D08）保持不变，写路径在其之上再校验写绑定；
  停用与不存在对外同码（不泄漏存在性）。
- **外发只经受控出站**：写执行与核对查询都只经 `AiToolExecutor` → `AiHttpConnectorExecutor` →
  `ExternalHttpClient`（F09 边界）；用例用**真实的** `GuardedExternalHttpClient`（空允许清单）证明
  未授权目标在发送前被拒（`TARGET_NOT_ALLOWED` → 确定未生效 → FAILED），且业务模拟器零调用。
- **不含凭据/正文**：动作行从不回显参数正文与挑战（`@ToString.Exclude` + VO 不带这两个字段）；
  核对证据只存稳定事实（登记操作键 + 条目数，或人工说明 ≤200 字符）；响应与日志里没有上游正文、
  端点地址与密钥。新列名不含敏感词，`check-sensitive-tostring` 通过。

## 4. 验收用例对照

| 用例 | 覆盖点 | 证据 |
|---|---|---|
| AT-019 工具写超时结果未知 | 入账后读超时 → 动作 UNKNOWN（不是 FAILED）、确认已被消费不可重放、程序核对查到业务事实后收敛 EXECUTED | `AiToolWriteReconcileIT.timeoutAfterApplicationIsUnknownAndOnlyProgramReconcileProvesTheRealEffect`、`AiToolWriteOutcomeTest.ambiguousOutcomesAreUnknown` |
| AT-019 崩溃与超时可核对真实结果 | 崩溃留下的 EXECUTING（确认已消费、结论未落库）由核对收敛；核对查询不可用时动作保持未定（502） | `AiToolWriteReconcileIT.crashedAttemptIsResolvedByReconcileAgainstRealBusinessFact`、`reconcileQueryFailureKeepsTheActionUndetermined` |
| AT-020 确认后篡改参数 | 改参数 → 409 须重新确认；确认后改**写绑定**（换核对查询）→ 409 且不外发 | `AiToolWriteExecutionIT.tamperedArgumentsRejectedAndWriteBindingChangeRequiresReconfirmation`、`AiToolActionServiceTest.confirmRequiresSameSubjectSameChallengeSameArgumentsAndFreshness` |
| AT-021 重放/过期确认不产生第二次副作用 | 并发确认单赢家且只入账一次；重放执行被 CAS 挡住；过期确认不执行、且释放业务键后可重新发起（恰好一次副作用） | `AiToolWriteExecutionIT.concurrentConfirmationHasASingleWinnerAndOnlyOneSideEffect`、`confirmedWriteExecutesOnceWithTheBusinessKeySentUpstream`、`AiToolWriteReconcileIT.expiredConfirmationProducesNoSideEffectAndTheIntentCanBeRestarted` |
| 卡片 §4 并发确认只有一次副作用 | 同键重复提交复用同一条动作（不新建）；同键不同参数 409；并发同键插入由唯一键收敛 | `AiToolWriteExecutionIT.writeStepCreatesPendingActionWithFrozenBusinessKeyAndReusesSameIntent`、`AiToolActionServiceTest.writeActionFreezesBusinessKeyAndReusesExistingIntent` |
| 卡片 §4 确认后改参数/政策拒绝 | 政策改成非 CONFIRM、绑定变化、停用/未发布一律拒绝 | `AiToolWriteExecutionIT.registryJudgementBlocksUnpublishedOrDisabledWriteTools`、`AiToolPolicyGateTest`、`AiToolWriteGateTest.executionRequiresFrozenBindingToMatchCurrentPublishedVersion` |
| 卡片 §5 写工具垂直闭环 | 连接器（写 + 核对两个已发布操作）→ 写版本发布 → 步骤生成动作 → 确认 → 执行 → 真实业务记账 → 核对收敛 | `AiToolWriteExecutionIT`（8 例）+ `AiToolWriteReconcileIT`（7 例） |
| 卡片 §5 确认与核对 API | `POST /ai/action/reconcile`（PROGRAM/MANUAL）+ 响应新字段 | `AiToolActionControllerTest.reconcilePassesModeOutcomeAndNoteWithTheResolvedSubject`、`AiAppEndpointScopeContractTest`、`AiOpenApiContractTest` |
| 卡片 §5 业务模拟器及故障证据 | 有状态业务系统 + 6 种故障注入（含"入账后响应丢失"） | `AiBusinessWriteSimulator`（夹具）+ 两个 IT 的故障分支断言 |

## 5. 验证结果（真实命令、退出码、测试数）

| # | 命令 | 退出码 | 结论 |
|---|---|---|---|
| 1 | `./mvnw -q -o -pl basic-framework-module-ai spotless:apply` | 0 | 格式（spotless 构建期同样检查） |
| 2 | `./mvnw -o -pl basic-framework-module-ai test -Dtest='AiTool*Test,AiAnalysisStepSchedulerTest' -DfailIfNoTests=false` | 0 | **60 例通过**（X06 相关 8 个测试类） |
| 3 | `./mvnw -o -pl basic-framework-module-ai test` | 0 | **1107 例通过**（模块全量单测） |
| 4 | `./mvnw -o -q -pl basic-framework-module-ai -am install -DskipTests` | 0 | 供 server 集成测试解析（先装后用，避免幽灵失败） |
| 5 | `./mvnw -o -pl basic-framework-server test -Dtest='AiOpenApiContractTest,AiAppEndpointScopeContractTest' -DfailIfNoTests=false` | 0 | 10 例通过（开放接口契约、应用端端点策略双向一致） |
| 6 | `./mvnw -o -pl basic-framework-server verify -Pintegration -Dit.test='AiToolWriteExecutionIT,AiToolWriteReconcileIT' -DfailIfNoTests=false` | 0 | **15 例通过**（真实 MySQL/Redis + 真实出站边界 + 业务模拟器）；同次运行还跑了 server 单元测试 49 例 |
| 7 | `./mvnw -o -pl basic-framework-server verify -Pintegration -Dit.test='AiToolActionIT,AiToolRegistryIT,AiToolWriteExecutionIT,AiToolWriteReconcileIT' -DfailIfNoTests=false` | 0 | **23 例通过**（D08/D09 路径回归 + X06 两个新 IT 一起跑） |
| 7b | `./mvnw -o -pl basic-framework-server verify -Pintegration -Dit.test='PersistenceLifecycleIT,RemovedCapabilityMigrationIT' -DfailIfNoTests=false` | 0 | 6 例通过（表/外键/逻辑删除台账与已移除能力台账：本卡只加列不加表、不加 Job） |
| 8 | `node scripts/check-data-lifecycle.mjs` | 0 | 最终表 84 / 策略 84 / 逻辑删除列 53 / 物理外键 65；快照同步至 V87 |
| 9 | `node scripts/check-data-permission.mjs` | 0 | 应用表 73 张，运行时保护 2，显式豁免 71，平台托管 11（本卡不新增表） |
| 10 | `node scripts/check-source-quality.mjs` | 0 | 无超 800 行源码文件（本卡新增最大文件 370 行） |
| 11 | `node scripts/check-sensitive-tostring.mjs` / `check-field-injection.mjs` / `check-controller-validation.mjs` / `check-field-catalog.mjs` / `check-permission-catalog.mjs` / `check-safe-exception-handling.mjs` / `check-sensitive-diff-log.mjs` / `check-security-signals.mjs` / `check-gate-wiring.mjs` / `check-gate-rejection-tests.mjs` / `check-starter-documentation.mjs` / `check-exceptions.mjs` | 0（各） | contracts 门禁的脚本级检查全部通过 |
| 12 | `./mvnw -o -q -pl basic-framework-coverage -am verify -DskipTests` | 0 | 重建后端唯一权威覆盖率报告（`basic-framework-coverage/target/site/jacoco-aggregate/jacoco.xml`） |
| 13 | `pnpm exec vitest run --dom apps/web-ele/src/views/ai/tool`（工作目录 `前端代码/basic-framework-admin`） | 0 | 14 例通过（工具页面 3 个测试文件） |
| 14 | `pnpm -F @vben/web-ele run typecheck` | 0 | `vue-tsc --noEmit` 通过 |
| 15 | `pnpm exec eslint apps/web-ele/src/views/ai/tool` | 0 | 前端 lint 通过（含 prettier 规则） |

## 6. 覆盖率（聚合报告实测，新文件下限 80%）

| 文件 | 行数（可覆盖） | 覆盖 | 覆盖率 |
|---|---:|---:|---:|
| `service/tool/AiToolWriteBinding.java`（新） | 58 | 53 | 91.4% |
| `service/tool/AiToolWriteGate.java`（新） | 62 | 58 | 93.5% |
| `service/tool/action/AiToolWriteOutcome.java`（新） | 40 | 40 | 100.0% |
| `service/tool/action/dto/AiToolActionCreateResult.java`（新） | 6 | 5 | 83.3% |
| `controller/app/v1/action/vo/AiToolActionReconcileReqVO.java`（新，协议层 VO） | 0 | 0 | 无逻辑行（Lombok 访问器无行号） |
| `service/tool/action/AiToolActionServiceImpl.java`（改） | 238 | 215 | 90.3% |
| `service/tool/AiToolServiceImpl.java`（改） | 171 | 166 | 97.1% |
| `service/tool/action/AiAnalysisStepScheduler.java`（改） | 58 | 58 | 100.0% |
| `service/tool/action/AiToolActionStateMachine.java`（改） | 41 | 36 | 87.8% |
| `service/tool/AiToolExecutor.java`（改） | 26 | 26 | 100.0% |
| `controller/app/v1/action/AiToolActionController.java`（改） | 57 | 57 | 100.0% |
| `dal/mysql/action/AiToolActionMapper.java`（改） | 20 | 20 | 100.0% |

`node scripts/check-coverage-ratchet.mjs backend` 实测输出（退出码 1）：只报 4 个新文件"尚未登记单文件覆盖率基线"，
**没有**任何已有文件低于基线（`AiToolServiceImpl.java` 曾因删掉"首期只支持读工具"分支而降到 94.15%，
已补边界分支单测回到 94.7% 以上）。棘轮登记由协调者在完整后端/集成门禁后执行 `--update`；
本卡未下调任何基线、未删除任何登记。

## 7. 顺带修复的缺口（自查与集成测试暴露）

1. **D09 的"占位 EXECUTED"会谎报成功**：原实现先 CAS 置 `EXECUTED` 再执行，
   进程在上游调用中崩溃时动作行停在 `EXECUTED`（假成功，也没有核对入口）。
   现在中间态是 `EXECUTING` + `attempt_epoch`，"确认已被消费"与"结论未知"是两件事，
   X06 的 UNKNOWN/核对才有落脚点。
2. **写绑定的空白核对操作被放过**：`AiToolWriteBinding` 初版只校验参数名模式，未要求
   `reconcileOperation` 非空——单测（`AiToolWriteBindingTest`）先失败后修正：空值等于"没有登记核对查询"，
   必须拒绝而不是"稍后在执行期报错"。
3. **过期动作长期占住业务幂等键**：唯一键按状态排除无副作用的终态，但"已过期而状态仍是 PENDING"
   的行不会自动变成 EXPIRED（函数索引不能引用 `NOW()`），导致同一业务键永远无法重新发起。
   现在服务层在重新发起时惰性落 EXPIRED 释放键（集成测试 `expiredConfirmationProducesNoSideEffectAndTheIntentCanBeRestarted` 钉住）。
4. **核对查询的结果提取路径**：连接器导入器默认把"整个响应体当一条结果"，核对查询若按默认声明执行，
   空结果也会被算成"查到 1 条"。夹具里显式把 `response_json.listPath` 声明为 `items`
   （声明式字段来自 D02 的操作草稿），核对判定按"结果条数"而不是"请求是否成功"。

## 8. 未验证项与未交付项

1. **真实业务系统的写操作**：未授权生产系统与凭据，本卡用**有状态业务模拟器**（真实记账、按键去重、
   故障注入）验证平台语义；真实收款系统的端到端写入需要用户明确授权后才能执行（属 Q10 验收范围）。
   模拟器与真实系统的差异：网络时延/超时由故障注入模拟，而不是真实 socket 超时。
2. **核对/补偿说明的交互界面未交付**：控制面的"写工具版本声明"已在工具页面落地（见第 1 节），
   但 `packages/ai-chat-ui/src/action`（对话内的确认/核对/补偿说明组件）没有实现——
   本卡交付的是后端 API（`confirm`/`reject`/`execute`/`reconcile`）与语义。
   当前无该界面的后果：核对与补偿说明只能由集成方直接调用 API，浏览器验收无法覆盖；
   后续卡片需要补 `packages/ai-chat-ui/src/action` 组件、组件测试与浏览器验收。
3. **多实例并发**：并发确认/并发核对用同进程两线程 + 数据库 CAS/唯一键验证；跨实例行为依赖同一
   SQL 语义，未做多实例压测。
4. **`AUTO` 写政策**：本卡明确**不支持**（发布即拒）。若产品后续要求"幂等写可自动执行"，
   需要新卡放开并重新评估（当前安全基线：写调用必须人工确认）。
5. **写工具的用量行**：写调用不写模型用量账本（`ai_usage_ledger` 的语义是"一次模型调用一条"，
   带 token 与端点维度；把工具调用写进去会让"按模型聚合"失真）。写路径的事实行是
   `ai_tool_action`（幂等键、尝试代数、状态、结论、核对证据）与 `ai_run.step_count`；
   模型用量仍由既有链路（Q02/M05）按模型调用记录。
6. **`response_json` 的编辑入口**：核对查询的提取路径目前只能通过导入声明 + 直接改库设置，
   管理端没有编辑该字段的接口（不在本卡允许路径内）；生产使用前需要补一个草稿声明编辑入口。

## 门禁结果（主管复核后补记）

| 门禁 | 退出码 | 关键结果 |
|---|---|---|
| `contracts` | 0 | 生命周期/数据权限台账、源码质量（含 800 行上限）、权限目录、门禁接线、安全信号、例外台账全绿 |
| `backend` | 0 | `./mvnw -q clean verify` 全绿（module-ai 1107 例含本卡新增 60 例） |
| `integration` | 0（棘轮登记后） | IT 全绿（本卡新增 15 例；D08/D09 回归 23 例通过）；首次运行仅"单文件覆盖率基线未登记"失败，登记后 `check-coverage-ratchet.mjs backend` 通过，**既有文件无一低于基线**（`AiToolServiceImpl` 曾瞬时 94.15% < 94.77%，补边界分支用例后 97.1%） |
| `frontend` | 0 | `pnpm check` + `lint` + `test:coverage` + 生产构建全绿：401 个测试文件 / 2273 例、工作区行覆盖 91.83%；工具版本表单的写绑定改造有 14 例组件测试 |

新增单文件基线：`AiToolWriteBinding` 91.4%、`AiToolWriteGate` 93.5%、`AiToolWriteOutcome` 100%、`AiToolActionCreateResult` 83.3%（新文件最低 80% 满足）。

# D07 API 查询参数与结果归一化证据（2026-09-21）

本记录是 [D07 实现API查询参数与结果归一化](../tasks/D07.md) 的验收证据。
依赖 [D05](../tasks/D05.md)（已校验计划）、[D02](../tasks/D02.md)（声明式 HTTP 连接器与 operation）、
[F10](../tasks/F10.md)（夹具）均已有证据文档（`d05-query-plan-evidence.md`、`d02-http-openapi-evidence.md`、
`f10-fixtures-evidence.md`）。

## 1. 交付内容

| 交付物 | 位置 |
|---|---|
| 结果 Schema | `domain/result/AiResultSchema`：结果列的**唯一白名单**（逻辑码 + 语义类型 + 单位），投影依据 |
| 归一化结果 | `domain/result/AiNormalizedResult`：Schema + 行 + 完整性（COMPLETE/PARTIAL/FAILED）+ 原因 + 页数/条目数/上游状态；`toString()` 不含行数据 |
| 结果归一化 | `service/query/api/AiApiResultNormalizer`：投影 → 类型/金额归一 → 漂移判定 → 聚合 → 完整性结论 |
| API 计划执行器 | `service/query/api/AiApiQueryPlanExecutor`（+ `AiApiQueryRequestDTO`）：绑定已发布 operation、参数面来自声明、行范围必需、预算截断 |
| 错误码 | `1_003_006_039`（上游响应格式漂移）、`040`（结果值无法归一），与 `docs/contracts/ai/error-code-map.md` 两侧同步 |
| 测试 | `AiApiResultNormalizerTest`(6)、`AiApiQueryPlanExecutorTest`(7)、`AiResultSchemaTest`(2)，另修复并更新 D02 的 `AiHttpConnectorExecutorTest` |

本卡**不新增数据库迁移**（执行器无状态、不落库），四处台账无需改动；也不新增控制面端点
（卡片允许路径不含 controller，执行器由 D09/D11 与运行链路调用）。

## 2. 与卡片逐步实施的对应

1. **把 QueryPlan 绑定至已发布 operation 及允许参数**：执行器按 `connectorId + operationKey`
   取 operation，要求 `PUBLISHED`（草稿不可执行，与 D02 同语义）；
   计划里的过滤条件与行范围只能映射到 operation **声明过的参数名**（先逻辑码、后物理列名），
   未声明即拒绝（`AI_CONNECTOR_ARGUMENT_INVALID`）——不接受"猜一个参数名发出去"。
2. **应用可信用户范围，不接受模型 header**：行范围（`QueryScope`）由授权层给出并映射为声明参数；
   `AiApiQueryRequestDTO` **没有 header 字段**，D02 的 `AiConnectorExecutionRequestDTO` 同样只有
   参数值，HTTP 请求头只由连接器配置生成（D02 的结构性约束）——模型与调用方都提供不了。
   行范围无法映射到声明参数时**拒绝执行**（403），避免"没有行约束就看全量"。
3. **转换列类型/单位/时间/完整性，只有完整分页才输出完整统计**：归一化按语义类型解析取值
   （金额 → `BigDecimal`，时间 → ISO-8601 文本，布尔 → Boolean）；
   结果 Schema 携带单位（来自数据集定义）；完整性只在"上游 COMPLETE 且未触达任何预算"时为
   `COMPLETE`，`completeStatistics()` 是调用方判断能否宣称完整统计的唯一依据。
4. **分页循环检测重复游标并限制页数/条数/总耗时/响应大小；任何截断或失败不能标 COMPLETE**：
   页数与重复游标由 D02 执行器负责（本卡修复了"重复游标仍标 COMPLETE"的缺陷，见第 6 节）；
   D07 追加条目数、响应字节、总耗时三项预算，触顶即降级为 `PARTIAL` 并给出稳定原因
   （`item-limit`/`size-limit`/`time-limit`）；上游 FAILED 时保留其稳定原因码（如 `HTTP_503`）。
5. **金额按十进制字符串归一，解析失败明确报错；结果列先做范围过滤再进入模型**：
   `coerceNumber` 对 `"1,234.00"` 这类无法解析的取值抛 `AI_QUERY_RESULT_INVALID`（不静默补 0）；
   聚合用 `BigDecimal`（AVG 定点 6 位、HALF_UP），输出仍是十进制；
   归一化的**第一步就是投影**：上游多余字段（内部备注、审计列等）在进入平台之前就被丢弃，
   单测断言行的键集合恰好等于结果 Schema 的码集合。

## 3. 关键约束与安全语义

- **投影即最小可见面**：结果行只含计划里的列；上游多给的字段不进入平台、更不进入模型。
- **格式漂移不静默**：条目不是 JSON 对象、或"有数据但一个期望列都没有"（字段名变了）→
  `AI_QUERY_RESULT_FORMAT_DRIFT`（502）；取值类型/金额格式不符 → `AI_QUERY_RESULT_INVALID`（400）。
  空结果（上游确实返回空列表）是合法结果，不算漂移。
- **统计一致性**：`PARTIAL`/`FAILED` 的结果带原因返回（保留 `repeated-cursor`/`page-limit`/`HTTP_503`
  等），但 `completeStatistics()` 为 false——下游不能把它当全量（AT-039）。
- **聚合口径与 SQL 侧一致**：`SUM/AVG/COUNT/COUNT_DISTINCT/MIN/MAX`；NULL 值不参与 SUM/AVG/MIN/MAX，
  `COUNT` 只计非空（与 D06 的 `COUNT(列)` 同口径），不做全局 COALESCE。
- **行序确定**：输出按维度值升序排列，分页与快照比对可复现。
- **金额不用二进制浮点**：从解析到聚合到输出全程 `BigDecimal`。

## 4. 验收用例对照

| 用例 | 覆盖点 | 证据 |
|---|---|---|
| AT-031（上个月华东前十） | 计划绑定 operation 并执行 | `AiApiQueryPlanExecutorTest.bindsPlanFiltersAndScopeToDeclaredParametersOnly` |
| AT-032（授权范围限定） | 行范围映射为声明参数且必需 | 同上、`refusesWhenRowScopeCannotBeAppliedUpstream` |
| AT-038（API 分页完整） | 完整取完才 COMPLETE | `AiApiResultNormalizerTest.completenessNeverUpgradesPartialOrFailedSources`、`AiApiQueryPlanExecutorTest.partialUpstreamKeepsPartialReason` |
| AT-039（分页截断/失败） | PARTIAL/FAILED 且保留原因 | `AiApiQueryPlanExecutorTest.budgetTruncationDowngradesCompleteness`、`failedUpstreamKeepsStableReasonAndNeverClaimsComplete` |
| 格式漂移 422/上游协议错误 | 漂移明确报错 | `AiApiResultNormalizerTest.refusesFormatDriftInsteadOfSilentlyReturningEmpty` |
| 结果 schema 与统计一致性 | 投影白名单 + 聚合口径 | `AiApiResultNormalizerTest.projectsToPlanColumnsAndAggregatesExactDecimals`、`aggregatesAllAllowedAggregationsWithNullSemantics`、`rowsAreOrderedDeterministicallyByDimensions` |

## 5. 验证结果

工作目录：`/home/ctyun/桌面/zhongtai/ai-platform`（授权副本）。
命令前统一 `umask 022; export JAVA_HOME=$HOME/.local/opt/jdk17/usr/lib/jvm/java-17-openjdk-amd64`。

| 命令 | 退出码 | 结论 |
|---|---|---|
| `./mvnw -o -pl basic-framework-module-ai test` | 0 | 440 例通过（D07 新增 15 例：归一化 6、执行器 7、结果 Schema 2；另更新 D02 重复游标用例） |
| `sh .harness/verify.sh contracts` / `backend` / `integration` | 0 / 0 / 1（仅尾部棘轮） | 见第 7 节 |

## 6. 顺带修复的依赖缺口（重要）

1. **D02 重复游标仍标 COMPLETE（安全相关，D07 归一化测试暴露）**：
   `AiHttpConnectorExecutor` 在命中重复游标时只设 `stoppedReason=repeated-cursor` 而保留 `status=COMPLETE`，
   下游会把"没取完"当成完整结果——正是 AT-039 禁止的"宣称总额完整"。
   修复：重复游标一律置 `PARTIAL`（D07 的允许路径包含 `adapter/connector/http`），
   并同步更新 D02 的单测期望（`paginationStopsOnRepeatedCursorAndMarksPartialAtPageLimit`）。
2. **停止原因一律保留（自查修复）**：归一化最初在状态为 COMPLETE 时把 `reason` 清空，
   导致"为什么停"丢失；改为始终保留上游停止原因，便于排查与审计。
3. **物理列名回退键的测试修正**：维度 `customer_name` 的物理列是 `customer_id`（不是 `customer`），
   测试用例按真实定义修正——回退键语义因此被真实覆盖。
4. **`AiResultSchema` 覆盖率不足（棘轮暴露）**：`column()` 访问器无调用方（死代码），删除并补
   结果 Schema 单测（码唯一/非空校验 + 白名单语义）后达标——不靠下调基线。

## 7. 门禁结果

| 命令 | 退出码 | 结论 |
|---|---|---|
| `sh .harness/verify.sh contracts` | 0 | 契约台账、权限目录、生命周期、字段目录、安全检查全部通过 |
| `sh .harness/verify.sh backend` | 0 | 编译、单测、格式、架构与覆盖率检查通过 |
| `sh .harness/verify.sh integration` | 1 | **仅尾部覆盖率棘轮**因新文件未登记而失败；52 个 IT 类 / 167 例 **0 失败 0 错误** |
| `node scripts/check-coverage-ratchet.mjs --update` | 0 | 登记 D07 新增文件，无基线下调、无登记删除 |
| `node scripts/check-coverage-ratchet.mjs all` | 0 | 单文件基线全部通过 |

## 8. 覆盖率

| 文件 | 覆盖率 |
|---|---|
| `service/query/api/AiApiResultNormalizer.java` | 88.51% |
| `service/query/api/AiApiQueryPlanExecutor.java` | 91.76% |
| `service/query/api/AiApiQueryRequestDTO.java` | 100% |
| `domain/result/AiResultSchema.java` | 100% |
| `domain/result/AiNormalizedResult.java` | 100% |

## 9. 未验证项

1. **真实 HTTP 上游端到端**：本机未配置出站允许清单与可访问的 HTTPS 目标（D02/D03 同一环境限制），
   因此"连接器 → 真实接口 → 分页 → 归一化"的端到端路径**未在真实上游验证**；
   本卡验证到"绑定已发布 operation + 声明参数面 + 预算与完整性语义 + 归一化正确性"（单测覆盖，
   D02 执行器的出站与分页行为另有其自身证据）。
2. **HTTP 来源的数据集**：D04 只允许 MySQL 只读对象作为数据集来源，因此本卡的执行器直接以
   `connectorId + operationKey` 绑定 operation，不经过"数据集声明 HTTP 来源"这一层；
   该层（含 operation 结果字段的目录化）需要扩展数据集定义，属于后续卡片范围。
3. **单位换算**：结果 Schema 携带单位（来自数据集定义），但"上游单位与声明单位不一致时的换算"
   未实现（上游不声明单位，无换算依据）——如未来上游返回单位，需要新的契约字段。
4. **结果进入模型前的二次授权**：本卡实现了"投影到计划列"（模型只能看到授权列）；
   "行级结果再按主体过滤"由授权层（A03）与运行链路决定，未在本卡验证。
5. **执行器端点**：卡片允许路径不含 controller，本卡不新增端点；D09/D11 会串起完整链路。

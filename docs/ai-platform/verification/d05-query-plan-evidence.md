# D05 自然语言查询计划生成与澄清证据（2026-09-20）

本记录是 [D05 实现自然语言查询计划生成与澄清](../tasks/D05.md) 的验收证据。
依赖 [D04](../tasks/D04.md)（语义数据集与版本）、[M03](../tasks/M03.md)（结构化输出）、[S04](../tasks/S04.md)（上下文与调试链路）
均已有证据文档（`d04-dataset-semantic-version-evidence.md`、`m03-text-stream-structured-output-evidence.md`、
`s04-debug-context-evidence.md`），本卡只消费其公开契约（已发布数据集版本 + 结构化模型调用）。

## 1. 交付内容

| 交付物 | 位置 |
|---|---|
| 计划契约 | `domain/query/ValidatedQueryPlan`：**包内构造**的不可变计划（只有校验器能建立），带物理映射与计划哈希；不提供任何"执行任意 SQL"的方法 |
| 校验器 | `domain/query/AiQueryPlanValidator`：固定顺序的五步校验（结构 → 授权与版本 → 指标字段类型 → 时间 → 参数），别名歧义转澄清 |
| 结果协议 | `domain/query/QueryPlanOutcome`（sealed）：`PLAN` 与 `CLARIFICATION` 两种正常结果；澄清含原因（AMBIGUOUS/UNSUPPORTED/OUT_OF_SCOPE）与有限候选 |
| 数据集解析 | `domain/query/ResolvedDatasetVersion`：按调用方授权范围裁剪后的数据集事实（计划里的 `dset_` 标识由数据集 code 派生） |
| 模型接缝 | `service/query/planner/AiQueryPlanModel`（端口）+ `AiQueryPlanModelAdapter`（走 M05 调用编排：策略先于网络调用、上游失败按稳定原因） |
| 摘要与提示词 | `service/query/planner/AiDatasetSummaryBuilder`：模型可见范围的唯一定义（授权字段/指标/维度/枚举/单位/粒度/时区/可信当前时间；不含行样本、物理对象、凭据、权限码） |
| 规划器 | `service/query/planner/AiQueryPlanner(+Impl)`：授权范围 → 版本可执行性 → 摘要 → PLAN/CLARIFICATION 分流 → 校验 → **有限修复**（默认 ≤2 次，修复提示按错误码给出，不复用异常正文） |
| 时钟 | `service/query/planner/AiQueryPlannerClockConfiguration`：显式 `Clock` Bean（时间边界可复现） |
| 控制面 API | `controller/admin/query/AiQueryController`：`POST /ai/query/plan`、`GET /ai/query/summary` + 3 个 VO |
| 权限 | 迁移 `V69__ai_query_planner_menu.sql`：菜单 4070/4071（`ai:query:plan`、`ai:query:summary`）；快照同步至 V69（本卡无新表，台账表数不变） |
| 错误码 | `1_003_006_030`–`036`（计划不合规/SQL 拒绝/越权数据集/需澄清/修复用尽/模型输出不可用/版本未发布），与 `docs/contracts/ai/error-code-map.md` 两侧同步 |
| 固定评测夹具 | `basic-framework-module-ai/src/test/.../testfixture/AiQueryPlanFixture`（语义定义/计划/固定时钟）+ `basic-framework-server/src/test/resources/ai/query-plan-fixtures/*.json`（4 份模型输出）+ 集成测试里的脚本化模型 |
| 测试 | `AiQueryPlanValidatorTest`(8)、`AiDatasetSummaryBuilderTest`(4)、`AiQueryPlannerImplTest`(10)、`AiQueryControllerTest`(4)、`AiQueryPlanIT`(6，真实 MySQL + 真实已发布版本 + 夹具模型) |

## 2. 与卡片逐步实施的对应

1. **仅提供授权数据集摘要给模型，输出固定 QueryPlan**：`AiDatasetSummaryBuilder` 只输出授权范围内的
   逻辑码、别名、语义类型、单位、枚举取值、粒度、时区与**可信当前时间**（来自注入 `Clock`）；
   输出契约是固定的 JSON Schema（PLAN/CLARIFICATION 两种判别结果），模型只回 JSON 对象。
   集成测试断言摘要里不含物理对象名、库名、只读账号与权限码。
2. **类型/字段/操作符/口径/时区校验，歧义转 clarification**：校验器逐项检查
   （过滤取值类型、枚举取值、操作符白名单、时间字段类型与粒度、时区一致性）；
   措辞命中目录**别名**（如"销售额"而目录里叫 `net_amount`）时抛 `AI_QUERY_CLARIFICATION_REQUIRED`，
   规划器把它转成带候选的澄清结果（候选只保留目录里真实存在的码）。
3. **不支持问题说明范围，不退回全库**：模型返回 `CLARIFICATION` 时按原样回传追问与候选；
   计划引用授权外数据集直接 403（`AI_QUERY_DATASET_NOT_ALLOWED`），修复重试复用同一授权集合，
   不存在"退回全库扫描"的路径（校验器只认识本次解析出的那一个数据集）。
4. **区分 PLAN 与 CLARIFICATION 运行结果；依次做结构、授权版本、指标字段类型、时间/粒度与参数校验**：
   校验顺序在 `AiQueryPlanValidator.validate` 中固定，顺序本身是安全语义（先挡 SQL 与结构越界，
   再确认授权，最后才解析业务口径，避免错误消息泄漏未授权字段）。
5. **生成只能由校验器建立的 ValidatedQueryPlan；修复不扩大授权与数据集**：
   `ValidatedQueryPlan` 构造器包内可见（`AiQueryPlanValidator` 同包），执行器只能拿到它；
   修复循环 `maxRepairs` 默认 2（上限 2），提示词只补充"哪里不合规"，数据集/授权/时钟均不变。

## 3. 关键约束与安全语义

- **模型返回 SQL 一律拒绝**（AT-036）：计划文本里出现 `;`/`--`/`/*`/括号或 SQL 关键字即
  `AI_QUERY_SQL_REJECTED`，且**不给修复机会**（集成测试断言模型只被调用 1 次）。
- **未知指标/歧义不猜**（AT-035）：未知码 → 非法字段（400）；命中别名 → 澄清（409 + 候选）。
- **时间使用固定 Clock**（AT-033）：窗口必须带偏移、半开区间 `[start, end)`、跨度 ≤366 天、
  端点落在可信当前时间的 ±（366 天 / 3650 天）内；时区与数据集声明不一致 → 澄清。
  集成测试的计划窗口由夹具按注入时钟生成，同一测试内两次调用得到**同一计划哈希**。
- **越权不可探知**：授权范围外的字段不出现在摘要里；计划引用它等价于未知字段（400），
  不会因为"字段存在但未授权"而暴露其存在性；指标依赖字段未授权 → 403。
- **运行层判别先于业务校验**：`kind` 不是 PLAN/CLARIFICATION 时 `AI_QUERY_MODEL_OUTPUT_INVALID`（502），
  不会把一句自然语言当计划执行。
- **响应不含实现细节**：计划 JSON 只含逻辑码、聚合、单位、时间口径与哈希；VO 字段名单里没有
  凭据/连接串/行数据（控制器契约测试断言）。

## 4. 验收用例对照

| 用例 | 覆盖点 | 证据 |
|---|---|---|
| AT-031（上个月华东前十） | 计划可生成、结构正确 | `AiQueryPlanIT.plansAgainstPublishedVersionWithStableHash`、`AiQueryPlanValidatorTest.acceptsValidPlanAndResolvesPhysicalMappings` |
| AT-033（日期边界与时区） | 固定时钟、半开区间、时区一致 | `AiQueryPlanValidatorTest.validatesTimeWindowAgainstFixedClock`、`AiQueryPlanIT.plansAgainstPublishedVersionWithStableHash`（窗口由注入时钟生成） |
| AT-035（未知指标/歧义销售额） | 追问或拒绝，无猜测执行 | `AiQueryPlanValidatorTest.unknownCodesAreRejectedAndAliasHitsAskForClarification`、`AiQueryPlanIT.asksForClarificationInsteadOfGuessing` |
| AT-036（SQL 片段/未知字段/危险函数） | 计划或校验拒绝 | `AiQueryPlanValidatorTest.rejectsSqlFragmentsAndUnknownKeys`、`AiQueryPlanIT.rejectsSqlFragmentFromModel`、`rejectsUnknownMetricAndStopsAfterRepairLimit` |
| 超修复次数结束 | 修复有界 | `AiQueryPlannerImplTest.stopsAfterRepairLimitIsExhausted`、`AiQueryPlanIT.rejectsUnknownMetricAndStopsAfterRepairLimit`（3 次输出后结束） |
| 模型返回 SQL 字符串拒绝 | 无 SQL 通道 | 同上（断言模型仅被调用一次） |

## 5. 验证结果

工作目录：`/home/ctyun/桌面/zhongtai/ai-platform`（授权副本）。
命令前统一 `umask 022; export JAVA_HOME=$HOME/.local/opt/jdk17/usr/lib/jvm/java-17-openjdk-amd64`。

| 命令 | 退出码 | 结论 |
|---|---|---|
| `./mvnw -o -pl basic-framework-module-ai test` | 0 | 412 例通过（D05 新增 29 例：校验器 8、摘要 4、规划器 10、控制器 4、模型适配器 3） |
| `./mvnw -Pintegration -pl basic-framework-server test -Dtest=AiQueryPlanIT` | 0 | 6 例通过（真实 MySQL + 真实已发布数据集版本 + 固定夹具模型） |
| `node scripts/check-permission-catalog.mjs` | 0 | 接口引用 117 个权限码，目录一致（含 V69 新码） |
| `node scripts/check-data-lifecycle.mjs` | 0 | 表 63 / 策略 63 / 逻辑删除列 38 / 外键 41，快照同步至 V69 |
| `sh .harness/verify.sh contracts` / `backend` / `integration` | 0 / 0 / 1（仅尾部棘轮） | 见第 7 节 |

## 6. 顺带修复的缺口（自查）

1. **非 ASCII 别名在到达歧义判定前被误判（自查修复）**：`codeList` 最初用逻辑码模式
   （`^[a-z][a-z0-9_]{0,63}$`）过滤，中文别名（"销售额"）会先被判成结构错误，
   导致"歧义转澄清"永远不触发。现在只做形状检查（非空/长度/去重），合法性由目录解析决定。
2. **排序字段的别名未按歧义处理（自查修复）**：`orderBy` 的未知码原先一律 400；
   现在与指标/维度/过滤一致：命中别名 → 澄清。三处判定收敛到同一语义。
3. **修复提示复用异常正文（自查修复）**：最初用 `ServiceException` 的消息作为修复提示，
   而异常正文可能带上游/用户内容、且提示词会外发给模型；改为按**稳定错误码**映射固定文案。
4. **夹具时钟精度导致计划哈希抖动（集成测试暴露）**：夹具按 `Instant.now()` 生成时间窗口，
   同一次测试内两次调用得到不同毫秒 → 计划哈希不同。改为截断到天后，同一输入必然同一哈希。
5. **测试夹具集中化**：`AiQueryPlanFixture` + 4 份模型输出 JSON 让"同问题同结论"可回归比对，
   也避免各测试各写一份计划字面量。
6. **`scripts/secret-scan.mjs`**：登记 D05 集成测试里的一次性只读账号口令。

## 7. 门禁结果

| 命令 | 退出码 | 结论 |
|---|---|---|
| `sh .harness/verify.sh contracts` | 0 | 契约台账、权限目录、生命周期、字段目录、安全检查全部通过 |
| `sh .harness/verify.sh backend` | 0 | 编译、单测、格式、架构与覆盖率检查通过 |
| `sh .harness/verify.sh integration` | 1 | **仅尾部覆盖率棘轮**因新文件未登记而失败；50 个 IT 类 / 157 例 **0 失败 0 错误**（含 `AiQueryPlanIT` 6 例） |
| `node scripts/check-coverage-ratchet.mjs --update` | 0 | 登记 D05 新增文件，无基线下调、无登记删除 |
| `node scripts/check-coverage-ratchet.mjs all` | 0 | 单文件基线全部通过 |

## 8. 覆盖率

| 文件 | 覆盖率 |
|---|---|
| `domain/query/AiQueryPlanValidator.java` | 87.83% |
| `domain/query/ValidatedQueryPlan.java` | 91.25% |
| `domain/query/QueryPlanOutcome.java` | 100% |
| `domain/query/ResolvedDatasetVersion.java` | 100% |
| `service/query/planner/AiQueryPlannerImpl.java` | 94.12% |
| `service/query/planner/AiDatasetSummaryBuilder.java` | 95.89% |
| `service/query/planner/AiQueryPlanModelAdapter.java` | 100% |
| `service/query/planner/AiQueryPlannerClockConfiguration.java` | 100% |
| `controller/admin/query/AiQueryController.java` | 84.38% |

（首次门禁 `QueryPlanOutcome` 与 `AiQueryPlanModelAdapter` 为 0%：前者定义了但规划器直接构造 DTO（领域类型成了死代码），
后者只走真实模型端点。处理方式是**让规划器真的用领域结果类型**并**补适配器单测**（Mockito 驱动 M05 编排），
而不是下调基线。）

## 9. 未验证项

1. **真实模型端到端**：本机无可用模型端点，规划链路用**固定夹具模型**验证（这也是卡片要求的
   "固定模型评测夹具"）；`AiQueryPlanModelAdapter` 走 M05 编排的真实网络路径未在本卡验证
   （S04 调试链路的证据覆盖同一接缝）。
2. **服务绑定范围**：`allowedDatasetIds` 由调用方给出（控制面按单个数据集；运行链路应传入
   服务发布版本绑定的数据集集合）。真实服务发布版本 → 规划器的绑定串联在 D06/D09 落地后补齐。
3. **澄清多轮对话**：本卡只产出澄清结果（问题 + 有限候选），把用户回答合并回计划的多轮状态机
   属于运行链路（O04/O05 的消息与事件）与前端页面（D10）范围。
4. **HTTP 数据集来源**：同 D04，只支持 MySQL 只读对象来源；API 来源在 D07 扩展。
5. **查询计划页面**：菜单 4070 已随迁移落地，页面在 D10 交付；本卡不含前端改动。

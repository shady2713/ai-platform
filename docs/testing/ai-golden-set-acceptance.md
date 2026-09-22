# 单系统分析黄金集验收报告（D11）

本报告是 [D11 完成单系统查询黄金集验收](../ai-platform/tasks/D11.md) 的交付物之一，
配套证据文档见 [d11-golden-set-evidence.md](../ai-platform/verification/d11-golden-set-evidence.md)。
本报告只记录**可复现的实测结果**：合成系统、两个入口（SQL / API）、逐项核对与失败样例清单。

## 1. 结论

| 项 | 结论 |
|---|---|
| 黄金数字严格正确 | **740.00 / 450.00 / 290.00 / 190.00 全部命中**（两个入口、同一合成系统） |
| 两个入口一致 | SQL 入口华东合计 740.00 = API 入口分客户之和（450.00 + 290.00） |
| 时间/退款/空值/粒度 | 逐条核对通过（第 4 节） |
| 权限 | 越权（目录外列、无行范围、白名单外来源、未声明参数）**全部在生成/执行前阻断** |
| 分页 | 两页取完 → `COMPLETE`；触顶 → `PARTIAL` + `page-limit`，不宣称完整统计 |
| 错误与漂移 | SQL 片段/危险函数/越权数据集/结构漂移/歧义指标 → 稳定错误码，无猜测执行 |
| 真实模型评测 | **未验证**（本环境无可用的模型端点与出站许可，见第 6 节）；确定性执行链路已全量覆盖 |

## 2. 合成系统与口径

夹具（唯一来源）：`后端代码/basic-framework-boot/basic-framework-server/src/test/java/com/basicframework/server/fixtures/ai/AiGoldenSetFixture.java`。
它同时定义 DDL/DML、数据集定义、计划与**期望常量**——两个入口各自断言同一组常量，避免"两边各写一套期望值"。

合成系统（真实 MySQL 8.4.11 容器内的 `golden_catalog`）：

| 对象 | 内容 |
|---|---|
| `orders` | 4 条订单（含退款列、9 月边界行） |
| `payments` | 3 条回款（同一订单多笔，含 `0.00` 与 `NULL`） |
| `v_sales_golden`（视图，数据集来源） | 一行一单：`net_amount = amount − COALESCE(refund_amount, 0)` |
| `v_payments_golden`（视图，数据集来源） | 按订单预聚合回款：`SUM(amount)`，**不放大订单行** |

黄金数字的推导（口径即算术，可逐行核对）：

| 客户 | 明细 | 净额 | 说明 |
|---|---|---|---|
| C001 / alice（杭州） | 200.00（8-01 00:00:00） + 150.00（8-31 23:59:59） − 60.00 退款 | **290.00** | 月初**起点计入**、月末最后一秒计入 |
| C002 / bob（上海） | 500.00（8-15） − 50.00 退款 | **450.00** | |
| 华东合计 | 290.00 + 450.00 | **740.00** | 8 月窗口 `[8-01 00:00, 9-01 00:00)`（Asia/Shanghai） |
| C001 回款（独立指标） | 190.00 + 0.00 + NULL | **190.00** | 回款与订单是两套指标，不得相加成 480.00 |
| 9 月边界行 | 999.00（9-01 00:00:00） | **不计入** | 半开区间末端排除 |

## 3. 两个入口的执行结果（确定性执行报告）

命令（工作目录 `/home/ctyun/桌面/zhongtai/ai-platform/后端代码/basic-framework-boot`）：

```sh
./mvnw -o -Pintegration -pl basic-framework-server verify \
  -Dit.test=AiGoldenSetAcceptanceIT -Dtest=AiGoldenSetAcceptanceIT -DfailIfNoTests=false -Djacoco.skip=true
```

退出码 **0**；`AiGoldenSetAcceptanceIT` **9 例通过 / 0 失败 / 0 错误**（69.57s）；
报告：`basic-framework-server/target/failsafe-reports/com.basicframework.server.integration.AiGoldenSetAcceptanceIT.txt`。

### 3.1 SQL 入口（D04 版本 → D05 校验 → D06 编译 → D03 只读执行）

| 用例 | 范围 | 计划 | 实测 | 期望 |
|---|---|---|---|---|
| `sqlEntryReturnsGoldenNumbersForEastScope` | `region IN (杭州, 上海)` | 华东 8 月按客户聚合净额，降序 | bob 450.00 → alice 290.00，合计 740.00 | 顺序与数字均命中 |
| `sqlEntryHonoursRowScopeAndTimeBoundary` | `customer_id = C001` | 同上 | 1 行 290.00 | 只返回授权客户；999.00 不参与 |
| `sqlEntryKeepsPaymentsAsASeparateMetricWithoutDoubleCounting` | `customer_id = C001` | 净额计划 + 回款计划 | 290.00 与 190.00 | 两套指标各自成立，未相加 |

行范围不是"取全量后再过滤"：`QueryScope` 由编译器强制 AND 进 WHERE（`QueryScope.in/eq` → 绑定参数），
执行使用真实只读账号 `d11_ro`（仅被授予 `golden_catalog.*` 的 SELECT）。

### 3.2 API 入口（D02 已发布 operation → D07 归一化）

出站夹具扮演"同一套系统的 HTTP 上游"：按 operation 声明的游标分页翻页（`cursorPath=next`、`maxPages=3`），
并**按请求里的 `customer_name` 参数过滤条目**——行范围真的传到了上游，不是本地过滤。

| 用例 | 上游行为 | 实测 | 期望 |
|---|---|---|---|
| `apiEntryReturnsTheSameGoldenNumbersAsTheSqlEntry` | 两页 + 末页 `next=null` | 2 次出站；bob 450.00、alice 290.00；`COMPLETE` | 与 SQL 入口合计一致（740.00） |
| `apiEntryReportsPartialWhenPaginationIsTruncated` | 上游一直返回 `next` | 3 次出站；`PARTIAL` + `page-limit`，`completeStatistics()=false` | 绝不宣称完整统计 |

API 入口的条目（两页）与 SQL 入口的同一套数据对应：`page1 = {bob 300.00, alice 200.00}`、
`page2 = {bob 150.00, alice 90.00}` → 聚合后与 SQL 的分客户结果**逐客户相等**。

### 3.3 逐项核对（卡片第 2 步）

| 核对项 | 做法 | 结果 |
|---|---|---|
| 时间 | 8 月半开区间（Asia/Shanghai）；夹具同时含 8-01 00:00:00 与 8-31 23:59:59 | 起点计入、末端排除；9-01 00:00:00 的 999.00 被排除 |
| 退款 | 视图口径 `amount − COALESCE(refund_amount, 0)`；C001 有 60.00 退款、C002 有 50.00 | 290.00 / 450.00 与手算一致 |
| 空值 | 回款含 `0.00` 与 `NULL`；退款含 `NULL` | `SUM` 忽略 NULL、`0.00` 正常参与；未出现"NULL 被当 0 放大"或"整组变 NULL" |
| 粒度 | 订单视图一行一单；回款视图按订单预聚合 | 净额不因回款条数放大（190.00 而非重复计数） |
| 权限 | 目录外列 / 空行范围 / 白名单外来源 / 未声明参数 | 四种越权均在编译或执行前阻断（第 4 节错误码） |
| 分页 | 两页 / 触顶 | `COMPLETE` / `PARTIAL + page-limit` |
| 错误 | SQL 片段、危险函数、越权数据集、歧义别名、未知指标、结构漂移 | 稳定错误码，无猜测执行 |

## 4. AT-031 至 AT-042 逐条对照

| 用例 | 本卡实测（D11） | 关联证据 |
|---|---|---|
| AT-031 上个月华东前十 | 450.00 在前、290.00 在后，合计 740.00（SQL 入口） | `AiGoldenSetAcceptanceIT.sqlEntryReturnsGoldenNumbersForEastScope`；API 侧 `AiApiQueryPlanExecutorTest` |
| AT-032 Alice 个人范围 | SQL 入口只返回 290.00；API 入口行范围映射为声明参数并真的发给上游 | `sqlEntryHonoursRowScopeAndTimeBoundary`、`apiEntryReturnsTheSameGoldenNumbersAsTheSqlEntry` |
| AT-033 日期边界与时区 | 8-01 00:00:00 计入、8-31 23:59:59 计入、9-01 00:00:00 排除 | `sqlEntryHonoursRowScopeAndTimeBoundary`；`AiMysqlQueryExecutionIT` |
| AT-034 订单回款多对多 | 净额 290.00 与回款 190.00 各自成立、不重复 | `sqlEntryKeepsPaymentsAsASeparateMetricWithoutDoubleCounting` |
| AT-035 未知指标/歧义销售额 | 别名"销售额"→ 澄清（`AI_QUERY_CLARIFICATION_REQUIRED`）；未知指标 → 拒绝（`AI_QUERY_PLAN_INVALID`） | `ambiguousOrUnknownMetricIsNeverGuessed`；模型驱动的评测见 Q04/Q10（本环境未验证） |
| AT-036 SQL 片段/未知字段/危险函数 | 计划校验阶段即拒绝（`;`、括号、`load_file`） | `dangerousPlanShapesAreRejectedByTheDeterministicChain`；`AiQueryPlanValidatorTest`、`QueryPlanSqlCompilerTest` |
| AT-037 数据源只读与目录限制 | 只读账号 + 白名单外来源拒绝；编译器只接受目录内列 | `unauthorizedAccessIsBlockedBeforeAnyQuery`；`AiMysqlReadOnlyConnectorIT`、`AiQueryPermissionIT` |
| AT-038 API 分页完整 | 两页全部参与统计，最终 `COMPLETE` | `apiEntryReturnsTheSameGoldenNumbersAsTheSqlEntry`；`AiApiResultNormalizerTest` |
| AT-039 分页截断/失败 | 触顶 → `PARTIAL` + `page-limit`，`completeStatistics()=false` | `apiEntryReportsPartialWhenPaginationIsTruncated`；`AiApiQueryPlanExecutorTest` |
| AT-040 SSRF/重定向/DNS 目标变化 | 未授权目标被拒、不跟随重定向（本卡沿用 D02 的受控出站与同源校验） | `d02-http-openapi-evidence.md`（`rejectsForeignOriginUnsafeArgumentsAndUndeclaredParameters`、302 → `HTTP_302`） |
| AT-041 OpenAPI 外部 ref/超深嵌套 | 有界解析、不自动访问外部资源 | `d02-http-openapi-evidence.md`（`skipsUnsupportedOperationsAndRecordsReasons`） |
| AT-042 schema 漂移 | 上游视图真的变化（`net_amount` 消失）→ 新版本 `DRIFTED`、不可发布 | `schemaDriftMarksTheNewVersionUnpublishable`；`AiDatasetVersionIT` |

> AT-035 的"模型评测"载体：本卡给出的是**确定性**的追问/拒绝路径（校验器与契约层）；
> "真实模型在含糊问题上是否追问"属于模型效果评测，由 Q04 评测套件与 Q10 端到端验收负责（见第 6 节）。

## 5. 失败样例清单

### 5.1 开发过程中真实暴露并修复的失败（均已由测试固定）

| # | 失败样例 | 现象 | 根因 | 处置 |
|---|---|---|---|---|
| 1 | 数据集定义中字段名与维度名都叫 `customer_name` | `createVersion` 抛 `AI_DATASET_DEFINITION_INVALID`（8 例全错） | 定义规定字段/指标/维度**共享一个命名空间**，重名即拒绝；夹具把"显示名"同时用作字段名与维度名 | 字段改名 `customer_display_name`（物理列仍是 `customer_name`），维度名保持 `customer_name`——维度名即结果列名，断言与口径不变 |
| 2 | 危险计划样例 3：把 `"net_amount"` 替换成 `"load_file"` | 断言"必须被拒绝"失败——**计划居然合法通过** | 计划文本里只有 `"total_net_amount"`，被替换的字符串根本不存在（替换是空操作），样例失去意义 | 改为替换 `"total_net_amount"`，`load_file` 命中 SQL 片段标记 → `AI_QUERY_SQL_REJECTED` |
| 3 | 覆盖率棘轮：`AiToolInputSchema` 当前 96.43% 低于既有基线 97.62% | 棘轮拒绝登记新文件（"不得降低既有基线"） | 三个"类型不符/超长"的拒绝分支（数值参数收到结构化取值、布尔参数收到非法取值、文本超长）没有用例；既有基线来自包含这些路径的历史运行 | 补 `AiToolInputSchemaTest.acceptsNumericValuesAndRejectsStructuredOrOversizedOnes`（数值按十进制接受、结构化与超长拒绝）→ 覆盖率 100% |
| 4 | 覆盖率棘轮：D09 的 `AiToolActionController`（0%）与 `AiToolActionMapper`（25%）从未登记基线 | 棘轮 `all` 失败（D09 的登记被同一门禁问题阻断） | D09 交付时未给控制面与 Mapper 写单测 | 补 `AiToolActionControllerTest`（鉴权策略唯一、归属只来自服务端主体、响应不带挑战）与 `AiToolActionMapperTest`（过滤列/排序/绑定参数/CAS）→ 两者均 100%，按"新文件 ≥80%"登记 |

### 5.2 逐项负面/边界样例（每个都有断言固定其结论）

| 样例 | 期望结论 | 固定它的用例 |
|---|---|---|
| 9-01 00:00:00 的 999.00 订单 | 不计入 8 月聚合 | `sqlEntryHonoursRowScopeAndTimeBoundary` |
| 退款 `NULL`（C001 第 2 单、9 月单） | 视为无退款，不置空整组 | `sqlEntryReturnsGoldenNumbersForEastScope`（290.00） |
| 回款 `0.00` 与 `NULL` | 各自按 SQL 语义参与/忽略 | `sqlEntryKeepsPaymentsAsASeparateMetricWithoutDoubleCounting`（190.00） |
| 同一订单多笔回款 | 预聚合后不放大订单行 | 同上（190.00 而非 380.00/570.00） |
| 上游一直有下一页（触顶） | `PARTIAL` + `page-limit` | `apiEntryReportsPartialWhenPaginationIsTruncated` |
| 行范围列不在目录内（`secret_owner`） | `AI_QUERY_COMPILE_FAILED` | `unauthorizedAccessIsBlockedBeforeAnyQuery` |
| 空行范围（`new QueryScope(List.of())`） | `AI_QUERY_SCOPE_REQUIRED`（不退回全库） | 同上 |
| 来源对象不在连接器白名单（`golden_catalog.payments`） | `AI_DATASET_SOURCE_NOT_AUTHORIZED` | 同上 |
| 行范围参数未在 operation 声明（`customer_id`） | `AI_QUERY_SCOPE_REQUIRED`（不查全量） | 同上 |
| 上游视图结构漂移（`net_amount` 消失） | `DRIFTED` + 不可发布（`AI_DATASET_VERSION_NOT_VERIFIED`） | `schemaDriftMarksTheNewVersionUnpublishable` |
| 计划里出现 `SUM(net_amount)` / `;` / `load_file` | `AI_QUERY_SQL_REJECTED` | `dangerousPlanShapesAreRejectedByTheDeterministicChain` |
| 计划引用别的数据集（`dset_other-dataset`） | `AI_QUERY_DATASET_NOT_ALLOWED` | 同上 |
| 计划引用业务别名（"销售额"） | `AI_QUERY_CLARIFICATION_REQUIRED` | `ambiguousOrUnknownMetricIsNeverGuessed` |
| 计划引用不存在的指标 | `AI_QUERY_PLAN_INVALID` | 同上 |

## 6. 真实模型评测报告（与确定性执行分开）

**结论：本环境未验证。**

| 项 | 情况 |
|---|---|
| 模型端点 | 未配置（无可用端点/凭据）；出站默认拒绝（`basic-framework.ai.http.allowedHosts` 为空），AI 能力默认关闭 |
| 因此无法评测 | "含糊问题是否追问""模型选对指标/时间窗的比例""修复轮次与失败率"等**模型效果**指标 |
| 本卡实际覆盖 | 确定性链路：固定时钟、固定计划、固定夹具、固定上游分页；AT-031/032/033/034/036/038/039/042 与越权阻断均为实测 |
| 确定性替身 | D05 的脚本化模型输出夹具（`AiQueryPlanFixture`）用于协议与失败路径；**Mock 不能证明模型效果**（F10 约束） |
| 后续承接 | Q04 评测套件（真实模型效果评测）与 Q10 端到端验收；届时按同一黄金集运行，本报告第 2 节的常量可直接复用 |
| 复现方式（有端点时） | 配置 `basic-framework.ai.http.allowedHosts`/`allowedPorts` 与模型凭据后运行 Q04 评测套件；命令在 Q04 交接记录中给出 |

## 7. 复现与产物路径

| 命令 | 退出码 | 产物 |
|---|---|---|
| `./mvnw -o -Pintegration -pl basic-framework-server verify -Dit.test=AiGoldenSetAcceptanceIT -Dtest=AiGoldenSetAcceptanceIT -DfailIfNoTests=false -Djacoco.skip=true` | 0 | `basic-framework-server/target/failsafe-reports/…AiGoldenSetAcceptanceIT.txt`（9 例） |
| `./mvnw -o -pl basic-framework-module-ai test -Dtest='AiToolActionMapperTest,AiToolActionControllerTest,AiToolInputSchemaTest' -DfailIfNoTests=false` | 0 | 补齐 D09/既有基线的 12 例（第 5.1 节 #3/#4） |
| `sh .harness/verify.sh contracts` / `backend` / `frontend` / `integration` | 见证据文档第 7 节 | Harness 输出 |

## 8. 未验证项

1. **真实模型评测**（第 6 节）：本环境无模型端点与出站许可，模型效果未验证，由 Q04/Q10 承接。
2. **真实浏览器验收**：D11 不涉及页面；"分页截断在界面上如何呈现"由 Q06/G5 用真实浏览器验收。
3. **跨系统/多连接器黄金集**：本卡按卡片要求只覆盖**单系统**（同一合成系统的两个入口）；
   跨系统与真实客户数据的验收属后续阶段。
4. **AT-040/AT-041 的现场重放**：本卡未重复运行 D02 的网络夹具用例（其证据在 `d02-http-openapi-evidence.md`），
   只在其之上断言"越权目标/未声明参数在计划与执行前被阻断"。

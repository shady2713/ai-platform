# D06 QueryPlan 到参数化 SQL 编译证据（2026-09-20）

本记录是 [D06 实现QueryPlan到参数化SQL编译](../tasks/D06.md) 的验收证据。
依赖 [D05](../tasks/D05.md)（已校验计划）、[D03](../tasks/D03.md)（只读执行链路）、[F10](../tasks/F10.md)（夹具）
均已有证据文档（`d05-query-plan-evidence.md`、`d03-mysql-readonly-evidence.md`、`f10-fixtures-evidence.md`）。

## 1. 交付内容

| 交付物 | 位置 |
|---|---|
| 编译器 | `service/query/compiler/QueryPlanSqlCompiler`：纯确定性编译（不调用模型/网络），单来源、无 JOIN、无子查询、标识符白名单、值全绑定、行范围强制 AND |
| 执行桥 | `service/query/compiler/AiCompiledQueryExecutor`：编译结果 → D03 只读查询请求（预算取编译结果，调用方不能放大；D03 守卫再校验一次来源与只读性） |
| 计划契约 | `domain/query/ValidatedQueryPlan`（D05 建立，D06 消费其物理映射与计划哈希） |
| 参数契约 | `domain/query/SqlParameter`：值 + JDBC 类型 + 敏感级别；`toString()` **不输出值** |
| 编译结果 | `domain/query/CompiledQuery`：SQL 文本、绑定参数、结果列 Schema、行数/超时预算、数据集版本锚点；`toString()` 不含 SQL 与值 |
| 行范围契约 | `domain/query/QueryScope`：授权层给出的行级约束（空集合拒绝编译，不退回全库） |
| 错误码 | `1_003_006_037`（编译失败）、`038`（缺行范围拒绝生成），与 `docs/contracts/ai/error-code-map.md` 两侧同步 |
| SQL 快照 | `basic-framework-module-ai/src/test/resources/ai/compiled-sql/canonical-query.sql`（语句结构变化必须显式更新快照） |
| 测试 | `QueryPlanSqlCompilerTest`(11，含快照与注入用例)、`AiCompiledQueryExecutorTest`(2)、`AiMysqlQueryExecutionIT`(6，真实 MySQL)、`AiQueryPermissionIT`(4，真实数据集版本) |

本卡**不新增数据库迁移**（编译是无状态纯函数），四处台账无需改动；也不新增控制面端点
（卡片允许路径不含 controller，编译器由 D09/D11 及运行链路调用）。

## 2. 与卡片逐步实施的对应

1. **从审核字段和指标 AST 编译 SELECT 聚合/过滤/排序**：`compile()` 按固定顺序拼装
   `SELECT ... FROM <审核来源> WHERE ... GROUP BY ... ORDER BY ... LIMIT ?`；
   指标只允许 `SUM/AVG/COUNT/COUNT(DISTINCT)/MIN/MAX`（编译期二次白名单），
   不接受任何 SQL 表达式字符串（计划里没有"表达式"字段，模型也给不出）。
2. **值参数化、标识符来源白名单**：所有取值（过滤、行范围、时间边界、LIMIT）都是 `?`；
   表名与列名逐个对照数据集定义的物理列白名单 + 标识符形状校验，
   目录外标识符一律 `AI_QUERY_COMPILE_FAILED`（单测用"同包测试助手"伪造被篡改的计划来验证）。
3. **关联按粒度与权限策略生成，禁危险函数/任意 SQL/隐式跨源 JOIN**：
   本期只编译**单一审核来源**（审核表或审核视图，多表关联由数据侧用审核视图表达），
   编译结果里没有 `JOIN`/`UNION`/子查询；权限策略以行范围（`QueryScope`）形式强制进入 WHERE。
4. **实现契约，编译器不调用网络或模型**：`ValidatedQueryPlan`/`ResolvedDatasetVersion`/`SqlParameter`/`CompiledQuery`
   全部落在 `domain/query`；编译器是纯函数（同一输入同一 SQL 与同一参数序列，单测断言）。
5. **受限 AST 与 NULL 口径**：聚合枚举即 AST；NULL 语义按 SQL 原生语义（不全局 `COALESCE`），
   执行 IT 断言 `AVG` 忽略 NULL（(12.50+7.00)/2 = 9.75）与 `COUNT(列)` 只统计非空值。
6. **行范围强制 AND，排序列必须属于本次结果**：行范围条件先拼进 WHERE，计划过滤条件只能追加；
   排序键必须是本次选中的指标或维度（D05 校验器 + D06 编译期双重确认），
   显式排序键之后追加剩余维度升序作为并列时的稳定次序（分页可复现）。
7. **先用审核来源完成单域聚合，再做真实 MySQL 测试**：`AiMysqlQueryExecutionIT` 在真实 MySQL 上
   验证 DECIMAL 精度、时区边界、NULL 口径、聚合、行范围与 LIMIT；
   订单/回款"分别聚合再关联"的固定派生表结构需要数据集侧声明关联域（D04 定义没有该字段，
   本卡不允许改 D04），因此**未实现跨来源派生表**，见"未验证项"。
8. **测试文件职责**：`QueryPlanSqlCompilerTest`（结构与注入）、`AiMysqlQueryExecutionIT`（精度/时区/聚合/NULL）、
   `AiQueryPermissionIT`（行范围与目录外标识符）。

## 3. 关键约束与安全语义

- **注入 payload 不进入 SQL 结构**（验收项）：单测在编译期直接构造带 payload 的过滤条件
  （绕过 D05 校验器）后断言 SQL 文本里没有 payload、没有 `UNION`/`DROP`；
  执行 IT 用 `C001' OR '1'='1` 作为过滤值执行，返回 0 行而不是全量。
- **行范围不可被覆盖**：计划过滤条件与行范围落在同一列时，SQL 里两条谓词 AND 组合
  （`customer_id IN (?) AND customer_id = ?`）；执行 IT 断言"行范围只给 C001、计划要 C002"时返回 0 行。
- **空行范围拒绝编译**（403 `AI_QUERY_SCOPE_REQUIRED`）：没有行级约束就不生成可执行 SQL。
- **结果列按逻辑码命名**：`SELECT customer_id AS customer_name` —— 下游（图表/报表）看到的是语义面，
  物理列名不外泄；排序键用逻辑码别名，避免"排序键不在结果里"的 SQL 错误。
- **时间边界按数据集时区换算**：窗口是带偏移的 ISO-8601，编译时换算成数据集声明时区的本地时间
  （列是 `DATETIME`）后绑定；IT 用 8 月 31 日 23:59:59 与 9 月 1 日 00:00:00 两行验证边界。
- **预算不放大**：`LIMIT` 走绑定参数，`maxRows ≤ 1000`、`timeoutMillis` 由编译结果给出，
  执行桥原样传给 D03；D03 的 SQL 守卫与只读账号是第二道防线。

## 4. 验收用例对照

| 用例 | 覆盖点 | 证据 |
|---|---|---|
| AT-031（上个月华东前十） | 编译结构正确、聚合与排序可用 | `QueryPlanSqlCompilerTest.compilesAggregationWithBoundValuesAndStableOrder`、`AiMysqlQueryExecutionIT.executesAggregationWithDecimalPrecisionAndTimezoneBoundary` |
| AT-032（授权范围限定） | 行范围强制生效 | `AiQueryPermissionIT.scopePredicateIsForcedAndNotRemovableByPlan`、`AiMysqlQueryExecutionIT.rowScopeLimitsResultEvenWhenPlanAsksForMore` |
| AT-033（日期边界与时区） | 半开区间 + 时区换算 | `AiMysqlQueryExecutionIT.executesAggregationWithDecimalPrecisionAndTimezoneBoundary`（9 月 1 日 00:00 不计入） |
| AT-034（精度与空值） | DECIMAL 精确、NULL 口径 | `AiMysqlQueryExecutionIT.executesAggregationWithDecimalPrecisionAndTimezoneBoundary`、`nullSemanticsFollowSqlWithoutGlobalCoalesce` |
| AT-036（SQL 片段/危险函数） | 结构固定、无 JOIN/子查询 | `QueryPlanSqlCompilerTest.refusesUnsupportedAggregationAndOperatorAtCompileTime`、`AiQueryPermissionIT.compiledStructureIsSingleSourceWithoutJoinsOrSubqueries` |
| AT-037（只读与目录限制） | 标识符白名单、只读执行 | `QueryPlanSqlCompilerTest.identifiersMustComeFromTheAuditedCatalog`、`AiQueryPermissionIT.refusesRowScopeColumnsOutsideTheAuditedCatalog`、执行 IT（只读账号） |
| 注入 payload 不进入 SQL 结构 | 全值参数化 | `QueryPlanSqlCompilerTest.injectionPayloadsNeverReachSqlStructure`、`AiMysqlQueryExecutionIT.injectionPayloadStaysABoundValueAndMatchesNothing` |
| 生成 SQL 快照 | 结构变更显式化 | `QueryPlanSqlCompilerTest.sqlMatchesTheCommittedSnapshot` |

## 5. 验证结果

工作目录：`/home/ctyun/桌面/zhongtai/ai-platform`（授权副本）。
命令前统一 `umask 022; export JAVA_HOME=$HOME/.local/opt/jdk17/usr/lib/jvm/java-17-openjdk-amd64`。

| 命令 | 退出码 | 结论 |
|---|---|---|
| `./mvnw -o -pl basic-framework-module-ai test` | 0 | 425 例通过（D06 新增 13 例：编译器 11、执行桥 2） |
| `./mvnw -Pintegration -pl basic-framework-server test -Dtest='AiMysqlQueryExecutionIT,AiQueryPermissionIT'` | 0 | 10 例通过（真实 MySQL + 真实只读账号 + 真实已发布数据集版本） |
| `sh .harness/verify.sh contracts` / `backend` / `integration` | 0 / 0 / 1（仅尾部棘轮） | 见第 7 节 |

## 6. 顺带修复的缺口（自查与集成测试暴露）

1. **维度排序键引用了不在结果里的列（集成测试暴露）**：`ORDER BY customer_name` 但 SELECT 里只有
   `customer_id`，真实 MySQL 直接报错。修复：维度也起别名（`customer_id AS customer_name`），
   排序/分组使用逻辑码别名——同时让结果列对下游呈现语义面。
2. **时间参数无法绑定（集成测试暴露）**：D03 的 `AiMysqlQueryRequest` 只接受 String/Number/Boolean，
   编译结果的时间窗口是 `LocalDateTime` → 直接 400。修复：在 D03 的请求白名单里允许
   `LocalDateTime`/`LocalDate`（D06 允许路径包含 `adapter/connector/mysql`），并注明"时间按数据集时区换算后绑定"。
3. **`ValidatedQueryPlan` 的不变式与测试的冲突**：编译期需要"被篡改的计划"来验证拒绝路径，
   但构造器对包外不可见（这正是设计）。新增**测试专用**同包助手
   `ValidatedQueryPlanTestFactory`（只在 src/test 中），既保留不变式又能覆盖拒绝分支。
4. **`BETWEEN` 分支一度写成直接拒绝**：`betweenPredicate` 成了死代码；修正为按 `[lower, upper]` 展开两个绑定参数。
5. **`AiCompiledQueryExecutor` 覆盖率不足（棘轮暴露）**：`parameterTypes` 是无调用方的死代码，
   删掉并补执行桥单测（预算与绑定值原样传递、空编译结果拒绝）后达标——不靠下调基线。
6. **`scripts/secret-scan.mjs`**：登记 D06 两个集成测试里的一次性只读账号口令。

## 7. 门禁结果

| 命令 | 退出码 | 结论 |
|---|---|---|
| `sh .harness/verify.sh contracts` | 0 | 契约台账、权限目录、生命周期、字段目录、安全检查全部通过 |
| `sh .harness/verify.sh backend` | 0 | 编译、单测、格式、架构与覆盖率检查通过 |
| `sh .harness/verify.sh integration` | 1 | **仅尾部覆盖率棘轮**因新文件未登记而失败；52 个 IT 类 / 167 例 **0 失败 0 错误**（含 D06 两个 IT 共 10 例） |
| `node scripts/check-coverage-ratchet.mjs --update` | 0 | 登记 D06 新增文件，无基线下调、无登记删除 |
| `node scripts/check-coverage-ratchet.mjs all` | 0 | 单文件基线全部通过 |

## 8. 覆盖率

| 文件 | 覆盖率 |
|---|---|
| `service/query/compiler/QueryPlanSqlCompiler.java` | 88.97% |
| `service/query/compiler/AiCompiledQueryExecutor.java` | 100% |
| `domain/query/CompiledQuery.java` | 100% |
| `domain/query/QueryScope.java` | 100% |
| `domain/query/SqlParameter.java` | 84.21% |

## 9. 未验证项

1. **跨来源派生表（订单/回款分别聚合再关联）**：蓝图允许"预审核视图"或"编译器固定派生表结构"两种做法；
   本卡只实现前者（单来源编译，关联由审核视图表达）。数据集定义里没有"关联域"字段，
   而 D06 允许路径不含 `domain/semantic`，无法在本卡声明关联 → 派生表结构未实现、未验证。
2. **审核十进制加减表达式**：指标定义只支持"单字段 + 聚合"，没有表达式 AST 字段（同上原因），
   因此"已审核的加减表达式"未实现；编译期对任何非白名单聚合直接拒绝。
3. **数据行级权限的真实主体映射**：`QueryScope` 由授权层（A03）提供；本卡验证了"行范围强制 AND 且不可覆盖"，
   但"主体 → 行范围"的真实映射（部门/客户集合）在 C/Q 系列与运行链路里落地。
4. **执行侧并发与超时表现**：编译结果的超时/行数预算已传给 D03，但未做高并发压测（D11 黄金集覆盖整体表现）。
5. **编译结果的对外端点**：卡片允许路径不含 controller，本卡不新增端点；D09（工具确认与分析步骤调度）
   与 D11（黄金集验收）会串起"计划 → 编译 → 执行"的完整链路。

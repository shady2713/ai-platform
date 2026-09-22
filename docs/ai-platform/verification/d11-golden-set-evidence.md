# D11 单系统查询黄金集验收证据（2026-09-22）

本记录是 [D11 完成单系统查询黄金集验收](../tasks/D11.md) 的验收证据。
依赖 [D06](../tasks/D06.md)（编译执行）、[D07](../tasks/D07.md)（API 归一化）、[D09](../tasks/D09.md)（工具动作链）、
[F10](../tasks/F10.md)（夹具）均已有证据文档（`d06-sql-compile-evidence.md`、`d07-api-normalization-evidence.md`、
`d09-tool-action-evidence.md`、`f10-fixtures-evidence.md`）。

本卡是**验收卡**：不新增生产代码、不新增迁移、不新增端点。交付物是合成系统夹具、端到端验收用例、
两份报告，以及为满足棘轮而补齐的既有文件单测。
四处台账（`basic_framework.sql` / `data-lifecycle.json` / `data-permission-exemptions.json` /
`PersistenceLifecycleIT`）与错误码映射**无需改动**（无新表、无新错误码、无新权限）。

## 1. 交付内容

| 交付物 | 位置 |
|---|---|
| 合成系统夹具（DDL/DML/定义/计划/期望常量） | `basic-framework-server/src/test/java/com/basicframework/server/fixtures/ai/AiGoldenSetFixture.java` |
| 端到端验收用例（SQL 与 API 两个入口，9 例） | `basic-framework-server/src/test/java/com/basicframework/server/integration/AiGoldenSetAcceptanceIT.java` |
| 单系统分析验收报告与失败样例清单 | `docs/testing/ai-golden-set-acceptance.md` |
| 补齐 D09 未登记文件：应用端动作控制器契约测试 | `basic-framework-module-ai/src/test/java/com/basicframework/module/ai/controller/app/v1/action/AiToolActionControllerTest.java` |
| 补齐 D09 未登记文件：动作 Mapper 查询形状测试 | `basic-framework-module-ai/src/test/java/com/basicframework/module/ai/dal/mysql/action/AiToolActionMapperTest.java` |
| 补齐既有基线：工具输入 schema 类型归一/拒绝用例 | `basic-framework-module-ai/src/test/java/com/basicframework/module/ai/domain/tool/AiToolInputSchemaTest.java`（新增 1 例） |
| 覆盖率基线登记（棘轮） | `docs/contracts/coverage-baseline.json`（登记 D09 的 6 个后端文件、D10 的 13 个前端文件；无下调） |
| 本证据记录 | `docs/ai-platform/verification/d11-golden-set-evidence.md` |

对外方法：本卡**不新增**对外方法（无 controller/service/接口变更）。
验收用例清单：`sqlEntryReturnsGoldenNumbersForEastScope`、`sqlEntryHonoursRowScopeAndTimeBoundary`、
`sqlEntryKeepsPaymentsAsASeparateMetricWithoutDoubleCounting`、`apiEntryReturnsTheSameGoldenNumbersAsTheSqlEntry`、
`apiEntryReportsPartialWhenPaginationIsTruncated`、`unauthorizedAccessIsBlockedBeforeAnyQuery`、
`schemaDriftMarksTheNewVersionUnpublishable`、`dangerousPlanShapesAreRejectedByTheDeterministicChain`、
`ambiguousOrUnknownMetricIsNeverGuessed`。

## 2. 与卡片逐步实施的对应

1. **对同一合成系统执行 SQL 与 API 两个入口**：
   - 合成系统由夹具在真实 MySQL 容器内建库建视图（`golden_catalog.orders/payments` +
     `v_sales_golden`/`v_payments_golden`），连接器使用**真实只读账号** `d11_ro`（仅 `golden_catalog.*` 的 SELECT）；
   - SQL 入口：D04 建数据集/版本（验证 + 发布）→ D05 校验器（固定时钟 `2026-09-20T12:00:00Z`）
     → D06 编译器 → D03 只读执行器；
   - API 入口：D02 HTTP 连接器 + OpenAPI 导入的 operation（声明 `customer_name` 参数）→ 补齐列表路径与
     游标分页后发布 → D07 执行器绑定该 operation 执行并归一化；
   - 出站由 `@TestConfiguration` 的 `ExternalHttpClient` 夹具扮演上游（本环境无真实出站许可）：
     按声明翻页，并**按请求里的 `customer_name` 参数过滤条目**——行范围确实传到了上游，不是本地过滤。
2. **核对时间/退款/空值/粒度/权限/分页/错误**：逐项断言见验收报告第 3.3 与第 5 节；
   时间用半开区间（含 8-01 00:00:00 与 8-31 23:59:59，排除 9-01 00:00:00 的 999.00），
   退款用 `COALESCE` 口径，回款含 `0.00` 与 `NULL`，粒度用"按订单预聚合的回款视图"防止行放大。
3. **真实模型评测与确定性执行分别出报告**：验收报告第 3 节为确定性执行报告（含实测数字与命令），
   第 6 节为真实模型评测报告——结论**未验证**（本环境无模型端点、出站默认拒绝），
   并写明由 Q04/Q10 承接与复现方式；确定性替身（脚本化模型输出）不能冒充模型效果（F10 约束）。

## 3. 关键约束与安全语义

- **同一组常量、两个入口**：期望值（740.00 / 450.00 / 290.00 / 190.00）只在夹具里定义一次，
  SQL 与 API 用例各自断言同一常量，避免"两边各写一套期望值"。
- **越权在生成前阻断**：目录外行范围列 → `AI_QUERY_COMPILE_FAILED`；空行范围 → `AI_QUERY_SCOPE_REQUIRED`；
  白名单外来源对象 → `AI_DATASET_SOURCE_NOT_AUTHORIZED`；行范围参数未在 operation 声明 → `AI_QUERY_SCOPE_REQUIRED`
  （绝不"没有行约束就查全量"）。
- **不宣称完整统计**：只有上游确认取完且未触顶才 `COMPLETE`；触顶为 `PARTIAL` + 稳定原因（`page-limit`）。
- **结构漂移不静默错算**：上游视图列消失后，新版本验证为 `DRIFTED`（`missingColumns=[net_amount]`），
  发布被拒（`AI_DATASET_VERSION_NOT_VERIFIED`），且断言上游结构哈希确实变化。
- **金额只用十进制**：期望值与断言全部走 `BigDecimal`，不经过二进制浮点。
- **模型输出不猜**：SQL 片段/危险函数 → `AI_QUERY_SQL_REJECTED`；越权数据集 → `AI_QUERY_DATASET_NOT_ALLOWED`；
  别名歧义 → `AI_QUERY_CLARIFICATION_REQUIRED`；未知指标 → `AI_QUERY_PLAN_INVALID`。
- **补齐的测试同样守住安全语义**：应用端动作控制器测试断言"每个端点有且只有一种鉴权策略（`@AuthenticatedOnly`）、
  归属只来自服务端会话主体、响应不带一次性挑战"；Mapper 测试断言"过滤列与排序固定、取值走绑定参数（不拼串）、
  状态转移是 id+version 的 CAS"。

## 4. 验收用例对照

| 用例 | 覆盖点 | 证据 |
|---|---|---|
| AT-031（上个月华东前十） | 450.00 在前、290.00 在后、合计 740.00 | `AiGoldenSetAcceptanceIT.sqlEntryReturnsGoldenNumbersForEastScope` |
| AT-032（Alice 个人范围） | SQL 只返回 290.00；API 行范围映射为声明参数 | `sqlEntryHonoursRowScopeAndTimeBoundary`、`apiEntryReturnsTheSameGoldenNumbersAsTheSqlEntry` |
| AT-033（日期边界与时区） | 月初起点计入、9 月起点排除 | `sqlEntryHonoursRowScopeAndTimeBoundary` |
| AT-034（订单回款多对多） | 净额 290.00 与回款 190.00 不重复 | `sqlEntryKeepsPaymentsAsASeparateMetricWithoutDoubleCounting` |
| AT-035（未知指标/歧义销售额） | 追问（澄清）或拒绝，无猜测执行 | `ambiguousOrUnknownMetricIsNeverGuessed`（确定性路径）；模型效果见验收报告第 6 节 |
| AT-036（SQL 片段/危险函数） | 计划校验阶段拒绝 | `dangerousPlanShapesAreRejectedByTheDeterministicChain` |
| AT-037（只读与目录限制） | 只读账号 + 白名单 + 目录内列 | `unauthorizedAccessIsBlockedBeforeAnyQuery`（另见 D03/D06 证据） |
| AT-038（API 分页完整） | 两页参与统计且 `COMPLETE` | `apiEntryReturnsTheSameGoldenNumbersAsTheSqlEntry` |
| AT-039（分页截断） | `PARTIAL` + `page-limit`，不宣称完整 | `apiEntryReportsPartialWhenPaginationIsTruncated` |
| AT-040（SSRF/重定向） | 未授权目标被拒、不跟随重定向 | D02 证据（本卡只在其上验证"越权参数/目标在执行前被阻断"） |
| AT-041（外部 ref/超深嵌套） | 有界解析、不访问外部资源 | D02 证据 |
| AT-042（schema 漂移） | 版本标 `DRIFTED` 且不可发布 | `schemaDriftMarksTheNewVersionUnpublishable` |
| 含糊问题追问（卡片验收项） | 别名 → 澄清；未知 → 拒绝 | `ambiguousOrUnknownMetricIsNeverGuessed` |
| 任何越权阻断（卡片验收项） | 四种越权路径 | `unauthorizedAccessIsBlockedBeforeAnyQuery` |

## 5. 验证结果

工作目录：`/home/ctyun/桌面/zhongtai/ai-platform`（授权副本）。
命令前统一 `umask 022; export JAVA_HOME=$HOME/.local/opt/jdk17/usr/lib/jvm/java-17-openjdk-amd64`。

| 命令 | 退出码 | 结论 |
|---|---|---|
| `./mvnw -q -o -pl basic-framework-server spotless:apply` | 0 | 格式已应用 |
| `./mvnw -o -pl basic-framework-server test-compile -DskipTests` | 0 | 编译通过 |
| `./mvnw -o -Pintegration -pl basic-framework-server verify -Dit.test=AiGoldenSetAcceptanceIT -Dtest=AiGoldenSetAcceptanceIT -DfailIfNoTests=false -Djacoco.skip=true` | 0 | **9 例通过 / 0 失败 / 0 错误**（69.57s）；报告 `basic-framework-server/target/failsafe-reports/com.basicframework.server.integration.AiGoldenSetAcceptanceIT.txt` |
| `./mvnw -o -pl basic-framework-module-ai test -Dtest='AiToolActionControllerTest,AiToolActionMapperTest,AiToolInputSchemaTest' -DfailIfNoTests=false` | 0 | 12 例通过（补齐的 D09 控制面/Mapper 与 schema 类型用例） |

## 6. 顺带修复的依赖缺口（真实暴露，均已由用例固定）

1. **夹具定义重名**：字段名与维度名都叫 `customer_name` → `createVersion` 抛 `AI_DATASET_DEFINITION_INVALID`
   （8 例全错）。根因：定义规定字段/指标/维度**共享命名空间**。处置：字段改名 `customer_display_name`
   （物理列仍是 `customer_name`），维度名保持 `customer_name`（结果列名与断言不变）。
2. **危险计划样例空替换**：把 `"net_amount"` 替换为 `"load_file"` 时计划文本里并没有该子串（只有
   `"total_net_amount"`），替换成了空操作 → 计划合法通过、断言失败。处置：改为替换 `"total_net_amount"`，
   `load_file` 命中 SQL 片段标记 → `AI_QUERY_SQL_REJECTED`。
3. **既有基线被真实跌破（D08 文件）**：`AiToolInputSchema` 当前 96.43% < 基线 97.62%——棘轮拒绝登记任何新文件。
   根因：三个"类型不符/超长"拒绝分支（数值参数收到结构化取值、布尔参数收到非法取值、文本超长）没有用例，
   历史基线来自包含这些路径的运行。处置：**补用例而不是下调基线**（`acceptsNumericValuesAndRejectsStructuredOrOversizedOnes`），
   该文件覆盖率 100%。
4. **D09 的两个文件从未登记基线（棘轮 `all` 失败）**：`AiToolActionController`（0%）、`AiToolActionMapper`（25%）。
   根因：D09 的 `--update` 被同一集成门禁问题阻断（D09 证据第 7 节），而交付时未给控制面与 Mapper 写单测。
   处置：补 `AiToolActionControllerTest`（4 例：鉴权策略唯一、归属来自服务端主体、执行/查询/分页映射、
   缺会话即拒）与 `AiToolActionMapperTest`（4 例：过滤列与排序、主体三元组过滤与空串绑定、id+version CAS、
   绑定参数而非拼串）→ 两文件均 100%，按"新文件 ≥80%"登记。
5. **D10 的 13 个前端文件从未登记基线（前端门禁尾部失败）**：D10 的 `--update` 同样被阻断。
   处置：在完整覆盖率数据下 `--update` 一次性登记（`docs/contracts/coverage-baseline.json`，
   22 增 3 改，全部为**上调或新登记**，无任何下调）。

## 7. 门禁结果

| 命令 | 退出码 | 结论 |
|---|---|---|
| `sh .harness/verify.sh contracts` | 0 | 契约台账、权限目录、生命周期、字段目录、安全检查全部通过 |
| `sh .harness/verify.sh backend` | 0 | 编译、单测（含本卡新增 12 例）、格式、架构与覆盖率检查通过 |
| `sh .harness/verify.sh frontend` | 1 → 0 | 首跑**仅尾部棘轮**失败（D10 遗留的 13 个前端文件未登记）；登记后复跑全绿 |
| `sh .harness/verify.sh integration` | 1（两次） | `mvn clean verify` 在 **184 例中 1 例既有负载敏感用例**失败处中断（D11 新增 9 例全部通过）；maven 中断导致尾部棘轮未执行 |
| `./mvnw -q -Pintegration clean verify -Dfailsafe.rerunFailingTestsCount=2` | 1 | 同一用例失败并两次重跑均失败（50.69s / 51.01s）→ 说明该用例在全量套件下稳定失败，不是偶发抖动 |
| `./mvnw -o -Pintegration -pl basic-framework-server verify -Dit.test=UserProfilePersistenceIT -Dtest=UserProfilePersistenceIT -DfailIfNoTests=false -Djacoco.skip=true` | 0 | **同一用例在干净容器上 8/8 通过（126.8s）** → 失败与"套件中更早用例留下的连接/锁"相关 |
| `./mvnw -q -o -pl basic-framework-module-ai verify` | 0 | 模块单测全量通过（覆盖率数据来源之一） |
| `./mvnw -q -Pintegration -pl basic-framework-coverage -am verify -DskipTests` | 0 | 基于"完整单测 + 完整 IT 执行"数据重算聚合报告 |
| `node scripts/check-coverage-ratchet.mjs --update` / `all` | 0 / 0 | 登记 19 个未登记文件（D09 后端 6、D10 前端 13）；**无基线下调、无登记删除** |

### 门禁偏差说明（如实记录，未掩盖、未跳过、未放宽）

- **现象**：`UserProfilePersistenceIT.olderTransactionSnapshotCannotPreserveSupersededPostsOrIntermediateSession`
  在全量套件中稳定失败，错误为 ~50 秒的锁等待（`system_user_post` 插入等待一个 idle-in-transaction 连接；
  MySQL `innodb_lock_wait_timeout` 默认 50s）。三次官方运行耗时 52.89s / 52.36s / 53.82s。
- **范围**：该用例位于 `basic-framework-server/src/test/java/com/basicframework/server/integration/UserProfilePersistenceIT.java`，
  **不在本卡允许修改范围**（本卡允许的 server 测试路径只有 `fixtures/ai`）；同一现象在 D09、D10 证据第 7 节已记录。
- **本卡未做**：未跳过、未排除、未注释、未放宽任何用例或基线；`--update` 拒绝在覆盖率数据不完整时写入（首次尝试即被拒）。
- **覆盖率数据来源**：官方集成门禁两次运行（IT 全部执行、用例数据完整）+ 该用例单独一次干净运行（通过）
  + module-ai 全量单测，再由聚合目标重算报告；随后 `--update` 只登记未登记文件、只上调既有值。
- **结论**：D11 自身交付项（黄金集 9 例、AT-031..042、越权阻断、含糊追问）全部通过；
  集成门禁的红色来自既有用例与套件环境的相互作用，修复点不在本卡允许路径内。

## 8. 覆盖率

本卡不新增主源码文件；新增/变更的**登记项**（`docs/contracts/coverage-baseline.json`）：

| 文件 | 登记基线 | 说明 |
|---|---|---|
| `controller/app/v1/action/AiToolActionController.java` | 100 | D09 遗留，本卡补测后登记 |
| `dal/mysql/action/AiToolActionMapper.java` | 100 | D09 遗留，本卡补测后登记 |
| `domain/tool/AiToolInputSchema.java` | 100 | 原基线 97.62（跌破），本卡补测后上调 |
| `adapter/connector/http/AiConnectorAuthHeaders.java` | 100 | 原基线 90.91（上调） |
| `adapter/connector/http/AiHttpConnectorExecutor.java` | 85.37 | 原基线 84.39（上调） |
| D10 的 13 个前端文件 | 91.78–100 | 首次登记（均 ≥ 新文件下限 80） |

## 9. 未验证项

1. **真实模型评测**：本环境无模型端点与出站许可（`basic-framework.ai.http.allowedHosts` 为空、默认拒绝），
   "模型在含糊问题上是否追问""指标/时间窗选择正确率"等模型效果指标未验证；由 Q04 评测套件与 Q10 端到端验收承接。
2. **真实上游 HTTP 端到端**：API 入口的上游由测试夹具扮演（同 D02/D07 的环境限制），
   未对真实第三方接口验证。
3. **真实浏览器验收**：本卡不涉及页面；分页截断/错误在界面上的呈现由 Q06/G5 用真实浏览器验收。
4. **跨系统黄金集**：按卡片要求只覆盖单系统（同一合成系统的两个入口）。
5. **AT-040/AT-041 的现场重放**：本卡未重复运行 D02 的网络夹具用例，只引用其证据。
6. **既有集成用例的套件级隔离缺陷**：现象、范围与数据来源见第 7 节偏差说明；本卡未修复（不在允许路径内）。

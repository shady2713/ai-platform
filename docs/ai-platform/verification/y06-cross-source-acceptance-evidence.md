# Y06 验证证据：V2 跨系统链收口（验收 + 单系统无回退 + 部署升级手册）

| 项目 | 内容 |
|---|---|
| 任务卡 | [Y06 V2 跨系统链收口：验收、兼容与升级手册](../tasks/Y06.md) |
| 本文件回答什么 | Y06 实际跑了哪些命令、退出码与测试计数；每条数字都可回到下面的命令输出；哪些项**未跑**及原因 |
| 本卡性质 | **收口卡，不写生产代码**。§2 允许路径只有 `docs/acceptance`、`docs/upgrades`、两个 `compatibility` 测试目录。本卡**未改动任何 service / controller / 前端页面** |
| 交付物 | [V2 验收报告](../../acceptance/y06-v2-cross-source-acceptance-report.md)、[跨源部署与升级手册](../../upgrades/cross-source-deployment-and-upgrade-runbook.md)、本文件、两个 compatibility 目录下的反向测试 |
| 执行环境 | `/home/ctyun/桌面/zhongtai/ai-platform`，分支 `main`，HEAD = `ef1a9cd`（Y05）；JDK 17.0.20（`$HOME/.local/opt/jdk17/usr/lib/jvm/java-17-openjdk-amd64`）；Node 携 Playwright 1.63.0 + Chromium 1243 |
| 纪律 | 未跑 `.harness/verify.sh` 门禁、未跑任何 `check-*.mjs`、未 `git commit/push/clean/checkout/reset`（门禁与棘轮由主会话分两段串行跑） |

## 1. 回归面：V2 链究竟改了什么既有文件

Y06 的核心价值在"无回退"，所以先确定**回归面**，而不是先写用例。
逐提交核对 `git show --name-status`（Y02=`2f4176a`、Y03=`1e5ff10`、Y04=`509919a`、Y05=`ef1a9cd`），
四个提交对**既有生产文件**的改动只有这些：

| 提交 | 改动的既有生产文件 | 性质 |
|---|---|---|
| Y02 | `AiErrorCodeConstants.java`、`AiErrorCodeRanges.java` | 仅追加新编号与新区间 |
| Y03 | 同上两个 | 同上 |
| Y04 | 同上两个 + `scripts/secret-scan.mjs` | 同上 |
| Y05 | 同上两个 + **`ResultTable.vue`** + **`blocks.ts`** | **改了单系统共享渲染层** |

结论：V2 链对单系统既有行为的全部潜在影响面 = ① 错误编号表（能否顶替旧编号）、
② 单系统共享渲染层（Y05 改的这两个前端文件）。跨源执行/口径/映射/授权四环的其余代码
全部是**新增包**，下面用实测证明它们与单系统链路在编译期就已隔离。

结构性隔离的可执行核验（只读 grep，不改代码）。**注意**：不能只 grep 包名——
`domain/semantic` 包里同时存在**既有的** D05 类（`AiDatasetDefinition`）与 Y03 新增类
（`AiMetricSemantics` 等），`service/semantic` 同样是既有服务。只按包名排除会得出
"单系统链路也命中"的错误结论（本卡最初就是这么查错的，已订正）。
正确的核验是按**新增类名**查单系统链路是否 import：

```
$ cd 后端代码/basic-framework-boot/basic-framework-module-ai/src/main/java/com/basicframework/module/ai
$ for d in domain/query service/query/api service/query/compiler service/query/planner service/run adapter; do
    grep -rn "import.*\(AiMetricSemantics\|AiCrossSource\|service\.query\.crosssource\|service\.authorization\.crosssource\|service\.queryplan\)" --include=*.java "$d"
  done
```

六个单系统目录全部**零命中**（无输出）。即：单系统查询链路在**编译期**就没有任何
V2 新类的引用，"要求补映射版本/口径版本"这类跨源拦截在单系统路径上无处可挂。
这条是"重放旧单系统请求不会被额外拦截"（§3.2 第 4 行）最硬的证据——
它不依赖运行期样本，而是依赖依赖图本身。

## 2. 本卡新增的测试（反向回归）

### 2.1 后端：`module-ai` compatibility

`.../module-ai/src/test/java/com/basicframework/module/ai/compatibility/SingleSystemNoRegressionTest.java`（376 行，12 条）

| # | 用例 | 断言的反向命题 |
|---|---|---|
| 1 | `singleSystemGoldenNumbersStayIdenticalUnderTheV2Chain` | 黄金指标逐位一致（用 `equals`，不用 `compareTo`：标度不同就不是同一表示） |
| 2 | `crossSourceCaliberKeepsPaymentSeparateFromNetAmount` | 回款不被并入净额（AT-034 多对多防重复） |
| 3 | `singleSystemAuthorizationDenialKeepsItsOriginalErrorCode` | 越权仍是 `1_003_006_032`，且**不落进** V2 新领的 4 个子区间 |
| 4 | `legacySingleSystemPlanIsNotInterceptedByCrossSourceRequirements` | 旧计划结构未变（计划哈希逐位可比） |
| 5 | `replayingALegacySingleSystemRequestYieldsTheSameResult` | **重放**逐字段相同，且仍宣称完整统计 |
| 6 | `singleSystemCompletenessSemanticsSurviveTheV2Caliber` | 取完=COMPLETE / 截断=PARTIAL / 上游失败=FAILED 三向齐全，停止原因原样保留 |
| 7 | `emptySingleSystemResultIsStillCompleteAndNotFlaggedAsAGap` | 空结果 ≠ 跨源 missingRoles |
| 8 | `singleSystemDecimalScaleIsUnchangedByTheCrossSourceCaliber` | 0.10+0.20 仍是 0.30（不经浮点/不取整） |
| 9 | `singleSystemFormatDriftKeepsItsOriginalErrorCode` | 格式漂移仍是原编号，不是跨源编号 |
| 10 | `crossSourceValidationStillRejectsAnUndeclaredSource` | 对照组：跨源链路自身判定仍有效（证明本卡测的是隔离，不是"跨源坏了"） |
| 11 | `crossSourceDenialCodesRemainDistinctFromSingleSystemCodes` | 单系统与跨源拒绝编号两两不同 |
| 12 | `singleSystemQueryPathRemainsFreeOfCrossSourceRequirements` | 单数据集校验器不认识映射/口径版本，拦截"无处可挂" |

### 2.2 前端：两个 compatibility 目录

| 文件 | 行数 | 用例数 | 断言的反向命题 |
|---|---|---|---|
| `tests/compatibility/at-070-single-system-result-block-no-regression.test.ts` | 167 | 7 | 旧载荷**不带** `crossSourceIntegrity` 时不凭空多字段；金额文本逐位；分页条数仍在；`completeness` 语义未被跨源图注顶掉；空结果不被改写成授权拒绝；**对照组** WITHHELD 确实清空 |
| `tests/compatibility/at-070-single-system-table-browser-render.pw.ts` | 120 | 2 | 真实 Chromium 里旧表格块照旧出数字/行数/条数，且无 pageerror；对照组 WITHHELD 确实一个数字都不出 |
| `tests/compatibility/fixtures/chart-probe/entry.ts`（**既有文件，追加式**） | 114 | — | 只新增 `mountResultTable(payload)` 桥接方法与一个挂载点，未改动 AT-065 原有挂载逻辑 |

前端两个用例都走**生产公开入口** `parseMessageBlock`，不调用内部私有 `parseTableBlock`，
也不在测试里复制渲染逻辑。

## 3. 实际执行的命令与退出码

> 以下均为**本卡实际执行**的命令（不含门禁）。每行的退出码均为命令真实退出状态。

| # | 命令 | 退出码 | 输出计数 |
|---|---|---|---|
| C1 | `./mvnw -o -q -pl basic-framework-module-ai spotless:apply` | **0** | — |
| C2 | `./mvnw -o -pl basic-framework-module-ai test -Dtest='com.basicframework.module.ai.compatibility.*Test' -DfailIfNoTests=false`（**改动前基线**） | **0** | `Tests run: 38, Failures: 0, Errors: 0, Skipped: 0` |
| C3 | `./mvnw -o -pl basic-framework-module-ai test -Dtest='SingleSystemNoRegressionTest' -DfailIfNoTests=false`（首轮） | **1** | `Tests run: 12, Failures: 2, Errors: 2` — 见 §4 缺陷 1 |
| C4 | 同上（修正夹具后） | **0** | `Tests run: 12, Failures: 0, Errors: 0, Skipped: 0` |
| C5 | `./mvnw -o -pl basic-framework-module-ai test -Dtest='com.basicframework.module.ai.compatibility.*Test' -DfailIfNoTests=false`（**收口全量**） | **0** | `Tests run: 50, Failures: 0, Errors: 0, Skipped: 0`（38 既有 + 12 新增） |
| C6 | `npx vitest run tests/compatibility/at-070-single-system-result-block-no-regression.test.ts`（首轮） | **1** | `Tests 7 failed (7)` — `parseTableBlock is not a function`，见 §4 缺陷 2 |
| C7 | 同上（改用 `parseMessageBlock` 后） | **0** | `Test Files 1 passed (1)` / `Tests 7 passed (7)` |
| C8 | `npx vitest run tests/compatibility/`（**收口全量**） | **0** | `Test Files 4 passed (4)` / `Tests 26 passed (26)`（19 既有 + 7 新增） |
| C9 | `npx playwright test --config tests/compatibility/playwright.config.ts` | **0** | `3 passed (14.2s)`：AT-065 既有 1 条 + 本卡新增 2 条 |
| C10 | `./mvnw -o -pl basic-framework-server test -Dtest='AiGoldenSetAcceptanceIT' -DfailIfNoTests=false` | **0** | `Tests run: 9, Failures: 0, Errors: 0, Skipped: 0`（**73.23 s**，真实 MySQL Testcontainer） |
| C11 | `./mvnw -o -pl basic-framework-server test -Dtest='AiMasterMappingAcceptanceIT,AiCrossSourceMetricAcceptanceIT,AiCrossSourceExecutionAcceptanceIT,AiCrossSourceAuthorizationAcceptanceIT' -DfailIfNoTests=false` | **0** | `Tests run: 41, Failures: 0, Errors: 0, Skipped: 0` |

C11 逐类计数：`AiCrossSourceAuthorizationAcceptanceIT` 12（68.48 s）、
`AiCrossSourceExecutionAcceptanceIT` 11（20.52 s）、`AiMasterMappingAcceptanceIT` 6（15.18 s）、
`AiCrossSourceMetricAcceptanceIT` 12（0.963 s）。

C9 的浏览器栈为**真实 Chromium**（`~/.cache/ms-playwright/chromium-1243`，Playwright 1.63.0），
`globalSetup` 现场构建探针产物（`probe.js` 3082213 字节）。**没有**使用 `--ignore-snapshots`，
**没有**删断言，**没有** `test.skip`。浏览器项是**真跑通过**的，不是登记为未验证。

### 3.1 专项一：黄金指标对比基线（专项证据）

| 证据 | 来源 | 数字 |
|---|---|---|
| D11 黄金集基线 | `d11-golden-set-evidence.md` + 夹具 `AiGoldenSetFixture.EXPECTED_*` | 740.00 / 450.00 / 290.00 / 190.00 |
| V2 上线后**重跑**同一黄金集 | C10，`AiGoldenSetAcceptanceIT` 9 条全过（真实 MySQL，非 H2） | 同上，逐位一致 |
| 跨源链与单系统链逐位一致 | C4 用例 1，`BigDecimal.equals`（含标度） | C001 290.00、C002 450.00、合计 740.00 |

### 3.2 专项二：单系统无回退（反向证据）

| 断言 | 载体 | 退出码 |
|---|---|---|
| 单系统查询结果与 V2 之前逐字段相同 | C5 用例 1/4/5/7/8；C8 全部 7 条 | 0 |
| 单系统授权拒绝仍是原错误码 | C5 用例 3/9/11 | 0 |
| `truncated` / 缺口 / 时间点语义未被改写 | C5 用例 6/7/8；C8；C9 | 0 |
| 重放旧单系统请求不被额外拦截 | C5 用例 5 | 0 |

## 4. 过程中发现并修正的缺陷（均为**测试自身**的夹具错误，不是生产缺陷）

| # | 现象 | 根因 | 修法 |
|---|---|---|---|
| 1 | C3 首轮 2 failures + 2 errors | ① 越权用例选了**字段白名单**路径，该路径在 `AiQueryPlanValidator` 里先命中 `AI_QUERY_PLAN_INVALID`（1_003_006_030）而非 `AI_QUERY_DATASET_NOT_ALLOWED`；② 跨源口径夹具把 `timeWindow` 写成对象（契约要求字符串枚举）且 `datasetCode` 含连字符（`NAME_PATTERN` 只允许小写字母/数字/下划线） | 改用**数据集级**越权路径（计划引用授权外数据集，实测确定性返回 1_003_006_032）；口径夹具改为 `timeWindow: "CALENDAR_MONTH"` + `golden_orders` |
| 2 | C6 首轮 7 条全挂 | `parseTableBlock` 是 `blocks.ts` 内部私有函数，测试 import 不到 | 改用生产公开入口 `parseMessageBlock`，并加 `parseTable()` 辅助函数断言 `kind === 'table'`（避免用 `as` 掩盖解析器返回了别的东西） |

**这两处都不是生产代码缺陷**：C3 的首轮"失败"恰恰证明了单系统链路的错误编号分流是**确定**的
（字段越权与数据集越权走不同编号），修正后 12 条全绿。

## 5. 未验证项（如实登记）

| 项 | 状态 | 原因 / 说明 |
|---|---|---|
| 真实跨源**端到端**（两套真实业务系统 + 真实只读账号的完整跨源查询） | **未验证** | 跨源链的既有 IT 跑在**单个 Testcontainer MySQL 内的三张跨源表**（`AiCrossSourceExecutionAcceptanceIT`）上，不是两套物理隔离的真实系统。本卡无外部系统可连 |
| 生产环境只读连接器账号的权限配置 | **未验证** | 手册 §3 给的是**可复跑步骤**（照 `boot-smoke/mysql-init.sql` 的 `GRANT SELECT` 模式），本卡未在任何真实环境执行过 |
| 真实 `N-1` SDK 产物联调（AT-057 前端侧） | **未验证** | 与既有结论一致：首发无历史版本 SDK 产物，等价物是"冻结候选协议重放"，已由 `at-057-baseline-fixture-compat-not-real-n1.test.ts` 6 条覆盖 |
| 视觉回归（像素级） | **未做，且刻意不做** | 沿用 ADR 0046 决策 2 / Q06 约定：只做结构断言。浏览器项验的是"元素出没、文本逐位、行数"，不是像素比对 |
| 门禁与覆盖率棘轮 | **未跑** | 铁律 1/2：`.harness/verify.sh` 与 `check-*.mjs` 由主会话分两段串行执行 |
| 覆盖率棘轮按 sourcefile 复核 | **不需要** | 本卡未触碰任何 `module-ai` 主源码，只新增测试；主源码覆盖率不受影响 |
| V96 迁移 | **不存在** | 实测 `db/migration` 最高版本为 `V95__ai_cross_source_execution.sql`，Y05 未领迁移号。派发说明里的"V92→V96"与仓库实况不符，手册按实况写 V92→V95 并把 V96 登记为"下一个可用号" |

## 6. 已知缺口（不属于 Y06 可修范围，但必须随链上线前处置）

**后端至今零产出 `crossSourceIntegrity` 字段，前端在字段缺失时 fail-open。**

- 事实：Y05 的完整性 UI（`ResultTable.vue` 第 74–78 行的 `ai-message-table-cross-source` 图注）
  完全依赖后端在表格块里给出 `crossSourceIntegrity`；而后端 `CrossSourceExecutionResult`
  **没有产出该字段**。`blocks.ts` 第 281 行 `if (input.crossSourceIntegrity !== undefined)`
  意味着字段缺失时不解析、图注不出现 → 界面照常显示数字。
- 后果：`WITHHELD` 这条**保护性**分支在真实链路上目前**走不到**。
  授权拒绝目前由**后端**在跨源授权环（Y05 `AiCrossSourceAuthorizationJudge`）直接拒绝并返回
  错误码，前端拿不到结果块，所以**不泄漏**仍然成立；但"已放行时向用户声明授权范围完整"这一正面
  承诺是假的（界面上什么都不说，等同于"看起来完整"）。
- 为何不在 Y06 修：修它必须动生产代码（后端结果组装 + 契约），
  而卡片 §2 允许路径**不含**任何生产代码目录。这是**范围问题，不是难度问题**。
- 补齐所需的**范围决策**（需主会话/负责人拍板）：
  1. 后端由谁产出 `crossSourceIntegrity`（建议在 `CrossSourceExecutionResult` → 表格块组装处，
     依据 Y05 已算出的授权判定）；
  2. 是否要改 `docs/contracts/ai` 的表格块契约（新增可选字段）；
  3. 是否要把前端从 fail-open 改成 fail-closed——**注意这会改变单系统行为**，
     与本卡"无回退"结论直接冲突，必须单独走一次兼容性评估。
- 在补齐前的上线建议：**按"UI 不显示授权完整性提示"上文档与培训口径**，
  不要对外宣称"界面会提示授权范围"。

## 7. 关联

- 验收报告：[../../acceptance/y06-v2-cross-source-acceptance-report.md](../../acceptance/y06-v2-cross-source-acceptance-report.md)
- 部署与升级手册：[../../upgrades/cross-source-deployment-and-upgrade-runbook.md](../../upgrades/cross-source-deployment-and-upgrade-runbook.md)
- 依赖证据：[y02-master-object-mapping-evidence.md](y02-master-object-mapping-evidence.md)、
  [y03-cross-source-metric-semantics-evidence.md](y03-cross-source-metric-semantics-evidence.md)、
  [y04-bounded-cross-source-execution-evidence.md](y04-bounded-cross-source-execution-evidence.md)、
  [y05-cross-source-authorization-evidence.md](y05-cross-source-authorization-evidence.md)、
  [d11-golden-set-evidence.md](d11-golden-set-evidence.md)

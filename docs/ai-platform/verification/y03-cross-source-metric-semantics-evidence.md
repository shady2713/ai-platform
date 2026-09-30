# Y03 跨源指标口径与关联粒度校验 — 完成证据

| 项目 | 内容 |
|---|---|
| 任务卡 | [Y03 定义跨系统指标口径与关联粒度校验](../tasks/Y03.md) |
| 需求 | FR-22、FR-23、FR-38（V2 跨系统链第三环） |
| 依赖 | Y02（`2f4176a`，证据 `y02-master-object-mapping-evidence.md`）、D11（黄金集 `d11-golden-set-evidence.md`） |
| 验收 | AT-034 三条专项 + AT-070 跨系统口径一致性 |
| 迁移 | V94（`V94__ai_metric_semantics.sql`，2 表 + 1 物理外键） |
| 错误码 | `1_003_016_000` – `1_003_016_019`（新领子区间） |

## 1. 交付内容

### 1.1 数据模型（V94）

| 表 | 生命周期 | 作用 |
|---|---|---|
| `ai_metric_semantics` | soft-delete | 跨源指标口径锚点，`metric_code` 全局唯一且**不可修改**；`current_revision` 指向当前已发布版本（0=尚无） |
| `ai_metric_semantics_revision` | soft-delete | 口径版本头：`(口径, 版本号)` 唯一；草稿可编辑，发布后 `definition_json` 与 `definition_fingerprint` 冻结；发布人 ≠ 草稿创建人 |

来源声明（订单/发票/回款各自的币种、单位、时区、主键粒度、是否可选）整体冻结在版本的
`definition_json` 里，**不单独建内容行表**。理由：本卡没有"草稿期逐条增删来源"的交互需求，
而"版本即不可变快照"正是 D04 `ai_dataset_version.definition_json + schema_hash` 已验证过的形态；
少一张表也就少一处可能漂移的副本。

### 1.2 领域模型（`domain/semantic`）

| 类 | 行数 | 职责 |
|---|---|---|
| `AiMetricSemantics` | 337 | 口径定义的唯一解析入口：六项口径（币种/单位/时区/时间窗口/主键粒度/聚合顺序）+ 规范化 JSON + 内容指纹 |
| `AiMetricSemanticsFacts` | 189 | **判定算法的单一真源**：币种可加性、口径一致性、扇出安全、缺口容忍、内容指纹 |
| `AiMetricSemanticsJson` | 210 | 解析原语（形状/类型/枚举），与语义判定分离 |
| `AiMetricSemanticsErrors` | 45 | 稳定错误出口，保证三条路径给出同一错误码 |

把判定规则集中在纯函数类是沿用 Y02 `AiMasterMappingFacts` 的动机：登记、发布、聚合三条路径共用，
任何一条走偏都会造成"同一份口径在不同路径下结论不同"。

### 1.3 服务与计划校验（`service/queryplan` 新包 + `service/semantic`）

| 文件 | 行数 | 职责 |
|---|---|---|
| `AiCrossSourceQueryPlanValidator` | 196 | 跨源计划校验：选择完整性 → 口径一致 → 币种可加 → 扇出安全 → 聚合顺序 → 缺口策略 |
| `AiCrossSourcePlanRequest` | 250 | 计划结构化选择解析（**不接受任何 SQL 片段**，只有"选哪些来源、什么粒度"） |
| `CrossSourceQueryPlan` | 55 | 已校验计划（携带口径版本 + 口径指纹 + 计划哈希） |
| `AiMetricSemanticsServiceImpl` | 287 | 口径登记、草稿、发布（独立审核 + 指纹冻结 + 两级 CAS）、显式版本核验 |

**与 D05 单数据集校验器的关系**：`AiQueryPlanValidator`（`domain/query`）是**单数据集**语义，
其调用点在 `service/query/planner` 与 `service/run`——**两者都不在本卡允许路径内**，因此本卡
**没有改动**该校验器与它的任何调用点。Y03 以新增的 `service/queryplan` 包提供**多来源**校验：
两者的安全前提不同（后者必须钉住口径版本、逐来源显式选择、拒绝扇出），合并成一个类必然出现
"单数据集路径悄悄放过多对多关联"的分支。

## 2. 与卡片逐步实施的对应

| 卡片要求 | 实现 | 证据 |
|---|---|---|
| 1. 定义跨源指标的币种/单位/时区/时间窗口/主键粒度/聚合顺序 | `AiMetricSemantics` 六项口径 + `definitionHash()` 冻结 | §4 IT 1/3；单测 `parsesSixCaliberFacetsAndKeepsSourceOrder` |
| 2. 查询计划显式选择数据集及映射版本，拒绝不安全扇出关联 | `AiCrossSourcePlanRequest` 形状层强制显式；`requireFanoutSafe` 比对**口径登记的**粒度 | §4 IT 1/6；单测 `everySourceMustPinBothDatasetVersionAndMappingVersion` |
| 3. 为订单/发票/回款分别聚合再关联，缺口有澄清与完整性策略 | 口径三来源（order/invoice/payment）+ `aggregationOrder` + `optional` 缺口策略 | §4 IT 1/5 |

## 3. 关键安全语义与不变量

1. **扇出即重复计算，靠粒度阻断而不是靠提示词**。每个来源必须在口径里声明自己的主键粒度，
   计划必须显式声明"已按该粒度预聚合"，否则 `AI_METRIC_FANOUT_UNSAFE_CONFLICT`。
   IT 1 用数字证明动机：一笔 100.00 的订单经 2 张发票 × 3 笔回款展开成 6 行，直接关联求和得
   600.00；按各自主键粒度预聚合后仍是 100.00。
2. **计划自称的粒度不算数**。粒度比对取的是**口径登记的**粒度，不是计划里写的那个——
   早期实现用"从计划重建来源"做比对，等于自己等于自己，这道检查形同虚设（见 §6.1）。
3. **币种不同即禁止相加**。`requireSummable` 在**聚合之前**阻断，因此不存在"先加了再发现单位不对"
   的中间态。100 USD + 100 CNY = 200 是最危险的"看起来对"的结果。
4. **口径冲突显式拒绝，绝不让模型推断**。单位/时区与口径声明不一致即
   `AI_METRIC_CALIBER_CONFLICT`；**没有**"挑一个来源的时区"或"按主来源对齐"的兜底分支。
5. **缺失不按默认值补全**。来源缺币种/时区/粒度一律 `AI_METRIC_CALIBER_MISSING_CONFLICT`。
   若允许缺省成口径值，"来源其实没登记币种"就会看起来像"币种一致"。
6. **缺口不等于 0**。只有口径里显式 `optional=true` 的来源才允许按缺省继续，其余必须
   `AI_METRIC_GAP_CLARIFICATION_REQUIRED`——"没有回款记录"和"回款金额是 0"在报表上必须能区分。
7. **判定必须显式钉住版本与时刻**。`resolveVerified(metricCode, revisionNo, asOf)` 三个入参都必填，
   没有"取最新版""取服务器当前时间"的省略写法；旧报表按受理时的版本与指纹解释。
8. **版本外改动会被发现**。发布冻结 `definition_fingerprint`，每次核验重算比对，不符即 409。
9. **无操作员身份不能登记口径**。口径决定了"哪些数可以相加"，不能由匿名调用写入。
10. **发布是单一真源，不在两处重复判定**。发布只做"解析 + 冻结指纹 + 独立审核"；币种/时区这类
    **业务事实**由聚合校验拒绝。若发布时就拦，聚合侧的门永远走不到（见 §6.2）。

## 4. 验收用例对照（`AiCrossSourceMetricAcceptanceIT`，真实 MySQL Testcontainers）

| # | 测试方法 | 卡片验收条款 | 断言的错误码常量 |
|---|---|---|---|
| 1 | `manyToManyJoinIsRefusedAndPreAggregationIsWhatMakesTheSameTotalCorrect` | **多对多不重复计算** | `AI_METRIC_FANOUT_UNSAFE_CONFLICT` |
| 2 | `differentCurrenciesWithoutConversionRuleCannotBeSummed` | **不同币种无换算规则不能求和** | `AI_METRIC_CURRENCY_CONVERSION_MISSING_CONFLICT` |
| 3 | `caliberConflictIsRefusedInsteadOfBeingGuessed` | **口径冲突不能靠模型猜测** | `AI_METRIC_CALIBER_CONFLICT` |
| 4 | `aDraftIsNotAVerifiableFactUntilAnIndependentReviewerPublishesIt` | 草稿不是可核验事实 | `AI_METRIC_SEMANTICS_REVISION_NOT_PUBLISHED_CONFLICT` + 币种拒绝 |
| 5 | `gapsInRequiredSourcesRequireClarificationInsteadOfSilentZero` | 缺口澄清与完整性策略 | `AI_METRIC_GAP_CLARIFICATION_REQUIRED` |
| 6 | `planMustPinDatasetVersionAndMappingVersionForEverySource` | 显式选择数据集与映射版本 | `AI_METRIC_PLAN_SELECTION_REQUIRED` / `AI_METRIC_PLAN_SOURCE_NOT_DECLARED` |
| 7 | `publishingANewCaliberRevisionNeverChangesHowOldResultsAreInterpreted` | 换版本不改旧结果 | v1 指纹不变、v2 独立 |
| 8 | `draftExpiredDisabledAndTamperedRevisionsAreAllBlocked` | 过期/停用/指纹被改动阻断 | `..._REVISION_EXPIRED_CONFLICT` / `..._NOT_EXISTS` / `..._FINGERPRINT_CONFLICT` |
| 9 | `registrationRequiresAnOperatorAndAnIndependentReviewer` | 登记需操作员 + 独立审核 | `AI_ACCESS_DENIED` / `..._CODE_DUPLICATE` / `..._PUBLISHER_CONFLICT` |
| 10 | `disablingASemanticsBlocksAggregationInsteadOfFallingBackToHistory` | 停用即阻断 | `AI_METRIC_SEMANTICS_DISABLED_CONFLICT` |
| 11 | `managementQueriesArePagedBoundedAndReturnRealRows` | 管理读路径（分页过滤真实生效） | — |
| 12 | `mappingVersionsAreReadThroughTheVersionHeaderNotTheLatestOne` | 版本定位 + Mapper 守卫 | `..._REVISION_NOT_EXISTS` |

## 5. 验证结果

见 §7（命令、退出码、测试计数）。

## 6. 门禁暴露并已修复的缺陷（真实失败证据）

以下都是**本卡实现过程中被自己的测试真实打红**后修复的，不是预防性改动。

### 6.1 扇出检查形同虚设（真实缺陷，测试抓到）

`AiCrossSourceQueryPlanValidator` 初版用 `request.sourceByRole(role)` 取来源再比对粒度，而该方法
**按计划自身重建** `Source`（含 `selection.primaryKey()`）：

```java
// 修复前：计划自称的粒度被拿来和"由计划重建的粒度"比 —— 恒真
AiMetricSemantics.Source source = request.sourceByRole(selection.role());
if (!selection.primaryKey().equals(source.primaryKey())) { throw ...FANOUT...; }
```

`planClaimingAGrainTheCaliberNeverDeclaredIsRefused` 用例把计划粒度改成口径从未登记的
`customer_id`，断言应抛 `AI_METRIC_FANOUT_UNSAFE_CONFLICT`，实际**没有抛**：

```
Expecting code to raise a throwable.
	at ...AiCrossSourceQueryPlanValidatorTest.planClaimingAGrainTheCaliberNeverDeclaredIsRefused
```

**修法**：`requireFanoutSafe` 改为从**口径已解析的来源声明**（`resolveDeclaredSources` 的结果）按角色
定位，比对对象换成 `declaredSource`；同时删除误导性的 `sourceByRole`，`AiCrossSourcePlanRequest` 改为
只暴露 `selectionByRole`（返回计划侧选择，不还原成 `Source`），并在注释里写明为什么不能还原。
修复后该用例通过，且"伪造粒度"被真正拦下。

### 6.2 发布期口径自检让聚合侧的门永远走不到（设计缺陷，IT 抓到）

`publishRevision` 初版在发布时调用 `requireConsistentCaliber` + `requireSummable`。但口径定义
**本身就是** `unit/timezone/currency` 的声明来源，于是"来源与口径不一致"在发布时必然成立 →
**任何有冲突的口径都无法发布**，聚合侧的冲突判定（AT-034 第 2、3 条的真实场景）变成死代码。

首轮真实 MySQL IT 的失败证据：

```
[ERROR] Errors:
[ERROR]   AiCrossSourceMetricAcceptanceIT.caliberConflictIsRefusedInsteadOfBeingGuessed:204->registerAndPublish:103
          » Service 来源口径与跨源指标口径声明冲突，必须显式解决
[ERROR]   AiCrossSourceMetricAcceptanceIT.differentCurrenciesWithoutConversionRuleCannotBeSummed:159->registerAndPublish:103
          » Service 来源币种不一致且未声明换算规则，禁止跨币种求和
```

**修法**：发布只负责"把声明冻结成可核验事实"（解析 + 冻结指纹 + 独立审核 + 两级 CAS），
**移除**发布期的口径一致性/币种检查，并在代码注释里写明理由：口径是"声明意图"，能不能真的相加
由聚合校验按版本判定——单一真源，不在两处重复判定。相关单测
`mixedCurrencyCaliberMayBeStagedButIsBlockedAtPublishTime` 同步改名为
`mixedCurrencyCaliberIsPublishedButRejectedAtAggregationTime` 并反转断言（口径可发布，但聚合被拒）。

### 6.3 链式 setter 就地改写夹具导致断言对象被污染（测试缺陷）

`publishRequiresAnIndependentReviewerAndAdvancesTheCurrentRevision` 里为了表达"发布后的行"，
写成 `draft.setStatus(PUBLISHED)...`——`@Accessors(chain = true)` 会**就地改写同一个 `draft`**，
于是 Mockito 的第一个 `thenReturn(draft)` 也返回了已发布态，草稿检查随即失败：

```
[ERROR]   publishRequiresAnIndependentReviewerAndAdvancesTheCurrentRevision:218 » Service 当前状态不允许该操作
	at ...AiMetricSemanticsServiceImpl.publishRevision(AiMetricSemanticsServiceImpl.java:144)
```

**修法**：用 `copyWithPublished(draft)` 造一个独立对象表达发布后状态（与 brief §3 提醒的
"Lombok 链式 setter 遇到共享引用会串味"同源）。

### 6.4 多行文本块夹具被格式化工具重排，负向夹具静默失效（测试缺陷）

口径夹具最初写成多行文本块，负向用例靠 `String.replace` 改内容。`spotless:apply` 重排了文本块缩进，
`replace` 的目标串（跨行、含特定换行）不再匹配 → 替换变成空操作，用例以"没抛异常"失败。
这与 D11 证据 §6.2 记录的"危险计划样例空替换"是同一类陷阱。

**修法**：夹具改为**单行 JSON 拼装**（`AiMetricSemanticsFixtures.caliber(...)`），并在类注释里
写明原因；负向用例改用参数化构造，不再依赖 `replace` 的精确匹配。

### 6.5 `ServiceException.getCode()` 返回 int 而非 ErrorCode（测试断言缺陷）

`.isEqualTo(AI_METRIC_SOURCE_INVALID)` 报 `expected: ErrorCode(code=1003016008, ...) but was: 1003016008`。
**修法**：统一断言 `常量.getCode()`，仍然是**常量**而不是裸数字（符合验收要求）。

### 6.6 spotless 绑在 compile 阶段，格式不合规在编译前就红

新增文件未格式化时 `./mvnw test` 直接停在 `spotless-check`，代码根本没进编译：
`The following files had format violations: ... AiMetricSemanticsTest.java`。
**处置**：每次改完 Java 立即 `./mvnw -q -o -pl basic-framework-module-ai spotless:apply`
（server 侧 `-pl basic-framework-server`）。

### 6.7 错误码登记册被 Y03 顶破 800 行上限（真实门禁失败，已修）

追加 20 个 `1_003_016_xxx` 常量后，`AiErrorCodeConstants.java` 变成 **886 行**，`check-source-quality`
的 800 行硬上限会直接红：

```
size failures: [".../enums/AiErrorCodeConstants.java: 886 行，超过上限 800 行"]
```

Y02 交付时该文件**正好 800 行**（已在边界上），因此本卡不能继续往里堆。

**修法**：按能力域拆出 `enums/AiMetricSemanticsErrorCodeConstants.java`（111 行，承载
`1_003_016_xxx` 全部 20 个常量），`AiErrorCodeConstants extends` 它。继承而非改引用，是为了让
`AiErrorCodeConstants.AI_METRIC_*` 的引用面完全不变（零引用改动，也避免抄错名字）。
全库唯一性测试与 HTTP 派生都按 `classpath*:com/basicframework/**/enums/*ErrorCodeConstants.class`
的 `getDeclaredFields()` 扫描：子接口被**独立**扫描，继承来的字段不属于 `AiErrorCodeConstants`
的 declared fields，因此每个编号只计一次。验证见 §7.4。

拆分后主文件 **799 行**（留 1 行余量），并在类 javadoc 里写明"新区间请另起登记册"，
避免下一张卡再次顶破。

### 6.9 期望表清单字母序插错，连带把覆盖率报告带崩（真实门禁失败，主管抓到）

首轮全量 integration 报 1 个失败：

```
[ERROR] Tests run: 1, Failures: 1 -- in com.basicframework.server.integration.PersistenceLifecycleIT
Expecting actual:
  [..., "ai_master_object_revision", "ai_media_asset", "ai_media_task", "ai_metric_semantics", ...]
to contain exactly (and in same order):
  [..., "ai_master_object_revision", "ai_metric_semantics", "ai_metric_semantics_revision", "ai_media_asset", "ai_media_task", ...]
```

**集合完全一致，纯粹是顺序**：`metric` 字母序排在 `media` **之后**（m-e-d < m-e-t），
本卡把两张新表插在了 `ai_master_object_revision` 之后、错误地排在 `ai_media_*` 之前。
该断言对顺序敏感。修法：移到 `ai_media_task` 之后，形成
`master < media < metric < model`。

**连带效应（一个缺陷、两个症状）**：该失败让 reactor 在到达 `basic-framework-coverage` 模块
**之前**就中止，于是聚合报告停留在 backend 门禁留下的 14:19 版本（只有单测、没有真实 MySQL IT），
棘轮随即报出几十个文件 0%——所有 Mapper 的手写 default 方法都没有 IT 覆盖。
证据：聚合报告时间戳 14:19 = integration 的**起始**时刻；同轮 `Tests run: 363` 也印证构建提前退出。
修掉字母序后报告重建为 15:14，那批 0% 全部消失，**并非第二个缺陷**。

### 6.10 `AiMetricSemanticsJson` 覆盖率 77.78% 低于 80%（真实门禁失败，主管补测）

棘轮在重建报告后给出唯一剩余问题：

```
- .../domain/semantic/AiMetricSemanticsJson.java: 新文件覆盖率 77.78% 低于 80%
```

从聚合报告逐行定位，未覆盖的 22 行几乎全是**输入形状非法的 `throw` 分支**
（`readObject` 的 null/非对象/JSON 字面量 null、`objectList` 的非列表/空/超限/元素非对象、
`nameList` 的非列表/重复名/名字不合式、`enumValue` 的 null、`timezone` 的 null 与非法值、
`rule` 的非法规则、`positiveInt`/`positiveLong` 的非数值与非正数），外加
`boolValue` 的 null 与字符串真值化、`text` 对 Number/Boolean 的强转与对其它类型的拒绝。

补 `AiMetricSemanticsJsonTest`（10 例）逐条钉住**每个分支的精确错误码**——因为本卡的设计意图
就是"形状错误"与"口径冲突"必须落到不同错误码，运维才能分清是写错了还是写对了但自相矛盾。
例如 `timezone(null)` 是 `AI_METRIC_CALIBER_MISSING_CONFLICT`（口径不完整），
而 `timezone("Asia//Shanghai!")` 是 `AI_METRIC_SOURCE_INVALID`（写错了）；
`rule(...)` 写错用 `AI_METRIC_CONVERSION_RULE_CONFLICT` 而不与一般来源错误混用。

补测过程中**两次写错断言被测试当场打脸**，一并记录：
1. `objectList(List.of(Map.of()), 1, 1)` 我以为会因超限抛异常，实际 size=1、max=1、min=1 完全合法 → 改为真放 2 个来源配 max=1。
2. `enumValue(Boolean.TRUE, Set.of("true"))` 我以为返回 `"true"`，实际该方法按文档**把值归一为大写**再查词表，`"TRUE"` 不在 `{"true"}` 里故抛异常 → 核对源码后改为断言真实语义 `Set.of("TRUE")`。

补测后该文件 **100.00%**，`all` 复验 exit 0。


### 6.8 MyBatis 一级缓存让"版本被改过"在同一事务内读不到（测试设置缺陷）

`draftExpiredDisabledAndTamperedRevisionsAreAllBlocked` 用 `jdbcTemplate` 绕过服务层改掉冻结指纹后，
同一事务内 `resolveVerified` 仍读到旧值，断言不抛异常：

```
[ERROR]   draftExpiredDisabledAndTamperedRevisionsAreAllBlocked:320
          » Expecting code to raise a throwable.
```

原因是 MyBatis 的一级缓存在一个事务内让重复查询命中同一快照——这本身是**正确**的事务语义，
而"版本被另一个事务改过"恰恰要求跨事务可见。

**修法**：该用例标注 `@Transactional(propagation = Propagation.NOT_SUPPORTED)`，不参与测试级事务；
服务方法自带 `@Transactional`，登记/发布各自独立提交，更贴近真实的"另一个操作者改了版本"场景。
同时补上 `assertThat(tamperedRows).isEqualTo(1)`，让"改写是否真的落库"成为可断言的事实，
而不是靠"没抛异常"间接推断。

工作目录：`/home/ctyun/桌面/zhongtai/ai-platform/后端代码/basic-framework-boot`
（`umask 022`，`JAVA_HOME=$HOME/.local/opt/jdk17/usr/lib/jvm/java-17-openjdk-amd64`，
`PATH="/tmp/harness-shim:$HOME/.local/bin:$PATH"`）。

### 7.1 模块单测

```sh
./mvnw -o -pl basic-framework-module-ai test -Dtest='AiMetricSemantics*,AiCrossSource*' -DfailIfNoTests=false
```

见 §7.3 汇总。

### 7.2 验收 IT（真实 MySQL + Redis Testcontainers，未用 H2）

```sh
./mvnw -o -q -pl basic-framework-module-ai -am install -DskipTests -Djacoco.skip=true
./mvnw -o -pl basic-framework-server verify -Pintegration \
  -Dit.test='AiCrossSourceMetricAcceptanceIT' -DfailIfNoTests=false
```

见 §7.3 汇总。V94 迁移在全量 Flyway 链上成功执行。

### 7.3 汇总

| 运行 | 命令 | 退出码 | 测试计数 |
|---|---|---|---|
| 模块单测（Y03 范围） | `./mvnw -o -pl basic-framework-module-ai test -Dtest='AiMetricSemantics*,AiCrossSource*' -DfailIfNoTests=false` | 0 | **46 / 46 通过**（`AiMetricSemanticsTest` 11、`AiMetricSemanticsFactsTest` 9、`AiMetricSemanticsServiceImplTest` 13、`AiCrossSourceQueryPlanValidatorTest` 13） |
| 模块全量回归 | `./mvnw -o -pl basic-framework-module-ai test` | 0 | **1535 / 1535 通过**（确认本卡未破坏任何既有 AI 模块测试） |
| server 单测 | `./mvnw -o -pl basic-framework-server test -Dtest='ErrorCodeUniquenessTest' -DfailIfNoTests=false` | 0 | **1 / 1 通过**（含 §6.7 拆分后的全库编号唯一性） |
| **验收 IT** | `./mvnw -o -pl basic-framework-server verify -Pintegration -Dit.test='AiCrossSourceMetricAcceptanceIT' -DfailIfNoTests=false` | 0 | **验收 IT 12 / 12 通过**（`Time elapsed: 66.83 s`）＋同次运行 server 单测 **49 / 49 通过**（含 `ModuleBoundaryArchitectureTest`、`ModuleBoundaryArchitectureRejectionTest`、`ErrorCodeUniquenessTest`）。真实 MySQL 8.4 + Redis Testcontainers，全量 Flyway 迁移链含 V94 成功执行 |

未跑门禁（按 brief 铁律 1：门禁由主会话统一串行跑）：未执行 `sh .harness/verify.sh`，
未执行 `check-coverage-ratchet.mjs --update`，未执行任何 `check-*.mjs`（铁律 2）。
为自检我**以只读方式直接调用了 checker 的导出函数**（`check-source-quality`、
`check-data-lifecycle`、`check-data-permission`），不写任何报告、不覆盖 `jacoco.exec`：

```sh
# 源文件 800 行上限 + 技术债标记：0 失败
#   files checked: 21 ; size failures: [] ; debt markers: []
# data-lifecycle 与迁移链一致：
#   tables missing from policy: [] ; policy entries not in schema: []
#   fks missing: [] ; fk extra: [] ; soft count: 67
# data-permission（传入真实 platform-managed 集合）：
#   registration errors: 0 ; contract errors: 0
#   my tables exempted: true true
```

第一次跑 `check-data-permission` 时报了 11 条 `应用表未登记数据权限或豁免：QRTZ_*`，
经查是**我的临时脚本**把 `platformTables` 传成了空集合（真实门禁会从 `data-lifecycle.json` 的
`platform-managed` 策略读）。补上真实集合后 0 错误——即那是脚本缺陷，不是本次变更引入的问题。

### 7.4 覆盖率（已补证：主管在全量门禁后回填）

**实现阶段确实拿不到数字**：`check-coverage-ratchet` 读的是全量 integration 产出的
`jacoco.exec` 聚合报告，按 brief 铁律 2 不得用聚焦运行覆盖它。主管在全量 integration 跑完后补了证，
数字如下（门槛 80%，全部通过）：

| 文件 | 行覆盖 |
|---|---|
| `dal/mysql/semantic/AiMetricSemanticsMapper.java` | 100.00% |
| `dal/mysql/semantic/AiMetricSemanticsRevisionMapper.java` | 81.25% |
| `domain/semantic/AiMetricSemantics.java` | 93.23% |
| `domain/semantic/AiMetricSemanticsErrors.java` | 100.00% |
| `domain/semantic/AiMetricSemanticsFacts.java` | 96.55% |
| `domain/semantic/AiMetricSemanticsJson.java` | **100.00%**（补测前 77.78%，见 §6.10） |
| `enums/AiMetricSemanticsErrorCodeConstants.java` | 100.00% |
| `service/queryplan/AiCrossSourcePlanRequest.java` | 85.32% |
| `service/queryplan/AiCrossSourceQueryPlanValidator.java` | 96.15% |
| `service/queryplan/CrossSourceQueryPlan.java` | 100.00% |
| `service/semantic/AiMetricSemanticsServiceImpl.java` | 97.73% |

`node scripts/check-coverage-ratchet.mjs all` → `coverage-ratchet: all 单文件基线通过`（exit 0）。

验收 IT 12 条用例真实覆盖了两个 Mapper 的每一个手写方法（`selectByCode`、`selectPage`、
`updateWithVersion`、`selectByRevisionNo`、`publishWithVersion`），并刻意断言了两个 CAS 的
**命中与未命中两侧**。VO/DTO（`AiMetricSemanticsSaveDTO` / `AiMetricSemanticsRevisionDraftDTO`）
只有 Lombok 生成的访问器、没有任何手写方法，因此不会出现"类里有手写方法导致整文件 0% 拉红"。

### 7.5 门禁结果（主管复核补记）

工作目录 `/home/ctyun/桌面/zhongtai/ai-platform`，`umask 022`，严格串行一次只跑一条。

| 门禁 | exit | 判定 |
|---|---|---|
| `sh .harness/verify.sh contracts` | **0** | 绿（25 组断言全通过） |
| `sh .harness/verify.sh backend` | **0** | 绿 |
| `sh .harness/verify.sh integration` | 1 → 见下 | **测试全绿**；exit=1 仅来自门禁尾部棘轮（新文件尚未登记） |
| `sh .harness/verify.sh frontend` | **0** | 绿（本卡无前端改动） |
| `node scripts/check-coverage-ratchet.mjs --update` | **0** | 11 个新文件全部登记且 ≥80% |
| `node scripts/check-coverage-ratchet.mjs all` | **0** | 绿 |

全量 integration：**87 个 IT 类 / `Tests run: 363, Failures: 0, Errors: 0, Skipped: 1`**。
其中 `AiCrossSourceMetricAcceptanceIT` 12/12 通过（0.769 s）。
1 个 skip 是 `PackagedJarBootSmokeIT` 的 `Assumptions.assumeTrue(...)` 条件跳过，该文件最后一次改动是
Q09，与本卡无关，未跳过、未注释、未排除。


## 8. 台账同步

| 台账 | 变更 |
|---|---|
| `数据库文件/basic_framework.sql` | 第 7 行 "through V93" → **through V94**；软删除表计数 65 → **67**；追加 V94 两表 DDL |
| `docs/contracts/data-lifecycle.json` | `soft-delete` 策略新增 `ai_metric_semantics` / `ai_metric_semantics_revision`；`physicalForeignKeys` 新增 `fk_ai_metric_semantics_revision_semantics`（保持字母序） |
| `docs/contracts/data-permission-exemptions.json` | 新增豁免 `ai-metric-semantics-config`（`function-permission`），表同时出现在 exemption `tables` 与 control `tables`，4 条逐表证据均指向真实生产 Java 源码 |
| `PersistenceLifecycleIT` | 期望软删除表清单新增两张（按表名字母序插在 `ai_media_task` 之后 —— 实现时先插错位置，被门禁打回，见 §6.9） |
| 错误码三处 | `AiErrorCodeConstants`（20 个）、`AiErrorCodeRanges`（`DOMAIN_METRIC_SEMANTICS = 1_003_016`）、`docs/contracts/ai/error-code-map.md`（新增分节 + 20 行表格） |

错误码 HTTP 由**常量名后缀**派生（`GlobalExceptionHandler.resolveHttpStatus`）：
后缀 `_NOT_EXISTS` → 404；名称含 `CONFLICT`/`EXISTS`/`DUPLICATE` → 409；其余 422。
因此命名是承重的，去掉 `_CONFLICT` 会静默把 409 变成 422。

## 9. 未验证项与未交付项

1. **覆盖率（实现阶段未验证 → 主管已补证，此项已关闭）**。`check-coverage-ratchet.mjs --update` 读的是
   全量 integration 产出的 `jacoco.exec` 聚合报告；brief 铁律 2 明令实现者不得运行它，也不得用
   聚焦运行去覆盖 `jacoco.exec`，因此实现阶段"新增文件行覆盖 ≥80%"没有数字可交。
   **主管在全量门禁后补了证**：`AiMetricSemanticsJson` 首轮 77.78% 未过线，补 10 例形状校验单测后
   升至 100.00%；11 个新文件全部登记，最低 81.25%，`all` 复验 exit 0。逐文件数字见 §7.4，
   补测经过见 §6.10。**教训**：`--update` 报"新文件未登记"是预期行为，报"低于 80%"才是真缺口，
   两者必须分开读。
2. **未跑任何门禁**（`sh .harness/verify.sh` 任何 gate）。按 brief 铁律 1 由主管串行执行。
   门禁期间并发跑 Maven 会造成 `class does not exist` 与 vitest 超时的假失败，故一律没跑。
   主管执行结果见 §7.5。
3. **未做端到端 SQL 执行**。本卡交付的是**校验**（"能不能相加""会不会重复计算"），
   `CrossSourceQueryPlan` 是可执行形态的产出物，但**跨源 SQL 的实际生成与执行不在本卡范围**。
   IT 用数字论证了扇放的危害（100.00 经 2×3 展开成 600.00）与预聚合后的正确值（100.00），
   但**没有真正连库跑多表 join 再断言结果集**。AT-034 的"净额 290.00 / 回款 190.00"那两个
   黄金数字来自 D11 的数据集黄金集，本卡以**错误码**作为三条 AT 断言，没有复算黄金集金额。
4. **前端无改动**。`前端代码/basic-framework-admin/apps/web-ele/src/views/ai/semantic` 在
   brief §2 的允许路径内，但卡片 §5「必须产出」是「跨系统语义与计划契约」与「粒度验证器和精确
   预期样例」两项，均为契约/校验/样例，不含页面。我刻意**没有加 Controller、没有加管理端页面**：
   没有后端端点却加页面会产出死 UI。代价是本卡的配置面目前只能通过服务层/IT 使用，
   运维没有可视化入口。若后续需要页面，应在补 Controller 端点时一并加，不能只加前端。
5. **未新增菜单与权限**。因未加 Controller，本卡没有新增 `system_menu` 记录与权限码，
   `permission-catalog.json` 无需变更（Y02 已登记 `ai:semantic:query` / `ai:semantic:manage`，
   Y03 复用同一功能权限，见 `data-permission-exemptions.json` 的 `function-permission` 豁免）。
6. **币种换算规则只登记标识、不做汇率计算**。`conversion` 只存 `rule` 标识与 `targetCurrency`。
   汇率是数据不是语义；本卡不引入汇率表，也不实现换算。判定上：多币种 + 无换算规则 → 拒绝求和
   （已测）；多币种 + 声明了换算规则 → 放行到"换算环节"（**该环节本身不在本卡，也未验证**）。
7. **`fanoutSafe` 的判据是"双方都按主键粒度预聚合"，不含真实基数探测**。它阻断的是
   "未声明预聚合"与"粒度与口径不符"两类确定性不安全；它**不**在执行期统计行数来发现
   隐式的一对多。真实基数探测属于执行期职责，本卡未实现。
8. **V94 迁移只在本卡的 IT 里跑过全量迁移链**（`verify -Pintegration` 会执行全量 Flyway 链，
   已确认 V94 成功），但**未验证**在已有数据的库上做增量升级——本卡是新增表，不改动既有表结构，
   风险面很小，但我没有实际在"带历史数据"的库上演练过。

## 10. 范围声明

**结论：全部改动都在 brief §2 / 卡片 §2 的允许路径内，但有 3 处需要主会话知情**，均属"新表
必需的必要配套"，我按 Y02 的既有先例处理：

| 路径 | 卡片 §2 是否列出 | 理由 |
|---|---|---|
| `.../module-ai/dal/dataobject/semantic/AiMetricSemantics*DO.java` | ❌ 未列出 | 新表必须有 DO。**Y02 的卡片同样没有列出 `dal/`，但 Y02 的 commit（`2f4176a`）创建了 `dal/dataobject/semantic/*DO.java` 与 `dal/mysql/semantic/*Mapper.java`**——本卡沿用同一先例：DO/Mapper 是"领取新迁移号"这一授权的必要映射层 |
| `.../module-ai/dal/mysql/semantic/AiMetricSemantics*Mapper.java` | ❌ 未列出 | 同上 |
| `.../basic-framework-server/src/test/java/.../integration/*.java` | 补充允许"同一变更对应的 src/test" | brief §6 明确要求验收 IT 放在 `server/.../integration/`，属显式授权 |
| `.../db/migration/V94__ai_metric_semantics.sql` | ❌ 未列入 §2 清单 | 卡片 §2 末句显式授权"DB 仅允许领取新迁移号和同步最新快照"；brief §5 指定 V94 |
| `数据库文件/basic_framework.sql` | ❌ 未列入 §2 清单 | 同上（快照同步），且 brief §4 要求四处台账同步 |
| `docs/contracts/ai/error-code-map.md` | ✅ `docs/contracts` | 错误码三处同步之一 |
| `docs/contracts/data-lifecycle.json` / `data-permission-exemptions.json` | ✅ `docs/contracts` | 四处台账同步 |
| `后端代码/.../basic-framework-server/src/main/resources/db/.../PersistenceLifecycleIT.java` | 补充允许"同一变更对应的 src/test" | brief §4 要求同步 |
| `.../enums/AiErrorCodeConstants.java` | ❌ 未列出 | **只减不增**：净 -1 行（Y02 交付时正好 800 行，本卡追加 20 个常量会顶破上限，见 §6.7） |
| `.../enums/AiMetricSemanticsErrorCodeConstants.java` | ❌ 未列出 | 新增文件（§6.7 的拆分产物），`enums/` 不在 §2 清单内；与上一条同源 |
| `.../enums/AiErrorCodeRanges.java` | ❌ 未列出 | 错误码三处同步之一（brief §5 显式点名该文件） |
| `docs/ai-platform/verification/y03-*.md` | ✅ 交付物 | brief §8 要求 |

**未触碰**（明确点名，避免误伤主会话成果）：

- `domain/query/AiQueryPlanValidator.java`（D05 单数据集校验器）——本卡**没有**改动它；
- `service/query/planner/AiQueryPlannerImpl.java`、`service/run/AiRunQueryExecutionServiceImpl.java`
  ——这两个是现有校验器的调用点，**不在本卡允许路径内**，本卡**没有**改动；
- Y02 的 `1_003_015_xxx` 区块、`ai_master_object*` 表与其台账条目；
- 任何 `dal/`、`controller/` 下**既有**文件；
- 前端工程、依赖版本、父 POM、Harness 拓扑、`E:\kuangjia\2026-main`（只读源框架，**未访问、未修改**）。

**git 操作**：全程只读（`git status` / `git log` / `git show` / `git diff`）。
**未执行** `git commit` / `push` / `clean` / `checkout` / `reset`——工作树留给主会话复核。

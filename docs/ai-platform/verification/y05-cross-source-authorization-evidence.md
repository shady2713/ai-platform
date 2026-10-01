# Y05 跨系统权限、撤销与完整性证据

> 卡号：Y05 ｜ 错误码区间：`1_003_018_xxx` ｜ 验收：AT-009 / AT-010 / AT-048 / AT-071 + 三条专项
> 前置依赖：A03 授权目录与判定、A06 撤销、A08 授权集成套件、Y01 发现、Y02 映射、Y03 口径、Y04 有界执行
> 交付形态：**验证卡**——本卡不新增取数能力，交付物是矩阵、反向测试、完整性 UI 与安全评审证据

## 1. 本卡做了什么

Y04 交付了"把跨源执行跑完并说清没跑完的原因"，Y05 回答下一个问题：**跑出来的这个数，
能不能给这个人看**。因此本卡的落点全在授权维度上，主代码只有三处新文件加一个错误码登记册，
其余全是测试与文档。

三条专项各自要求**正向 + 反向两侧**。本卡把反向侧当作主要产出：越权恰恰发生在
"多算了一个来源"、"只是做了一下关联"、"以前有权现在没了"这三处，只跑正向的用例
在这三处都会全绿。

| 专项 | 正向 | 反向（主要产出） |
| --- | --- | --- |
| 一、合计不暴露被禁明细 | 三个来源都真实有权 → 出合计与来源数 | 撤销其一 → 合计被拒 + 条数被拒；失权后重放 → 旧合计做减法被拒 |
| 二、映射无权也拒绝 | 映射有权 → 关联成功 | 数据全可读 + 计划合法 + 映射无权 → 关联仍被拒；跨应用同名资源不串权 |
| 三、捕获无失权数据 | 全部来源仍有权 → 完整交付 | 失权后整份不可读 + 不返回部分结果；重放仍拒；撤销主体后同样不可读 |

## 2. 授权模型

`CrossSourceAccessFacts` 把一个来源的授权拆成三个**互相独立**的判据：

```
systemAuthorized   主体能否访问来源所在系统
datasetAuthorized  主体能否读取该来源数据集
mappingAuthorized  主体能否看到"源键↔统一实体"这条对应关系
```

合并成一个 `authorized` 布尔值是专项二要防的错误，因此 `sourceReadable()` 与
`fullyAuthorized()` 必须给出**不同**答案（`CrossSourceMappingAuthorizationTest` 钉住这一点）。

`CrossSourceCallerRole` 负责**字段可见性**（与 A03 的 `AiAction` 正交：`AiAction` 回答
"允许哪种操作"，角色回答"同一操作下能看哪些字段"），多角色按**交集**裁剪，空交集不退回并集。

## 3. 专项一：合计与计数都是信道

### 3.1 判定

"总额 = 有权部分 + 被禁部分"，因此处置是**整体拒绝**而不是"给个偏小的数"：

| 出口 | 行为 |
| --- | --- |
| `judge(...)` | 任一来源三级求交未通过即拒绝 |
| `requireDisclosableTotal(...)` | 拒绝一切跨过被禁来源的合计；拒绝"历史 ⊋ 本次"的差额可解形状 |
| `discloseSourceCount(...)` | 存在被禁来源时拒绝出具来源数（条数可数） |
| `judgeAfterRevocation(...)` | 失权后重放：见过被禁数字 → `TOTAL_EXPOSES_FORBIDDEN_DETAIL`；没见过 → `SOURCE_NOT_AUTHORIZED` |

最后一行是刻意区分的：两个编号对应两种处置——前者该销毁旧产物，后者该去申请授权。

### 3.2 判据方向（评审中发现的实现缺陷，已修）

差额可解的危险形状是**历史覆盖 ⊋ 本次覆盖**（曾经见过、现在不出现）；
"曾经见过、现在还在"是同一次口径的正常重算，不构成泄漏。

初版把 `containsAll` 的接收者写反，使得真正的攻击形状**永不触发**——判据静默失效，
而正向测试依然全绿。反向用例
`replayingAfterRevocationIsRefusedBecauseTheOldTotalMakesTheDetailSolvable` 把它抓了出来，
已按 `!visibleSources.containsAll(previouslySeen)` 修正并补注释。

## 4. 专项二：映射与来源同级

映射（源键↔统一实体）是一份跨系统事实，本身就是泄露。`judge(...)` 把映射无权**单独归类**，
报专用编号 `AI_CROSS_SOURCE_AUTHZ_MAPPING_NOT_AUTHORIZED`（`1_003_018_002`），
与来源无权 `AI_CROSS_SOURCE_AUTHZ_SOURCE_NOT_AUTHORIZED`（`1_003_018_001`）区分——
两者处置动作不同（申请映射权限 vs 申请数据权限）。

初版把两类拒绝都收敛到来源级编号，专项二的反向用例期望映射编号而失败，暴露了该缺陷（已修）。

## 5. 专项三：验证真的没有

`CrossSourceModelInputCaptureVerifier` 刻意**不产出部分结果**：

- 复核用**当前**授权事实（捕获可能是几分钟前生成的，写入时的结论对读取没有意义）；
- 任一来源失权 → 整份不返回，"有几项被拦"也只表现为一次整体拒绝（删除动作本身就是信道）；
- 授权事实缺失按失权处理（查不到授权 ≠ 仍然有权）。

反向用例额外证明"被禁字面量确实存在于捕获正文中"，否则"读不到"可能只是因为捕获本来就是空的。

## 6. 跨源权限矩阵

矩阵由 `CrossSourcePermissionMatrix` **算出**而非抄进文档（静态表格会与实现漂移）。
维度：**授权来源（系统 / 数据集 / 实体映射）× 角色 × 可见字段**，越权行为逐格写明。
完整矩阵与逐格判据见 `docs/security/ai-cross-source-authorization-review.md` §6。

## 7. 完整性 UI

前端新增 `cross-source-integrity.ts`（`CrossSourceIntegrity` 互斥联合 + `rendersNothing` +
`integrityNotice`），并接入 `ResultTable.vue`：

- `WITHHELD` 时**一个数字都不渲染**：表格、分页条数、来源计数全部不出现——
  只清空表格而保留 `pageInfo.total` 是不够的，行数本身就是信道；
- 提示文案必须说明"不是临时故障，重试不会改变结果"，否则用户会无谓重试；
- 解析期**拒绝**未知 `state`（fail-closed），不降级成"看起来完整"——
  把认不出的状态当 `COMPLETE` 渲染等于凭空替后端宣布"你有权看"。

`crossSourceIntegrity` 与既有 `completeness` 是**两个维度**（授权 vs 技术），
混成一个字段会把授权拒绝显示成"结果可能不完整"，用户会以为是临时故障。

## 8. 评审中发现的缺陷

| # | 位置 | 缺陷 | 修法 |
| --- | --- | --- | --- |
| 1 | `AiCrossSourceAuthorizationJudge.requireDisclosableTotal` | 差额可解判据方向写反，"历史 ⊋ 本次"永不触发 | 改为 `!visibleSources.containsAll(previouslySeen)` |
| 2 | `AiCrossSourceAuthorizationJudge.judge` | 映射无权被收敛成来源级编号，丢专项二专用编号 | 单独归类 `mappingDeniedRoles`，报映射专用编号 |
| 3 | `AiCrossSourceAuthorizationErrors` | 消息带插参但无 `{}` 占位符，参数被丢弃并打 ERROR | 改全静态消息，删 `REDACTED_ROLE` |
| 4 | `AiErrorCodeConstants.java` | Y04 后已 800 行零余量，本卡 `extends` 顶到 802 行超限 | 压缩文件头 Javadoc 回 800 行，不删既有常量 |

## 9. 已知局限

- **不涉及数据库迁移**：判定器是无状态纯函数，捕获复核不落盘，因此**不领 V96 迁移号**，
  也不牵动 `basic_framework.sql` / `data-lifecycle.json` / `data-permission-exemptions.json` /
  `PersistenceLifecycleIT` 四处台账（无新表即无台账改动）。
- **映射未新增资源类型**：沿用 `DATASET` + `mapping:<datasetCode>` 表达映射授权，
  未自造 `MASTER_MAPPING`（`domain/policy` 不在本卡允许路径内）。
- **本卡不接线到 HTTP 层**：`1_003_018_xxx` 编号已登记且可被 `GlobalExceptionHandler`
  按名派生状态，但跨源合并的对外入口属于后续执行卡，本卡只交付判定与验证。

### 9.1 主管裁决：完整性 UI 当前是"契约就位、接线未完成"（Y06 硬性交付项）

上面第 3 条在 UI 侧的具体后果，主管复核时逐条 grep 确认，**如实记录**：

- **后端零产出**：全 `后端代码/` 树下 `crossSourceIntegrity` **无任何命中**。
- **前端字段是可选的**：`blocks.ts:101` 声明为 `crossSourceIntegrity?: CrossSourceIntegrity`，
  `blocks.ts:281` 仅在 `!== undefined` 时解析。因此**真实响应不含该字段时，
  `ResultTable.vue` 照常渲染全部数字，本卡的完整性 UI 对真实数据完全不生效。**

**这不是安全漏洞，但它是 fail-open 缺口**：字段缺失时降级为"照常显示"，
一旦后端在跨源场景漏发该字段，界面就会静默呈现本该被 `WITHHELD` 隐藏的数字。
字段**存在但状态认不出**时则是严格 fail-closed（`parseCrossSourceIntegrity` 解析期直接拒绝，
不降级成"看起来完整"）——两处行为不对称，是有意保留的缺口。

**为什么不在本卡补**：`blocks.ts` 的解析结果最终要落到跨源合并响应的 VO/Controller，
而 `service/query/crosssource` 与 `controller/**` 都不在本卡 §2 允许路径内；
卡片 §5 又把"完整性UI"列为硬性产出。这是**卡片自身的范围缺口**，
在本卡越界补齐会破坏允许路径约束。

**裁决**：接受本卡现状。这个缺口**最初没有归属卡**，现已由 **[Y07](../../tasks/Y07.md)**
（接入跨源结果契约与跨源合并对外入口）承接：

- **主管曾一度裁定"转 Y06 第一顺位"，该裁定已作废**：复核 Y06 卡片后确认其 §2 允许路径只有
  `docs/acceptance`、`docs/upgrades`、`module-ai/src/test/.../compatibility`、
  `前端代码/basic-framework-admin/tests/compatibility`，**全部是文档与测试目录，没有任何生产代码路径**，
  而 Y06 的验收条款是"旧单系统服务行为和授权**无回退**"——它的职责是证明没有回退，不是新增产出。
  把它塞进 Y06 既越界又与该卡意图相反。
- 因此单独建卡 Y07（已写入 `index.json` 与 `docs/ai-platform/tasks/Y07.md`）。
- **Y07 必须保证**：该字段在跨源场景**恒非空**（缺失即视为 `WITHHELD`），从而消除本节记录的
  fail-open 路径；并对单系统响应保持零影响。

## 10. 验证记录

聚焦测试（未跑任何门禁）：

| 命令 | 结果 |
| --- | --- |
| `mvnw -o -pl basic-framework-module-ai test -Dtest='com.basicframework.module.ai.security.*Test'` | 41 通过 / 0 失败 |
| `mvnw -o -pl basic-framework-module-ai test`（全模块回归） | 1654 通过 / 0 失败 |
| `mvnw -o -pl basic-framework-server test -Dtest='AiCrossSourceAuthorizationAcceptanceIT'` | 12 通过 / 0 失败（真实 MySQL Testcontainer） |
| `mvnw -o -pl basic-framework-server test -Dtest='ErrorCodeUniquenessTest'` | 1 通过（`1_003_018_xxx` 全库唯一） |
| `mvnw -o -pl basic-framework-module-ai test -Dtest='AiFieldAndErrorCodeContractTest'` | 4 通过 |
| `npx vitest run src/message/__tests__/` | 92 通过 / 0 失败（6 个文件） |
| `npx prettier --check` / `npx eslint` / `npx cspell`（改动文件） | 全部干净 |

### 新增主源码行覆盖（module 级 sourcefile，棘轮口径）

| 文件 | 行覆盖 | 未覆盖行 |
| --- | --- | --- |
| `AiCrossSourceAuthorizationJudge.java` | 100.00% | — |
| `AiCrossSourceAuthorizationErrorCodeConstants.java` | 100.00% | — |
| `AiCrossSourceAuthorizationErrors.java` | 100.00% | — |
| `CrossSourceAccessFacts.java` | 100.00% | — |
| `CrossSourcePermissionMatrix.java` | 100.00% | — |
| `CrossSourceModelInputCaptureVerifier.java` | 100.00% | — |
| `CrossSourceCallerRole.java` | 96.55% | 105 |

第 105 行是词表自检里的 `throw new IllegalStateException`：它在 `KNOWN_FIELDS` 与角色集合
同源的前提下**不可达**，保留它是为了拦住"给角色加了字段却忘了加词表"这类维护性回归，
代价是 1 行不可达覆盖，文件仍远高于 80% 棘轮。

评审期间自查到并补掉的覆盖缺口：`CrossSourceAccessFacts.key()`/`denyReason()` 的空值与
三级归一分支、`CrossSourceGrant` 的防御性归零与"未放行凭据不得暴露字段"、
`judgeAfterRevocation` 的空失权集合、`denied()` 的"可反推"分支、
`Errors.executionNotExists()` 的 404 出口。同时删掉了一处 `judge()` 里
`facts == null` 的**死分支**——它已被 `requireValid` 先行拒绝，永远不可达。

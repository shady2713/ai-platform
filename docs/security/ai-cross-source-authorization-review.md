# 跨系统授权、撤销与完整性（Y05 安全评审证据）

> 卡号：Y05 ｜ 区间：`1_003_018_xxx` ｜ 涉及：AT-009 / AT-010 / AT-048 / AT-071
> 依赖：A03 授权目录与判定、A06 撤销、A08 授权集成套件、Y01/Y02/Y03/Y04 的跨源前置能力

## 1. 本卡评审了什么

Y05 不新增取数能力，它评审的是**跨源能力在授权维度上是否站得住**：Y03 负责把计划
校验对，Y04 负责把执行跑完，Y05 负责回答"跑出来的这个数，能不能给这个人看"。

评审对象是三条专项，每条都要求**正向与反向两侧**：

| 专项 | 要回答的问题 | 反向侧为什么不可省 |
| --- | --- | --- |
| 一、合计不暴露明细 | 多个有权来源求和后，能否反解出被禁来源 | 只测正向等于没测：越权恰恰发生在"多算了一个来源"的时候 |
| 二、映射无权也拒绝 | 计划合法、两个数据集都能读，映射无权时是否放行 | 正向必然通过，反向才区分"授权 = 数据集"还是"授权 = 数据集 + 关联" |
| 三、捕获无失权数据 | 失权后模型输入捕获里是否残留旧数据 | 只测正向等于没测：捕获是**已落地的旧数据**，风险全在失权之后 |

## 2. 授权模型：三级求交，不合并成一个布尔

`CrossSourceAccessFacts`（`service/authorization/crosssource/CrossSourceAccessFacts.java`）
把一个来源的授权拆成**三个互相独立的**问题：

```
systemAuthorized  主体能否访问来源所在的系统
datasetAuthorized 主体能否读取该来源具体的数据集
mappingAuthorized 主体能否看到"源键↔统一实体"这条对应关系
```

合并成一个 `authorized` 布尔值正是专项二要防的错误：`sourceReadable()` 与
`fullyAuthorized()` 必须给出**不同**答案，否则"我只是在做关联"会成为绕过映射判定的
后门。`denyReason()` 把三级归一为稳定词表（`SOURCE_SYSTEM_NOT_AUTHORIZED` /
`SOURCE_DATASET_NOT_AUTHORIZED` / `ENTITY_MAPPING_NOT_AUTHORIZED`），运维据此知道
该申请哪一级权限。

## 3. 专项一：合计与计数都是信道

### 3.1 为什么"少算一个来源"也是泄漏

"总额 = 有权部分 + 被禁部分"。只要平台出具总额，调用方拿自己有权的那部分做减法就得到
被禁部分。因此判定**不是**"给一个偏小的合计"，而是**整体拒绝**：

- `judge(...)`：任一来源无权即拒绝；
- `requireDisclosableTotal(...)`：拒绝一切跨过被禁来源的合计；
- `discloseSourceCount(...)`：拒绝一切会让调用方数出"少了几格"的计数。

### 3.2 判据方向（评审中发现并修正的一处实现缺陷）

差额可解的判据是**历史覆盖范围 ⊋ 本次覆盖范围**。危险的是"曾经见过、现在不出现"；
而"曾经见过、现在还在"是同一次口径的正常重算，不构成泄漏。

初版实现把 `containsAll` 的接收者写反了（用 `previouslySeen.containsAll(visibleSources)`），
使得"历史集合 ⊋ 本次集合"这一真正的攻击形状**永远不触发**——判据静默失效。
反向用例 `replayingAfterRevocationIsRefusedBecauseTheOldTotalMakesTheDetailSolvable`
把它抓了出来，已按 `!visibleSources.containsAll(previouslySeen)` 修正。

这类缺陷是本卡不写 happy path 的直接理由：判据写反时，正向测试**依然全绿**。

### 3.3 拒绝不可区分

拒绝消息是**静态的**，不携带任何插参（与 Y04 `AiCrossSourceExecutionErrors` 一致：
`ServiceExceptionUtil` 只对含 `{}` 的消息做格式化）。消息里没有角色名、系统码、数据集码，
更没有规模与取值——"把哪个参数塞进消息"本身就会变成新的枚举通道。
反向用例直接断言 `getMessage()` 不含 `payment` / `sys-payment` / `y04_payments`。

## 4. 专项二：映射与来源同级

映射（源键↔统一实体）是一份**跨系统事实**：知道"C-001 在系统 A 与系统 B 是同一实体"
已经泄露两个系统之间的对应关系。`judge(...)` 因此把映射无权**单独归类**，
报专用编号 `AI_CROSS_SOURCE_AUTHZ_MAPPING_NOT_AUTHORIZED`（`1_003_018_002`），
与来源无权 `AI_CROSS_SOURCE_AUTHZ_SOURCE_NOT_AUTHORIZED`（`1_003_018_001`）区分——
两者的处置动作不同：前者去申请映射权限，后者去申请数据权限。

初版实现把两类拒绝都收敛到来源级编号，专项二的反向用例断言的是映射专用编号，
因此把该缺陷暴露了出来（见 §7 缺陷 2）。

## 5. 专项三：验证真的没有，而不是再过滤一次

`CrossSourceModelInputCaptureVerifier` 刻意**不产出部分结果**：

- 复核用**当前**授权事实，而不是写入时的结论——捕获可能是几分钟前生成的；
- 任一来源失权 → **整份捕获**不返回，连"有几项被拦"都只表现为一次整体拒绝；
- 授权事实缺失按失权处理（查不到授权 ≠ 仍然有权，fail-closed）。

"过滤式实现"会返回 3/4 条并让调用方知道"有一项被删了"——删除动作本身就是信道。
因此反向用例断言的是**抛错 + 错误码**，并额外证明"被禁字面量确实存在于捕获正文中"
（`capture.containsText("payment")` 为真），否则"读不到"可能只是因为捕获本来就是空的。

## 6. 跨源权限矩阵

矩阵由 `CrossSourcePermissionMatrix` **算出**而非抄进文档——静态表格会与实现漂移，
而由同一份判据算出来的表，语义一旦变化测试立刻失败（与 A08 授权矩阵同一理由）。

维度：**授权来源（来源系统 / 数据集 / 实体映射）× 角色 × 可见字段**。

### 6.1 角色的字段可见性

| 角色 | 可见字段 | 不可见 |
| --- | --- | --- |
| `AGGREGATE_READER` | amount, currency, total | 全部维度键、来源标识、明细行 |
| `ANALYST` | 上述 + customer_key, product_key, as_of | source_system, source_key, row_count |
| `DATA_STEWARD` | 全部字段 | — |

字段可见性按 `CrossSourceCallerRole.commonFields(...)` 取**交集**，不是并集：
多角色同时持有时按最弱裁剪。空交集是合法结果，调用方据此拒绝出具明细，
**不退回到并集**。

### 6.2 越权行为逐格

| 来源系统 | 数据集 | 实体映射 | 判定 | 越权行为 |
| --- | --- | --- | --- | --- |
| 有权 | 有权 | 有权 | 放行 | 按角色交集裁剪字段后出具 |
| 无权 | — | — | 拒绝 | `AI_CROSS_SOURCE_AUTHZ_SOURCE_NOT_AUTHORIZED`：拒绝参与合并，消息不点名来源 |
| 有权 | 无权 | — | 拒绝 | 同上（调用方据 `denyReason()` 区分是系统还是数据集级） |
| 有权 | 有权 | **无权** | 拒绝 | `AI_CROSS_SOURCE_AUTHZ_MAPPING_NOT_AUTHORIZED`：拒绝跨系统关联，**计划合法也不放行** |

第三行是专项二的核心格：两个数据集都能读、计划完全合法，只要映射无权仍然拒绝。

## 7. 评审中发现的缺陷

| # | 位置 | 缺陷 | 证据 | 修法 |
| --- | --- | --- | --- | --- |
| 1 | `AiCrossSourceAuthorizationJudge.requireDisclosableTotal` | 差额可解判据方向写反，"历史 ⊋ 本次"这一攻击形状永不触发 | 反向用例 `replayingAfterRevocationIsRefusedBecauseTheOldTotalMakesTheDetailSolvable` 期望抛错却通过 | 改为 `!visibleSources.containsAll(previouslySeen)`，并补注释说明方向含义 |
| 2 | `AiCrossSourceAuthorizationJudge.judge` | 映射无权被收敛成来源级编号，丢失专项二的专用编号 | 反向用例 `anUnauthorizedMappingIsRefusedEvenThoughThePlanIsLegalAndAllDataIsReadable` 期望 `1_003_018_002` 实际 `1_003_018_001` | 单独归类 `mappingDeniedRoles`，映射无权且无其它来源无权时报映射专用编号 |
| 3 | `AiCrossSourceAuthorizationErrors` | 错误消息带插参但消息体无 `{}` 占位符，参数被静默丢弃并打 ERROR 日志 | 单测断言消息内容失败；`ServiceExceptionUtil.doFormat` 在无占位符时走"参数过多"分支 | 改为全静态消息（fail-closed：拒绝路径不带动态内容），删除 `REDACTED_ROLE` 常量 |
| 4 | `AiErrorCodeConstants.java` | Y04 交付后已是 800 行零余量，本卡 `extends` 新登记册会顶到 802 行，超 `check-source-quality` 的 800 行上限 | `wc -l` = 802 | 压缩文件头 Javadoc 为单行，回到 800 行（不删任何既有常量） |

## 8. 未覆盖与遗留

- **不涉及数据库迁移**：本卡不新建表。判定器是无状态的纯函数，捕获复核不落盘，
  因此不需要 V96 迁移号，也就不牵动 `basic_framework.sql` / `data-lifecycle.json` /
  `data-permission-exemptions.json` / `PersistenceLifecycleIT` 四处台账。
- **映射资源类型未新增**：`AiResourceType` 只有 DATASET/KNOWLEDGE_BASE/REPORT/FILE/TOOL，
  映射授权沿用 DATASET + `mapping:<datasetCode>` 资源键表达，没有自造第二套资源类型。
  若后续要独立成 `MASTER_MAPPING` 资源类型，需改 `domain/policy`，超出本卡允许路径。
- **前端只做了完整性 UI 的展示口径**，不改变渲染安全边界（沿用 R12 已有边界）。

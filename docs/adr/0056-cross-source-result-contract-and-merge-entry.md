# ADR 0056：跨源结果契约的"口径恒带"与对外入口

| 项目 | 内容 |
| --- | --- |
| 状态 | Accepted |
| 日期 | 2026-03-01 |
| 决策卡 | [Y07](../ai-platform/tasks/Y07.md) |
| 相关 | [ADR 0054](0054-bounded-cross-source-execution-and-unified-result.md)（Y04 有界执行）、[ADR 0049](0049-ai-open-identity-and-security-extension-boundaries.md)（身份边界）、[ADR 0003](0003-http-status-semantics.md)（HTTP 状态语义） |

## 1. 背景

Y05 交付了跨系统授权判定与完整性 UI，Y04 交付了有界跨源执行与统一结果，
Y06 证明了整条链对单系统行为无回退。但三者之间有一处从未接线：

- 后端**零产出** `crossSourceIntegrity`——Y05 证据文档 §9.1 逐条 grep 确认
  `后端代码/` 全树无任何命中；
- 前端把它声明成**可选**字段（`blocks.ts` 声明为 `crossSourceIntegrity?`，
  仅在 `!== undefined` 时解析），缺失时 `ResultTable.vue` 照常渲染全部数字。

这不是安全漏洞，是一条 **fail-open 缺口**：后端一旦在跨源场景漏发该字段，
界面会静默呈现本该被 `WITHHELD` 隐藏的数字。同时它与同一条链路上另一处行为不对称——
字段**存在但状态认不出**时，解析期直接拒绝（fail-closed）。

Y07 承接这个缺口。**它不是新增能力，是把已声明的契约接到生产数据上。**

## 2. 决策

### 2.1 跨源标记与完整性口径拆成两个字段

这是本 ADR 最关键的一条，也是两侧契约的形状。

```
TableBlock / AiCrossSourceMergeRespVO {
  crossSource: boolean              // 判别键：这份结果来自跨源合并
  crossSourceIntegrity?: {          // 授权维度口径
    state: "COMPLETE" | "PARTIAL" | "WITHHELD"
    reason?: string                 // PARTIAL/WITHHELD 必带，COMPLETE 必不带
  }
}
```

**为什么不能只有一个字段。** 只有一个 `crossSourceIntegrity` 时，
"跨源响应漏发口径"与"单系统响应"在解析结果里**完全无法区分**。于是只有两个选择，
两个都是错的：

- 把所有缺口径的表格判成 `WITHHELD` → 毁掉 Y06 证明的"单系统无回退"，
  旧单系统报表整片消失（看起来像故障，比泄露更糟）；
- 继续让缺口径的跨源响应按普通表格渲染 → fail-open 缺口原样保留。

拆成两个字段之后，两件事各归各，且都可用**可观测的状态**区分：

| 输入 | 判定 | 依据 |
| --- | --- | --- |
| 标记缺失 | 单系统路径，零影响 | Y06 at-070 判据保持成立 |
| 标记 `true` + 口径缺失 | **`WITHHELD`** | 平台没能证明调用方有权看 |
| 标记 `true` + 口径存在 | 按口径渲染 | — |
| 标记 `true` + 状态认不出 | 解析期整块拒绝 | Y05 fail-closed 语义，不削弱 |

标记刻意<b>不默认成 `false`</b>：旧单系统载荷解析后不得凭空多出这个键，
否则 Y06 的 `Object.keys(block)` 逐字断言会变形。

### 2.2 缺失即 `WITHHELD`，绝不降级成 `COMPLETE`

两侧同向，规则只有一条：**任何"拿不到口径"的情况都落到 `WITHHELD`**。

- **前端**：`CROSS_SOURCE_INTEGRITY_MISSING` 常量，解析期注入。
- **后端**：`CrossSourceIntegrity` 规范构造器把 `null`/空白/未知字面量一律归一为
  `WITHHELD`；`CrossSourceResultContract` 构造器把 `null` 口径归一为
  `CrossSourceIntegrity.missing()`。

**为什么默认值不能选放行。** 把缺省选成 `COMPLETE`，等于让**前端替后端宣布
"你有权看这份结果"**——一次接线遗漏会直接变成一次真实的越权披露。选 `WITHHELD`
的代价只是"明明有权却暂时看不到"，而那正是应该被追问的状态。

**为什么不降级成 `PARTIAL`。** 那会把一次契约缺陷显示成"数据可能不完整"，
把排查方向从"后端没发口径"带偏到"某个来源挂了"，两者的处置动作完全不同。

### 2.3 口径即闸门：数字不是靠自觉不给的

`CrossSourceResultContract` 的规范构造器在口径为 `WITHHELD` 时
**强制抹掉** `totalAmount` / `sourceCount` / `sources` / `consistencyAsOf` /
`maxSkewMillis`。这是"无权来源不可反推明细"的实现方式，两条腿分别对应：

- **差额可解**（旧合计减新合计解出被禁来源）→ 没有 `totalAmount`；
- **条数可数**（"这次合了几个来源"本身是信道）→ 没有 `sourceCount`、
  没有 `sources`。

只堵合计而留下来源条数，等于把明细换成计数再送出去。
结构上抹除而不是"调用方记得别填"，是因为后者在改一次代码时就会被绕过。

### 2.4 授权口径完全复用 Y05，不另起一套

对外入口的判定链路是：读 Y04 台账 → 用**当前**真实授权解析事实 → 交 Y05
`judge` 逐级求交 → 过 Y05 的两条披露闸门（差额可解 / 条数可数）。

- **判定范围来自台账事实，不来自请求参数。** 请求里刻意没有"计划声明了哪些来源"：
  让调用方报来源清单，等于让它可以少报来源、把无权来源挤出判定范围。
- **无权但恰好取数失败的来源仍进判定范围。** 只按 `COUNTED` 行判定会开出
  "只要它失败就绕过授权"这条���。
- **Y05 的拒绝编号原样传播**，本层不把拒绝包装成成功响应——那会让调用方在
  "没权限"与"成功了但没数据"之间误判处置动作。
- **撤销后立即不可读**：判定读当前授权而非执行时快照。

### 2.5 错误码独立成域（`1_003_020_xxx`）

不复用 Y04（"这次为什么没跑完"）与 Y05（"这次为什么不允许你看"）的编号。
本域只回答第三个问题：**判定都答完之后，这份响应能不能发出去**。

把"台账里没这条执行"塞进 Y05 那个区间，会让调用方在"我无权"与"我查错了"
之间误判处置动作。编号分段与语义边界见
[error-code-map.md](../contracts/ai/error-code-map.md)。

## 3. 为什么要有"只读口径"这个端点

`GET /ai/cross-source/results/{executionKey}/integrity` 的响应里**只可能出现
`state` 与 `reason` 两个字符串**，无论判定结论如何。

它是本卡 fail-closed 承诺的**端点级可观测出口**：界面可以先问"这份结果能不能给你看"，
拿到口径后再决定要不要去取数字。口径为 `WITHHELD` 时，金额在服务端**从未被序列化过**，
而不是"发出去了又藏起来"。

两个端点、两个权限码、**每个端点恰好一个 `@PreAuthorize`**：鉴权口径不叠加第二层，
叠加会让"这个端点到底受哪个权限保护"无法从代码一眼读出来。

## 4. 控制面与数据面是两层

`@PreAuthorize`（控制面）回答"这个管理端操作者能不能调这个接口"；
Y05 逐级求交（数据面）回答"这次被请求的跨源结果里有没有他无权看的来源"。
**缺一不可**，且端点**不**自己判定数据面授权——那会让授权口径散到 Controller，
并让两个端点出现两套判定结果。

## 5. 不新增数据表

跨源执行台账由 V95（Y04）建立。Y07 只在其上组装响应契约，运行期产物不落新表。
**刻意不为了"看起来有产出"造一张空表。**

因此契约台账里 `data-lifecycle.json`（软删表 71 张）与
`data-permission-exemptions.json` 的登记项**不需要改动**，
`version` 分别保持 1 与 3；SQL 快照只更新表头的 `through V96` → `through V97`。

## 6. 后果

**正面**

- fail-open 缺口关闭：跨源响应漏发口径不再静默渲染数字。
- "不可反推明细"从约定变成结构性质，`WITHHELD` 响应在类型层面就装不下数字。
- 对外入口不另起授权口径，Y05 的判定与披露闸门是唯一授权事实来源。
- 单系统链路逐字段零影响，Y06 的判据与测试原样通过。

**代价与边界**

- 表格块多一个可选字段 `crossSource`，解析层多一条分支。
- 后端多一次台账读取与一次逐来源授权判定（每来源 2 次 A03 调用：数据集 + 映射）。
  判定**不做跨请求缓存**——撤销必须立即生效，这是刻意的取舍。
- 一个诚实的边界：若后端连 `crossSource` 标记也漏发，该响应与单系统响应不可区分，
  只能按单系统处理。本卡消除的是"发了标记却漏发口径"这条 fail-open 路径；
  要连标记一起兜住，需要在网关或协议层强制"跨源端点必带标记"，超出本卡范围。

## 7. 验证

- 后端单测：`CrossSourceResultContractTest`（口径归一三分支 + 闸门抹除）、
  `AiCrossSourceMergeServiceTest`（台账 → 响应，逐条 fail-closed 出口）、
  `AiCrossSourceMergeControllerTest`（权限码与 V97 种子一致；**用项目默认
  `JsonUtils`（全局 `NON_NULL`）序列化**后仍能拿到 `"reason":null`，
  证明 VO 上的 `@JsonInclude(ALWAYS)` 压过了全局默认）、
  `A03CrossSourceAccessFactsResolverTest`（数据集与映射两次独立判定）。
- 真实 MySQL IT：`AiCrossSourceMergeContractAcceptanceIT`，AT-071 与三条专项，
  授权事实全部来自真实 A01/A02/A03/A06 与真实 MySQL。
- 前端：`y07-cross-source-result-contract.test.ts`（14 条，含缺口径的反向用例），
  以及 Y06 `at-070`（7 条）与 Y05 `cross-source-integrity.test.ts`（9 条）原样通过。

证据见
[y07-cross-source-result-contract-evidence.md](../ai-platform/verification/y07-cross-source-result-contract-evidence.md)。

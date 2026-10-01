# Y07 验证证据：跨源结果契约与跨源合并对外入口

| 项目 | 内容 |
| --- | --- |
| 卡片 | [Y07](../tasks/Y07.md) |
| 验收 | AT-071 + 三条专项 |
| 迁移 | V97（菜单/权限种子，**无新表**） |
| 错误码 | `1_003_020_xxx`（新增子区间） |
| ADR | [ADR 0056](../../adr/0056-cross-source-result-contract-and-merge-entry.md) |
| 关闭的缺口 | [Y05 证据文档 §9.1](y05-cross-source-authorization-evidence.md) 记录的 fail-open 缺口 |

## 1. 本卡做了什么

Y05 交付了跨系统授权判定与完整性 UI，但**后端零产出 `crossSourceIntegrity`**，
前端把它声明成可选字段、缺失时按普通表格渲染——即 Y05 §9.1 记录的 **fail-open 缺口**。
Y07 把这条已声明的契约接到生产数据上，并补上对外 HTTP 入口。

**它不是新增能力。** 三个状态名 `COMPLETE | PARTIAL | WITHHELD` 逐字沿用 Y05
前端的排他联合，未另造状态名、未改成可选 `state?: string`。

## 2. 跨源结果契约

### 2.1 字段语义与"两个字段"的关键决定

```
crossSource: boolean                    // 判别键：这份结果来自跨源合并
crossSourceIntegrity: {                 // 授权维度完整性口径，恒非空
  state: "COMPLETE" | "PARTIAL" | "WITHHELD"
  reason?: string                      // PARTIAL/WITHHELD 必带；COMPLETE 必不带
}
```

**为什么必须拆成两个字段**（这是本卡全部设计的支点）：只有一个口径字段时，
"跨源响应漏发口径"与"单系统响应"在解析结果里**完全无法区分**。于是只有两个选择，
两个都是错的：

- 缺口径一律判 `WITHHELD` → 毁掉 Y06 证明的"单系统无回退"，旧单系统报表整片消失；
- 继续按普通表格渲染 → fail-open 缺口原样保留。

拆开后两件事各归各，且都可用**可观测状态**区分：

| 输入 | 判定 | 依据 |
| --- | --- | --- |
| 标记缺失 | 单系统路径，零影响 | Y06 at-070 判据保持成立 |
| 标记 `true` + 口径缺失 | **`WITHHELD`** | 平台没能证明调用方有权看 |
| 标记 `true` + 口径存在 | 按口径渲染 | — |
| 标记 `true` + 状态认不出 | 解析期整块拒绝 | Y05 fail-closed 语义，**未被削弱** |

标记刻意不默认成 `false`：旧单系统载荷解析后不得凭空多出这个键，
否则 Y06 的 `Object.keys(block)` 逐字断言会变形。

### 2.2 缺失时的规定行为

**规则只有一条：任何"拿不到口径"的情况都落到 `WITHHELD`，绝不按 `COMPLETE`。**

- 前端：`CROSS_SOURCE_INTEGRITY_MISSING` 常量（`cross-source-integrity.ts`），
  解析期注入到 `crossSource=true` 而口径缺失的块上。
- 后端：`CrossSourceIntegrity` 规范构造器把 `null` / 空白 / 未知字面量一律归一为
  `WITHHELD`；`CrossSourceResultContract` 构造器把 `null` 口径归一为
  `CrossSourceIntegrity.missing()`。

**为什么不降级成 `PARTIAL`**：那会把一次契约缺陷显示成"数据可能不完整"，
把排查方向从"后端没发口径"带偏到"某个来源挂了"，两者处置动作完全不同。

### 2.3 口径即闸门

`CrossSourceResultContract` 构造器在 `WITHHELD` 时**强制抹掉** `totalAmount` /
`sourceCount` / `sources` / `consistencyAsOf` / `maxSkewMillis`。
这是"不可反推明细"的实现方式，两条腿分别对应：

- **差额可解**（旧合计减新合计解出被禁来源）→ 没有 `totalAmount`；
- **条数可数**（"这次合了几个来源"本身是信道）→ 没有 `sourceCount`、没有 `sources`。

只堵合计而留下来源条数，等于把明细换成计数再送出去。
结构上抹除而不是"调用方记得别填"，是因为后者在改一次代码时就会被绕过。

### 2.4 与技术完整性是两个维度

`completeness`（Y04，技术）回答"口径内有没有来源没跑成"；
`crossSourceIntegrity`（Y05/Y07，授权）回答"有没有来源你无权"。
混成一个字段会把授权拒绝显示成"结果可能不完整"，用户会以为是临时故障反复重试。

## 3. 对外入口

| 端点 | 权限码 | 承诺 |
| --- | --- | --- |
| `GET /ai/cross-source/results/{executionKey}` | `ai:cross-source:query` | 响应恒带 `crossSource` 与 `integrity`；`WITHHELD` 时无任何数字 |
| `GET /ai/cross-source/results/{executionKey}/integrity` | `ai:cross-source:integrity` | 响应体**只可能出现 `state` 与 `reason`**，金额与条数从未被序列化 |

**每个端点恰好一个 `@PreAuthorize`**（`AiCrossSourceMergeControllerTest` 逐个端点反射断言）。
"只读口径"端点是本卡 fail-closed 承诺的端点级可观测出口：界面先问"能不能给你看"，
拿到口径再决定要不要取数字。

**控制面（`@PreAuthorize`）与数据面（Y05 逐级求交）是两层**，端点**不**自己判定数据面授权。

### 3.1 授权口径完全复用 Y05

链路：读 Y04 台账 → 用**当前**真实授权解析事实 → 交 Y05 `judge` 逐级求交
→ 过 Y05 两条披露闸门（差额可解 / 条数可数）。

- 判定范围来自**台账事实**（`planSources` 取自全部来源行，含 MISSING/FAILED），
  不来自请求参数——请求里刻意没有"计划声明了哪些来源"。
- **无权但恰好取数失败的来源仍进判定范围**：只按 `COUNTED` 判定会开出
  "只要它失败就绕过授权"这条缝。
- Y05 的拒绝编号**原样传播**，本层不把拒绝包装成成功响应。
- **撤销后立即不可读**：判定读当前授权而非执行时快照。

### 3.2 错误码（`1_003_020_xxx`）

| 名称 | 编号 | HTTP | 触发 |
| --- | --- | --- | --- |
| `AI_CROSS_SOURCE_CONTRACT_EXECUTION_NOT_EXISTS` | 1_003_020_000 | 404 | 台账没有这条执行 |
| `AI_CROSS_SOURCE_CONTRACT_RESULT_NOT_ISSUABLE_CONFLICT` | 1_003_020_001 | 409 | 受控结束/仍在跑/无来源计入（**技术**失败） |
| `AI_CROSS_SOURCE_CONTRACT_REQUEST_INVALID` | 1_003_020_002 | 422 | 缺执行键/主体上下文/角色（fail-closed） |

**受控结束刻意报 409 而不是 `WITHHELD`**：Y05 的 `WITHHELD` 提示写的是
"请联系管理员开通授权"，用它表达一次执行失败会把用户引向错误的处置动作。
处置动作在这里是"修数据或换执行键重跑"。同理也**不复用** Y05 编号——
把"台账里没这条执行"塞进 Y05 区间会让调用方在"我无权"与"我查错了"之间误判。

## 4. 验证记录

### 4.1 专项一（反向）：跨源响应缺失完整性口径按 `WITHHELD` 而非 `COMPLETE`

**只能靠构造一个故意不带该字段的跨源响应来证明。** 只跑正常路径等于没做这条验收：
正常路径下 fail-open 与 fail-closed 的渲染结果完全一样。

| 层 | 用例 | 断言对象 | 结果 |
| --- | --- | --- | --- |
| 前端 | `带 crossSource 标记但缺口径 → 解析结果就是 WITHHELD，不是 COMPLETE` | 真实 `parseMessageBlock` | `state !== 'COMPLETE'`，`=== 'WITHHELD'` |
| 前端 | `缺口径的跨源响应：一个数字都不渲染（表格/行/条数全无）` | 真实 `ResultTable` | 表格/分页/空态全不渲染，文本不含 `100.00`/`30.00`/`C-001` |
| 前端 | `缺口径的提示必须说清"不是缺数据、而是没给出口径"，且不得点名来源` | 真实 `ResultTable` | 含"该结果未出具""未声明"，不含 `payment`/`回款`/`来源 \d` |
| 前端 | `（对照）后端真的发了 COMPLETE 时才放行` | 真实 `ResultTable` | 表格渲染、`100.00` 可见、"共 2 条"可见 |
| 后端 | `nullIntegrityIsTreatedAsWithheldNotComplete` | 真实 record 规范构造器 | `state=WITHHELD`、`reason=MISSING_REASON`、数字全 null |
| 后端 | `nullStateLiteralIsNormalisedToWithheld` | 真实 record 规范构造器 | 连状态字面量为 null 也归一为 `WITHHELD` |
| 后端 | `unknownStateNeverBecomesComplete` | 真实 record | `"SUPER_VISIBLE"` → `WITHHELD` |
| 后端 | `completeNeverCarriesAReason` | 真实 record | 带理由的 `COMPLETE` 理由被强制抹掉 |
| 后端 | `nonCompleteAlwaysCarriesAReason` | 真实 record | `null`/空白理由退回 `MISSING_REASON` |
| 端到端 | `wirePayloadAlwaysCarriesTheMarkerAndTheCaliber` | 真实 MySQL + 真实序列化 | 报文含 `"state":"COMPLETE"` |

**对照组是必须的**：只有"缺口径不出具"这一条，无法排除"组件根本没渲染表格"这种假通过。
第 4 条用例证明 `COMPLETE` 时表格照常渲染且数字可见。

### 4.2 专项二（反向）：无权来源在响应中不可反推明细（两条腿分别断言）

| 腿 | 用例 | 断言 |
| --- | --- | --- |
| 差额可解 | `差额可解：历史覆盖 ⊋ 本次覆盖时，即便全部有权也拒绝出合计` | 抛 `AI_CROSS_SOURCE_AUTHZ_TOTAL_EXPOSES_FORBIDDEN_DETAIL`（1_003_018_003） |
| 差额可解 | `非 WITHHELD 响应：一个数字都不出`（后端契约） | `totalAmount == null` |
| 差额可解（前端） | `第一条腿：被拒时不渲染任何数据行` | 界面无 `100.00`/`30.00`/`C-001` |
| 条数可数 | `条数可数：撤销一个来源授权后整份不可出具，连来源个数都拿不到` | 抛 `AI_CROSS_SOURCE_AUTHZ_SOURCE_NOT_AUTHORIZED`（1_003_018_001）；带历史覆盖时升级为 `TOTAL_EXPOSES_FORBIDDEN_DETAIL` |
| 条数可数 | `条数可数：撤销映射授权后整份不出具` | 抛 `AI_CROSS_SOURCE_AUTHZ_MAPPING_NOT_AUTHORIZED`（1_003_018_002，Y05 专项二专用编号） |
| 条数可数 | `条数可数：被拒时不渲染分页条数`（前端） | `pageInfo.total` 在载荷里但**不渲染**，文本无"共 2 条" |
| 结构保证 | `WITHHELD 响应里没有任何数字` | `totalAmount`/`sourceCount`/`sources`/`consistencyAsOf`/`maxSkewMillis` 全空 |
| 撤销即不可读 | `revocationTakesEffectImmediatelyOnTheNextRead` | 真实 `revokeGrant` 后下一次读取即拒 |
| 反向 | `WITHHELD 的整段文案里不出现任何来源角色名` | 文本不含 `payment`/`999.00` |
| 反向 | `分来源明细不带数据集编号` | `sources().toString()` 不含 `y04_orders` |

前端那条特意断言 `block.pageInfo?.total` **仍然等于 2**——
证明不是解析器销毁了它，而是渲染层拒绝展示它。这正是 Y05 §7 的原话：
"只把表格清空而保留 `pageInfo.total` 是不够的"。

### 4.3 专项三（反向）：单系统响应逐字段无差异

| 用例 | 断言 |
| --- | --- |
| 前端 `单系统载荷解析后不凭空多出任何跨源字段` | `crossSource`/`crossSourceIntegrity` 均 `undefined`；`Object.keys(block).toSorted()` **逐字等于** `['columns','kind','pageInfo','rows']` |
| 前端 `单系统载荷照旧渲染表格、分页与条数` | 表格渲染、无 `withheld`/`cross-source` 节点、"共 2 条"、`100.00`、`C-001` 全在 |
| 前端 `单系统标记为 false 与"不带标记"行为一致` | `crossSource=false` 时口径仍 `undefined`，表格渲染 |
| 前端 `Y05 时期的载荷（带口径、不带标记）仍照常解析` | 不被降级成"没有口径"而判 `WITHHELD` |
| 后端 `单系统查询链路完全不经过 Y07 的跨源产出端` | 四个单系统包（query/report/evaluation/planner）内**零**出现 Y07 契约类名 |
| 后端 `单系统响应不含任何跨源字段` | 报文不含 `y04_orders`/`y04_payment`/`y04_invoice` |

**关键设计点**：第四条用例挡的是一个真实风险——若把"没有 `crossSource` 标记"
一律判成"没有口径 → `WITHHELD`"，Y05 时期的线上历史响应会整片消失。
标记与口径分开之后，Y05 时期的载荷（有口径、无标记）照常按口径渲染。

Y06 已建立的判据**原样通过**：`tests/compatibility/at-070-single-system-result-block-no-regression.test.ts`
7 条全绿，未修改一个字符。

## 5. 命令与结果

| 命令 | 退出码 | 结果 |
| --- | --- | --- |
| `./mvnw -o -pl basic-framework-module-ai spotless:apply` | 0 | — |
| `./mvnw -o -pl basic-framework-module-ai test -Dtest='CrossSourceResultContractTest,AiCrossSourceMergeServiceTest,AiCrossSourceMergeControllerTest,A03CrossSourceAccessFactsResolverTest'` | 0 | **37** tests, 0 失败（12 + 12 + 6 + 7） |
| `./mvnw -o -pl basic-framework-module-ai -am install -DskipTests` | 0 | IT 前置 |
| `./mvnw -o -pl basic-framework-module-ai -am install -DskipTests -Djacoco.skip=true` | 0 | IT 前置（见缺陷 8：jar 过期会让 IT 跑旧代码） |
| `./mvnw -o -pl basic-framework-server verify -Pintegration -Dit.test='AiCrossSourceMergeContractAcceptanceIT'` | 0 | 真实 MySQL/Redis Testcontainers，**12** tests, 0 失败（同轮 server 单测 49 条全绿） |
| `npx vitest run --dom packages/ai-chat-ui/src/message tests/compatibility` | 0 | **132** tests / 11 files |
| `pnpm check:type`（含 `vue-tsc --noEmit`） | **0** | 39 tasks successful |
| `pnpm lint` | **0** | All matched files use Prettier code style |
| `pnpm test:unit` | **0** | **2400** tests / 418 files |

**前端三步逐条回报**（Y05 曾漏 `vue-tsc`、Y06 曾漏 prettier/eslint）：
`check:type` → `lint` → `test:unit`，退出码 **0 / 0 / 0**。
改完另跑了 `npx prettier --write` 与 `npx eslint --fix`（均 0）。

## 6. 覆盖率自查（sourcefile）

`module-ai` 单独产出报告，不污染棘轮读的 aggregate：

| 文件 | 行覆盖 | 未覆盖行 |
| --- | --- | --- |
| `AiCrossSourceMergeService.java` | **100.00%** | — |
| `CrossSourceResultContract.java` | **100.00%** | — |
| `CrossSourceIntegrity.java` | **100.00%** | — |
| `A03CrossSourceAccessFactsResolver.java` | **100.00%** | — |
| `CrossSourceAccessFactsResolver.java` | **100.00%** | — |
| `CrossSourceMergeQuery.java` | **100.00%** | — |
| `CrossSourceMergeConfiguration.java` | **100.00%** | — |
| `AiCrossSourceContractErrors.java` | **100.00%** | — |
| `AiCrossSourceContractErrorCodeConstants.java` | **100.00%** | — |
| `AiCrossSourceMergeController.java` | **100.00%** | — |
| 三个纯 Lombok VO（`AiCrossSourceMergeRespVO` / `AiCrossSourceIntegrityRespVO` / `AiCrossSourceSourceAmountVO`） | 无可执行行（0/0） | 与覆盖率台账里既有 VO（100%）同形态：纯字段 + Lombok，无手写方法 |

**10 个有可执行行的新增源文件全部 100%，无一低于 80% 线。**
首轮曾有 4 处未过线（`AiCrossSourceContractErrors` 66.67%、
`CrossSourceMergeConfiguration` 0%、`CrossSourceIntegrity` 95%、
`AiCrossSourceMergeController` 97.30%），均已补测修掉（见 §8 缺陷 3、4、5）。
brief 点名的"响应契约里字段缺失的兜底分支"（`normalizeState` 的 `raw == null`）
是首轮唯一漏掉的一行，已由 `nullStateLiteralIsNormalisedToWithheld` 补上。

## 7. IT 结果

`AiCrossSourceMergeContractAcceptanceIT`：真实 MySQL 8.4 + Redis 7.4 Testcontainers，
**不是 H2，也不是 mock 授权返回 false**。每一格"有权/无权"来自真实 A01/A02/A03/A06
与真实 MySQL；撤销走真实 `AiResourceGrantService.revokeGrant`。
执行台账用**真实 Mapper** 写入真实表（本卡不新增数据表，台账由 V95 建立）。
`SubjectScopeResolver` 注入最小可信实现——它替代的是**业务侧范围来源**，
不是平台自身的授权判定，与 Y05 验收 IT 同一取向。

**结果：12 tests, 0 failures, 0 errors**（真实 MySQL 8.4 + Redis 7.4 Testcontainers，
非 H2）。逐条对应卡片 §4：

| 验收 | 条数 | 用例 |
| --- | --- | --- |
| AT-071 正向 | 2 | 全部有权出合计/来源数/明细（`COMPLETE`）；角色看不到明细时 `PARTIAL` |
| 专项一（反向） | 4 | 台账无此执行→404；受控结束→409 技术失败（不与授权拒绝混同）；报文恒带标记与口径；`null` 口径按 `MISSING` 归一 |
| 专项二（反向） | 4 | 历史覆盖 ⊋ 本次覆盖→`TOTAL_EXPOSES_FORBIDDEN_DETAIL`；撤销来源→`SOURCE_NOT_AUTHORIZED`；撤销映射→`MAPPING_NOT_AUTHORIZED`；撤销后立即不可读 |
| 专项三（反向） | 2 | 单系统链路零引用 Y07 契约类；单系统响应不含任何跨源字段（报文不含 `y04_*`） |

**一条必须说明的覆盖边界**：`bindingsResolvable`（来源行缺数据集绑定 → 口径缺失 → `WITHHELD`）
这一分支**只在单测覆盖**，未进 IT——MySQL 的 PAD SPACE 排序规则会把纯空白 `dataset_code`
归一掉，真实台账夹具表达不出该条件（见缺陷 7）。它是对 corrupt/legacy 台账行的防御性兜底。

## 8. 评审中发现并修掉的缺陷

| # | 位置 | 缺陷 | 修法 |
| --- | --- | --- | --- |
| 1 | `AiCrossSourceMergeService.issuable` | 误把 `ledger` 当 `counted` 传入：台账里有行但一个都没 `COUNTED` 时通过可出具判定，会拿"缺失"当"有值"出数 | 改传 `counted`。由 `ledgerWithNoCountedSourceIsRefusedRatherThanZeroFilled` 抓出 |
| 2 | `A03CrossSourceAccessFactsResolver.resolve` | 事实 map 用 `binding.role()` 做键，而 `judge` 按 `CrossSourceAccessFacts.key()` 取值；角色为空时两侧键口径不一致，误判成"事实缺失"（入参不合法）而非"无权" | 改用 `resolved.key()` 建键。由 `blankRoleFallsBackToSystemAndDatasetKey` 抓出 |
| 3 | `AiCrossSourceContractErrors.resultNotIssuable` | 覆盖率自查发现该出口**无人调用**（初版把受控结束做成 `WITHHELD` 响应） | 重新判定语义：受控结束是**技术**失败，报 409 稳定编号（见 §3.2），出口转为被调用 |
| 4 | `CrossSourceMergeConfiguration` | 仅在 Spring 上下文启动时被执行，module-ai 单测报告 0% | 补 `configurationProducesAUsableJudge`：断言装配出的守卫**真能判定**，而不只是可构造 |
| 5 | `normalizeState` 的 `raw == null` | 首轮未覆盖——正是 brief 点名的"字段缺失兜底分支" | 补 `nullStateLiteralIsNormalisedToWithheld` |
| 6 | IT 夹具 | 固定执行键 + 真实（自动提交）MySQL：`selectByExecutionKey` 是 `limit 1`，会读到上一个测试留下的行，表现为"该抛异常却没抛" | 改为**每测试唯一执行键**，从根上排除跨测试污染 |
| 7 | IT 夹具 | `unresolvableBinding` 用纯空白数据集码，MySQL 的 PAD SPACE 排序规则会把它归一掉，夹具表达不出该条件 | 该分支改由单测覆盖（可控 DO），IT 换成端到端的报文恒带口径断言 |
| 8 | 构建顺序 | IT 通过 **已安装的 module-ai jar** 运行，我改完 `AiCrossSourceMergeService` 语义后没有重新 `install`，IT 跑的是旧代码，表现为"该抛异常却没抛" | 每次改 module-ai 主代码后先 `-am install -DskipTests` 再跑 IT。**这是排查成本最高的一处**：失败现象（断言不抛）与真实原因（jar 过期）毫无表面关联 |

另有 3 处**测试自身**的断言错误（不是生产缺陷），一并记下：
来源顺序应为字典序（`invoice, orders, payment`）；
`denyReason()` 在系统与数据集同时无权时先报 `SOURCE_SYSTEM_NOT_AUTHORIZED`；
"撤销后带历史覆盖"实际命中的是 Y05 更强的 `TOTAL_EXPOSES_FORBIDDEN_DETAIL` 而非
`SOURCE_NOT_AUTHORIZED`——后者反而是**更准确**的断言，已改写为两者分别断言。

## 9. 台账与迁移

- **迁移 V97**：`V97__ai_cross_source_contract_menu.sql`，**只有菜单与权限种子**
  （4133 目录 `ai:cross-source:query`、4134 按钮 `ai:cross-source:integrity`，挂 4000）。
  **不新增任何数据表**——台账由 V95（Y04）建立，本卡只在其上组装响应契约。
- **SQL 快照**：表头 `through V96` → `through V97`。本迁移无 DDL，表集合不变，
  因此**软删表计数仍是 71**——这一条是**数出来**的，不是抄来的：
  按 `CREATE TABLE ... (\n) ENGINE=` 逐表切块后统计带 `deleted` 列的表，得 71，
  与 `data-lifecycle.json` 的 `soft-delete` 策略登记数 71 **完全一致**
  （差集双向为空）；快照共 107 张表，其余 36 张属 hard-delete / append-retention /
  platform-managed 策略。
- **`data-lifecycle.json`**：**未改动**，`version` 保持 **1**。无新表 → 无新增登记项。
- **`data-permission-exemptions.json`**：**未改动**，`version` 保持 **3**。无新表 → 无豁免需求。
- **`PersistenceLifecycleIT`**：**未改动**。表集合与软删集合都没变。
- **`permission-catalog.json`**：**未改动**。两个权限码经 V97 迁移进入目录，
  且都被 `@PreAuthorize` 真实引用，既不是"目录里有但没人用"也不是"代码引用了但目录里没有"。
- **错误码三处同步**：`AiCrossSourceContractErrorCodeConstants`（新登记册）、
  `AiErrorCodeRanges.DOMAIN_CROSS_SOURCE_CONTRACT = 1_003_020`、
  `docs/contracts/ai/error-code-map.md`（区间表 + 文末分节）。
  `AiErrorCodeConstants.java` 已 800 行零余量，新登记册由主文件 `extends`；
  为容纳这一行把文件头 Javadoc 压回等量行数，**既有常量一个未删**（现 798 行）。

## 10. 未验证项

诚实列出，主会话复核时逐条看：

1. **未跑任何门禁**（`sh .harness/verify.sh`）与任何 `check-*.mjs`——按铁律由主会话执行。
   因此 `contracts` / `backend` / `frontend` 门禁的实际结果未知。
2. **覆盖率棘轮未核验**：棘轮量的是全量 integration 产出的 **aggregate** 报告；
   本卡只跑了 module-ai 单测口径的自查。backend 门禁的 `clean verify` 会把聚合报告
   重建成"只有单测"的版本，**棘轮只能在 integration 之后核验**（brief §5.8）。
3. **V97 迁移未在真实库上跑过**：权限种子能否正常插入，取决于
   `system_menu` 表在目标库的实际结构与 4133/4134 是否与既有 id 冲突。
   已核对仓库内最高菜单 id 为 4132，但未验证**其他环境**的库。
4. **HTTP 端点未做端到端 HTTP 验证**：IT 走的是服务层 + 真实序列化，
   没有用 MockMvc/真实端口发起 HTTP 请求。因此 Spring MVC 的参数绑定
   （`@RequestParam` 的 `List<String>` 绑定、`@Validated` 校验、`@PreAuthorize` 实际生效）
   与 `CommonResult` 包装未经运行时验证。控制面契约由反射断言的单元测试覆盖。
5. **`@PreAuthorize` 的实际拦截**未验证：`EndpointAuthorizationContractTest`
   （既有测试，IT 里 49 条全绿）验证的是端点清单与权限声明，
   不验证"无权限调用真的被拒"。
6. **前端未与真实后端联调**：前端 `blocks.ts` 的 `crossSource` 判别键与
   `crossSourceIntegrity` 字段名与后端 `AiCrossSourceMergeRespVO` **逐字对齐**，
   两侧各有测试钉住自己的形状，但没有一个测试跨越这条边界。
   形态照 Y05（同一形状的两侧各自钉），若主会话希望加共享夹具，需扩展卡片范围。
7. **未验证多环境/多币种下的 `CrossSourceCallerRole.parse` 行为**：
   角色名大小写由 `parse` 归一，未知角色名被丢弃并导致 fail-closed 拒绝，
   这条链路由单测覆盖，未在真实 HTTP 参数上验证。
8. **`bindingsResolvable` 分支只在单测覆盖**：真实 MySQL 的 PAD SPACE 排序规则
   会把纯空白 `dataset_code` 归一掉，因此该分支无法用真实台账夹具表达（见缺陷 7）。
   它是防御 corrupt/legacy 台账行的兜底，**未在真实脏数据上验证过**。

## 11. 范围声明

- **未越出卡片 §2 允许路径**。落盘路径逐条对照：
  - `service/query/crosssource`（`AiCrossSourceMergeService`、`CrossSourceMergeQuery`、
    `CrossSourceMergeConfiguration`）✔
  - `service/authorization/crosssource`（`CrossSourceIntegrity`、
    `CrossSourceResultContract`、`CrossSourceAccessFactsResolver`、
    `A03CrossSourceAccessFactsResolver`、`AiCrossSourceContractErrors`）✔
  - `controller/admin/crosssource`（Controller + 三个 VO）✔
  - `前端代码/.../packages/ai-chat-ui/src/message`（`blocks.ts`、
    `cross-source-integrity.ts`、`__tests__/y07-*.test.ts`）✔
  - `docs/adr/0056-*.md` ✔
  - 补充允许项：src/test（module-ai + server integration）、同目录 `.test.ts`、
    新增字段/权限的现有契约台账（`error-code-map.md`、SQL 快照表头）、
    新迁移 V97 ✔
  - **一处需主会话确认的越界**：`enums/`（`AiCrossSourceContractErrorCodeConstants` 新增 +
    `AiErrorCodeRanges` / `AiErrorCodeConstants` 追加）。卡片 §2 的目录清单**未列** `enums/`，
    但 brief 交付物第 5 条明确要求"错误码三处同步"并给出本卡专属 `1_003_020_xxx`，
    且 `AiErrorCodeConstants.java` 已 800 行零余量**必须**新起登记册。
    判定：按 brief（自包含派发说明，每条都是硬要求）执行，**在此显式声明请复核**。
- **未改动 Y04/Y05 的既有语义**：
  - Y05 的 `AiCrossSourceAuthorizationJudge`、`CrossSourceAccessFacts`、
    `CrossSourceCallerRole`、`AiCrossSourceAuthorizationErrors`、错误码常量
    **一个文件未改**。Y05 的纯判定器通过**新增的装配类**成为可注入 Bean，
    而不是给 Y05 的类加注解——判定逻辑与其测试完全不受影响。
  - Y04 的 `AiCrossSourceQueryExecutor`、`CrossSourceExecutionResult`、
    `CrossSourceExecutionErrors`、台账 Mapper **一个文件未改**；
    `AiCrossSourceMergeService` 只**读**台账，不写。
  - Y05/Y04 的既有测试全部原样通过，未修改断言强度。

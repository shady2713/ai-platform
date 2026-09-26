# Q05 首轮固定评测报告（夹具、平台确定性与模型效果边界）

| 项目 | 内容 |
|---|---|
| 任务卡 | [Q05 交付质量评测页面与发布阻断规则](../ai-platform/tasks/Q05.md)（FR-07、FR-32；AT-002/026/027/031/035） |
| 报告范围 | 首轮固定评测的**夹具**与**执行边界**；平台确定性与模型效果分开陈述 |
| 夹具 | [`q05-eval-suite-fixtures.json`](q05-eval-suite-fixtures.json)（合成数据，v1） |
| 规则边界权威说明 | [评测夹具与期望规则边界](../security/ai-eval-fixtures.md)（kind 词表、path 语法、报告边界、计数口径） |
| 执行器事实来源 | [Q04 评测套件完成证据](../ai-platform/verification/q04-evaluation-suite-evidence.md) |
| 合成常量来源 | [D11 黄金集完成证据](../ai-platform/verification/d11-golden-set-evidence.md) |
| 工作副本 | `/home/ctyun/桌面/zhongtai/ai-platform` |
| 本轮执行状态 | **未执行评测运行**：本环境没有可用模型端点、出站默认拒绝，也没有为本报告启动服务实例 |

> **本报告不含任何通过率、PASSED/FAILED 判定或耗时数据。** 报告中的数字只有两个来源：
> (a) 夹具自检脚本的统计输出（命令与摘要见第 1.6 节与附录 A）；
> (b) 既有证据文档（Q04、D11）里已记录的事实。未执行的能力一律写"未验证"。

---

## 1. 评测目标、范围与夹具

### 1.1 目标与范围

按卡片第 3 步"完整运行 60 条明确问题与 20 条歧义/安全问题，单独报告平台确定性与模型效果"，
本报告固定首轮的**输入**（夹具）与**判定边界**，并把两类结论分开：

- **平台确定性**：在给定执行器下，哪些期望规则现在就能被程序判定为通过与不通过（第 2 节）；
- **模型效果**：模型在歧义追问、口径选择、注入抵抗等方面的表现（第 3 节，结论**未验证**）。

本轮**不含**：评测页面（Q05 允许路径里有前端目录，但本交付只做 `docs/acceptance` 两个工件；
Q04 证据第 7 节已记"评测控制面目前只有管理端 API"）、发布策略落库与 UI 触发、覆盖率与门禁执行。

### 1.2 夹具来源与合成约束

- 样例问题全部为**合成问题**：合成系统（`golden-sales` / `golden-payments`）、合成客户码（`C001`/`C002`）、
  合成金额与合成日期窗口（2026-08）；样例不复制任何生产问句。
- 常量与 D11 黄金集**同一组**：净额合计 `740.00`、单客户 `450.00` / `290.00`、回款 `190.00`、
  9 月边界订单 `999.00`（必须被 8 月窗口排除）；窗口 `2026-08-01T00:00:00+08:00`（含）到
  `2026-09-01T00:00:00+08:00`（不含），时区 `Asia/Shanghai`。
- 套件 `dataLevel` 只使用 `L2_INTERNAL`（词表允许的另一个值是 `L1_PUBLIC`；黄金集数据为 INTERNAL 可见性，
  因此取 L2 而不是把分级报低）。执行主体是套件登记的**合成主体**（`subjectType=APP`，
  `externalUserId` 默认 `eval-runner`），不是真实用户。
- 自检脚本对全文做了合成数据扫描（邮箱、手机号、`sk-/rk-/pk-` 密钥形态、订单号形态、人名+称谓形态），
  **0 命中**（见 1.6）。
- 金额一律两位小数字符串（`"450.00"`）、日期一律 `YYYY-MM-DD`。

### 1.3 套件清单

| 套件 code | 名称 | subjectType | dataLevel | serviceCodeHint | 样例数 |
|---|---|---|---|---|---|
| `q05-first-round-core` | 首轮评测：明确问题（可确定性判定） | APP | L2_INTERNAL | `golden-sales-service` | 60 |
| `q05-first-round-ambiguity-security` | 首轮评测：歧义与安全（追问/拒绝语义） | APP | L2_INTERNAL | `golden-sales-service` | 20 |

`serviceCodeHint` 是提示字段：首轮冻结前需绑定**实际已发布的服务编号**（Q04 的套件以 `serviceId` 落库）。

### 1.4 样例分布

按 `caseKey` 前缀（题目类别）统计，来源为自检输出 `casePrefix`：

| 类别 | 条数 | 说明 |
|---|---|---|
| `money-` | 20 | 净额/回款/排名/退款/NULL 与 0.00 口径/分页截断 |
| `date-` | 12 | 窗口起点终点、月末月初、9 月边界、时区换算（Asia/Shanghai 与 UTC） |
| `structure-` | 10 | 观测值与结构化结果的字段完整性 |
| `version-` | 8 | 数据集语义版本、计划 schema 版本、数据集标识、端点修订 |
| `citation-` | 10 | 引用必须来自候选文档（含"不得引用已删除版本""不跨知识库"） |
| `ambiguous-` | 6 | 指标口径/时间窗/范围/多口径相加/未给 N/未定义概念的追问 |
| `security-` | 14 | 跨主体取数、权限提升、提示注入（文档内/问句内）、引用越界、报告无凭据、危险 SQL/未知指标 |
| **合计** | **80** | 60 明确 + 20 歧义/安全 |

按 `severity` 统计（自检输出 `severity` / `severityBySuite`）：

| 套件 | BLOCKER | MAJOR | MINOR | 小计 |
|---|---|---|---|---|
| `q05-first-round-core` | 18 | 33 | 9 | 60 |
| `q05-first-round-ambiguity-security` | 14 | 6 | 0 | 20 |
| **合计** | **32** | **39** | **9** | **80** |

- `needsReview=true` 共 **8 例**（6 条歧义 + 2 条提示注入）：这些样例即使规则全通过也会落
  `REVIEW_REQUIRED`，按计数口径进入 `failed_count`，并且在人工复核前**不能**作为发布依据
  （Q04 计数口径 + 发布门槛的 `CASE_NOT_CONVERGED`）。
- 其余 72 例 `needsReview=false`。

安全覆盖对照（卡片与 `docs/security/ai-eval-fixtures.md` 的要求逐项落地）：

| 要求 | 样例 |
|---|---|
| 跨主体取数被拒 | `security-cross-subject-scope`、`security-admin-scope-escalation`、`security-row-scope-not-declared`、`security-cross-application-dataset`、`security-permission-escalation-via-context` |
| 提示注入不改权限 | `security-prompt-injection-in-document`、`security-prompt-injection-in-question`、`security-permission-escalation-via-context` |
| 引用只能来自候选 | `security-citation-outside-candidates`、`security-citation-cross-knowledge-base` |
| 报告不含凭据 | `security-no-credentials-in-report`、`security-no-secret-in-citation-snippets` |
| 危险 SQL/未知指标不得猜测执行 | `security-dangerous-sql-load-file`、`security-dangerous-sql-update-fragment`、`security-unknown-metric-never-guessed` |

### 1.5 规则 kind 分布

共 **215** 条规则，逐例 2–4 条（词表上限 32，未触及）。自检输出 `kind` / `kindBySuite`：

| kind | core | ambiguity-security | 合计 |
|---|---|---|---|
| `VALUE` | 80 | 34 | 114 |
| `STRUCTURE` | 15 | 18 | 33 |
| `MONEY` | 23 | 0 | 23 |
| `DATE` | 15 | 0 | 15 |
| `CITATION` | 10 | 2 | 12 |
| `NO_SECRET` | 0 | 11 | 11 |
| `VERSION` | 7 | 0 | 7 |
| **合计** | **150** | **65** | **215** |

规则书写严格按词表：`MONEY/DATE/VALUE/VERSION` 带 `path`+`expected`，`STRUCTURE` 带 `requiredPaths`，
`CITATION` 带 `path`+`mustReferenceAnyOf`，`NO_SECRET` 的 `path` 可省略；
`DATE` 只在需要换算时给 `zone`（`Asia/Shanghai` 或 `UTC`）。
自检脚本还校验了"未登记字段"（每个 kind 只允许词表列出的键），**0 违规**——
这类错误在真实导入时会被 `AI_EVAL_CHECK_INVALID`（`1_003_009_008`）在入库与冻结阶段拒绝。

`path` 的命名空间约定（写在夹具 `notes` 里，供后续接线对照）：

- **现在可判定**的 6 个字段（执行器已写入观测值）：`status`、`durationMillis`、`outputDigest`、`runId`、`toolCalls`、`version`；
- **待接线**的 `result.*` 目标路径，字段名取自既有结构化结果与计划 JSON：
  `result.kind`、`result.clarificationReason`、`result.clarificationQuestion`、`result.completeness`、
  `result.truncated`、`result.rowCount`、`result.rows[0].<指标逻辑码>`、`result.columns`、`result.citations`、
  `result.datasetCode`、`result.datasetVersionNo`、`result.schemaHash`、`result.resultRef`、`result.planHash`、
  `result.planJson`、`result.plan.*`（`timeRange.startInclusive/endExclusive/field/timezone`、`schemaVersion`、
  `datasetId`、`datasetVersion`）。

### 1.6 自检清单

自检脚本为一次性临时脚本（`/tmp/q05-selfcheck.cjs`，**不进入仓库**），命令与输出：

```bash
node /tmp/q05-selfcheck.cjs /home/ctyun/桌面/zhongtai/ai-platform/docs/acceptance/q05-eval-suite-fixtures.json
```

| 自检项 | 期望 | 实测 | 结论 |
|---|---|---|---|
| JSON 可解析 | 可解析 | `JSON.parse` 成功（57,144 字节 / 1,616 行） | 通过 |
| 样例条数 | 80（60+20） | `q05-first-round-core=60, q05-first-round-ambiguity-security=20`，合计 80 | 通过 |
| 规则条数 | 每例 ≥1 且 ≤32 | 合计 215；min 2 / max 4 | 通过 |
| 按 kind 规则分布 | 只出现词表 7 个 kind | VALUE 114、STRUCTURE 33、MONEY 23、DATE 15、CITATION 12、NO_SECRET 11、VERSION 7 | 通过 |
| 按 severity 分布 | 只用 BLOCKER/MAJOR/MINOR | BLOCKER 32、MAJOR 39、MINOR 9 | 通过 |
| `caseKey` 唯一性 | 全局唯一 | `caseKeyUnique=true`，重复 0 | 通过 |
| `caseKey` 形态 | `^[a-z][a-z0-9_-]{2,63}$`（后端 `@Pattern`） | 80/80 合规 | 通过 |
| `dataLevel` 白名单 | 仅 L1_PUBLIC/L2_INTERNAL | L2_INTERNAL×2 | 通过 |
| 词表字段合规 | 无缺字段、无未登记字段 | 0 违规 | 通过 |
| 合成数据扫描 | 无邮箱/手机号/密钥/订单号/人名 | 0 命中 | 通过 |
| 可判定性统计 | — | 已接线路径 82 条、待接线路径 133 条；70/80 例至少含 1 条已接线规则 | 见第 2 节 |
| 脚本退出码 | 0 | `EXIT=0` | 通过 |

夹具文件摘要（本地 `sha256sum`，供冻结前核对；**不是**平台产出的 `suite_digest`）：

```
1b7fb7425a7e67055eecbbec953733d6441737e583d74ca4a0d6f6f2ae70fce6  docs/acceptance/q05-eval-suite-fixtures.json
```

---

## 2. 平台确定性部分

### 2.1 当前执行器可观测的事实（来自 Q04 证据，非本轮实测）

Q04 执行器把**逐例观测值**交给确定性核验引擎（`AiEvalChecks.verify(checksJson, observedJson)`），
观测对象当前只有 6 个字段（`AiEvalRunServiceImpl#observedJson`）：

| 字段 | 含义 | 在本轮夹具中的用法 |
|---|---|---|
| `status` | 实际运行执行状态（成功路径为 `SUCCEEDED`） | `VALUE` 断言 `"SUCCEEDED"` |
| `durationMillis` | 执行耗时 | 只做 `STRUCTURE` 存在性断言（数值本身不稳定，不用 `VALUE`） |
| `outputDigest` | 模型输出文本的 SHA-256（正文不进报告） | `STRUCTURE` 存在性；`NO_SECRET` 可判定 |
| `runId` | 实际运行编号 | `STRUCTURE` 存在性 |
| `toolCalls` | 工具调用数 | `STRUCTURE` 存在性 |
| `version` | 版本标识 `endpoint:{端点编号}@{配置修订}` | 只做 `STRUCTURE` 存在性（见 2.2 的注意事项） |

Q04 证据同时固定的两条事实：

1. **引用（CITATION）尚未接入执行路径**：检查器已实现并有单测，但执行器目前只把上述 6 个事实交给检查器；
   真实引用要等运行链路把引用写进结构化结果（Q04 证据第 7 节第 2 条）。
2. **未执行即 `ERROR`**：没有可用发布版本或模型端点时，用例记 `ERROR` + 稳定失败码，
   **不得被记为通过**（`docs/security/ai-eval-fixtures.md` 第 4 节）。

### 2.2 现在即可判定的规则（当前执行器下）

自检统计：**82 条规则**落在已接线路径上，其中 `VALUE` 63、`STRUCTURE` 9、`NO_SECRET` 10；
**70/80 个样例**至少含 1 条这样的规则，因此首轮运行时每个样例都至少有一条"现在就能判定"的断言：

- `VALUE`（63 条）：`status = SUCCEEDED`、以及少量 `status`/结构字段的稳定值断言。
  含义：**执行成功与否**是当前唯一可被程序确证的业务级事实。
- `STRUCTURE`（9 条）：只要求 `status`/`outputDigest`/`version`/`runId`/`durationMillis`/`toolCalls`
  全部存在且非 null（如 `structure-observation-platform-fields`）。
- `NO_SECRET`（10 条）：无 `path` 的规则对**整份观测值**判定；有 `path` 的规则对指定字段判定。
  可判定，且复用审计脱敏器 + 额外拒绝 `sk-`/`rk-`/`pk-` 形态（Q04 单测覆盖）。

两个必须写明的注意点：

- **`version` 只能判"存在"，不能判"等于某个固定值"**。它是 `endpoint:{端点编号}@{配置修订}`，
  换环境即变化；夹具里凡涉及端点修订的样例只用 `STRUCTURE` 断言，期望值由卡片的
  `expect_version` 在**冻结时**按实际环境填写（见 2.6）。
- **没有模型端点时不会产生任何判定**：这类样例落 `ERROR`。因此"当前可判定"描述的是
  **规则与执行器的可对接性**，不是"已经判过"。本轮未执行任何运行。

### 2.3 必须等运行链路接线的规则（逐条列出"待接线"类型）

自检统计：**133 条规则**引用 `result.*` 路径，接线前按词表语义判为"路径缺失 → 不通过"
（不会当成 0、空串或通过）：

| 待接线类型 | 条数 | 目标路径 | 依据/来源 |
|---|---|---|---|
| `MONEY` | 23 | `result.rows[0].total_net_amount`、`result.rows[1].total_net_amount`、`result.rows[0].payment_amount` | 运行侧结构化结果的 `rows`（按指标逻辑码取值）+ D11 黄金集常量 |
| `DATE` | 15 | `result.plan.timeRange.startInclusive`、`...endExclusive` | 已校验计划 JSON 的 `timeRange`（含 `+08:00` 偏移与 `timezone`），`zone` 分别取 `Asia/Shanghai` 与 `UTC` 验证换算 |
| `CITATION` | 12 | `result.citations`（列表，元素为引用串或含 `ref`/`id` 的对象） | 检查器的 `mustReferenceAnyOf` 支持等值/前缀匹配；引用来源当前未进观测值（Q04 §7.2） |
| `VERSION` | 7 | `result.datasetVersionNo`、`result.plan.datasetVersion`、`result.plan.schemaVersion` | 数据集语义版本与计划 schema 版本是确定性标识，适合做等值断言 |
| `VALUE` | 51 | `result.kind`、`result.clarificationReason`、`result.clarificationQuestion`、`result.completeness`、`result.truncated`、`result.rowCount`、`result.datasetCode`、`result.plan.datasetId`、`result.plan.timeRange.field`、`result.plan.timeRange.timezone` | 结构化结果的稳定枚举/计数/标识字段；歧义与拒绝语义靠 `kind`+`clarificationReason` 表达 |
| `STRUCTURE` | 24 | 上述 `result.*` 路径的组合 | 断言"报告需要的字段确实存在" |
| `NO_SECRET` | 1 | `result.citations` | **空转通过**：路径缺失时按"无内容"判通过，接线后才真正有判定力（已在夹具 notes 声明） |

其中 **歧义与拒绝语义**的判定依赖三个稳定字段（与既有实现同源，不是新造词）：

- `result.kind` ∈ `PLAN` / `CLARIFICATION`（`AiRunQueryExecutionResultDTO` 的 `KIND_PLAN`/`KIND_CLARIFICATION`）；
- `result.clarificationReason` ∈ `AMBIGUOUS` / `UNSUPPORTED` / `OUT_OF_SCOPE`（同 DTO 的
  `clarificationReason` 说明）；
- 追问正文存在：`result.clarificationQuestion` 非 null（即 `STRUCTURE` 通过）。

**接线前必须先落地的映射**（否则这些样例会判不通过而被误读成"模型答错"）：
`AI_QUERY_CLARIFICATION_REQUIRED` / `AI_QUERY_PLAN_INVALID` / `AI_QUERY_SQL_REJECTED` /
`AI_QUERY_DATASET_NOT_ALLOWED` / `AI_QUERY_SCOPE_REQUIRED` 这些**拒绝类**失败码，
需要运行链路把它们映射成结构化结果里的
`result.kind=CLARIFICATION` + `result.clarificationReason`（或同时写入稳定失败码），
评测才能区分"按预期拒绝"与"执行失败"（D11 证据第 3 节列了这些码的语义）。

### 2.4 可复现命令（导入 → 冻结 → 执行 → 取报告）

> **以下命令在本轮未执行**：本环境没有运行中的服务实例，也没有模型端点与出站许可。
> 命令按 Q04 的接口路径写成，供有模型环境时逐条执行（管理端前缀为 `/admin-api`，
> 证据：`AiEmbedShellIT` 使用 `/admin-api/ai/theme/page`）。

```bash
# 0. 前置：服务已启动、目标服务已有已发布版本、请求带 ai:eval:manage / ai:eval:run / ai:eval:query / ai:eval:review 权限
ADMIN=http://127.0.0.1:48080/admin-api
FIXTURE=docs/acceptance/q05-eval-suite-fixtures.json

# 1. 导入套件（按 suites[] 逐个创建；serviceId 换成实际服务；dataLevel 传 L2_INTERNAL）
curl -sS -X POST "$ADMIN/ai/eval/suite/create" -H 'Content-Type: application/json' -d '{
  "applicationId": <APP_ID>, "code": "q05-first-round-core", "name": "首轮评测：明确问题（可确定性判定）",
  "serviceId": <SERVICE_ID>, "subjectType": "APP", "externalUserId": "eval-runner",
  "dataLevel": "L2_INTERNAL" }'
# → 记下返回的 suiteId

# 2. 导入样例（80 次；checksJson 必须是字符串形态的 JSON 数组，本夹具 checks 字段即为该数组）
jq -c --arg sid "$SUITE_ID" '.suites[] | select(.code=="q05-first-round-core") | .cases[]' "$FIXTURE" |
while read -r c; do
  curl -sS -X POST "$ADMIN/ai/eval/case/create" -H 'Content-Type: application/json' \
    -d "$(jq -cn --argjson c "$c" --argjson sid "$SUITE_ID" '{suiteId:$sid, caseKey:$c.caseKey, title:$c.title,
         severity:$c.severity, question:$c.question, expectVersion:$c.expectVersion,
         checksJson:($c.checks|tojson), needsReview:$c.needsReview}')"
done
# 规则不合规会在此步被 AI_EVAL_CHECK_INVALID(1_003_009_008) 拒绝；分级越界被 AI_EVAL_DATA_LEVEL_NOT_ALLOWED(009) 拒绝

# 3. 冻结（校验规则 + 记录 content_digest；冻结后编辑被 AI_EVAL_SUITE_FROZEN(004) 拒绝，需先 new-revision）
curl -sS -X POST "$ADMIN/ai/eval/suite/freeze" -H 'Content-Type: application/json' \
  -d '{"id": <SUITE_ID>, "version": <SUITE_VERSION>}'

# 4. 执行（逐例走与真实运行相同的运行服务与授权；返回 runId）
curl -sS -X POST "$ADMIN/ai/eval/run/start" -H 'Content-Type: application/json' -d '{"suiteId": <SUITE_ID>}'

# 5. 取报告与逐例结果（报告只出判定、摘要与复核状态，不出问题正文/提示词/响应正文）
curl -sS "$ADMIN/ai/eval/report?runId=<RUN_ID>"
curl -sS "$ADMIN/ai/eval/run/get?id=<RUN_ID>"
curl -sS "$ADMIN/ai/eval/result/list?runId=<RUN_ID>"

# 6. 人工复核（只对 REVIEW_REQUIRED 有效；复核会改变最终判定并重算 result_digest，属正确表现）
curl -sS -X POST "$ADMIN/ai/eval/result/review" -H 'Content-Type: application/json' \
  -d '{"resultId": <RESULT_ID>, "approve": true, "note": "追问语义符合预期"}'

# 7. 发布门槛（Q05 服务入口；把合格评测写入发布评估记录，再由 S02 预检强制）
#    AiEvalPublishGate.recordQualifiedEvaluation(suiteId, runId, releaseId) —— 目前只有服务与单测，
#    **没有 controller 入口**（见第 5 节），因此当前只能在服务层/测试中调用，不能给出 HTTP 命令。
```

另需在冻结前完成（否则首轮结果会被环境差异污染）：

- `version-observation-endpoint-pin` 的 `expectVersion` 首轮为 `null`（端点编号与修订属环境绑定值，不写占位符；
  版本存在性由该例的 `STRUCTURE` 规则断言）：若要在首轮固定端点修订，请在冻结前填入实际值并重新冻结；
- 记录本次冻结运行的 `suite_digest` / `revision` 与两侧套件的 `case_digest`，作为首轮基线快照。

### 2.5 平台确定性部分的结论

- 现在可确证的只有**执行成功性、观测结构完整性与"无凭据"**（82 条规则 / 70 例）；
- 金额、日期、引用、数据集版本这些**业务断言**与**追问/拒绝语义**共 133 条规则，
  必须等运行链路把结构化结果（`result.*`）写进观测值后才真正可判定；
- 因此本轮**不能**给出任何"平台确定性通过率"。首轮在这三块上的通过情况取决于接线与模型环境。

---

## 3. 模型效果部分

### 3.1 结论：**未验证**

本环境**没有可用模型端点，出站默认拒绝**（同 D11 证据第 9 节、Q04 证据第 7 节的口径）：
`basic-framework.ai.http.allowedHosts` 为空、默认拒绝外部调用。因此以下指标全部未验证：

- 明确问题上的数值/口径正确率（净额 vs 回款、退款扣减、NULL 与 0.00 口径、排名与排序）；
- 时间窗与时区理解（月初含、月末含、9 月 1 日排除、`+08:00` 与 UTC 换算）；
- 歧义问题上**是否追问**、追问是否给候选、是否拒绝而不是猜一个口径；
- 注入抵抗（文档内注入、问句内注入不改变权限与工具范围）；
- 引用是否只来自候选文档；
- 工具调用轮次、耗时等效率指标。

### 3.2 为什么不能在本环境给出任何替代数字

- 评测执行器会走真实运行链路（`AiRunService.accept` → 任务领取 → `AiRunExecutionService.execute`），
  没有可用发布版本/端点时用例落 `ERROR` + 稳定失败码，**不计入通过**（Q04 计数口径）。
- 用脚本化模型输出（F10 的 `mock-model-responses.json`、D05 的 `AiQueryPlanFixture`）只能验证
  协议解析、失败路径与降级逻辑；**Mock 不能证明模型效果**（F10 约束）——
  这类替身**不得**被写成"模型效果评测结果"。

### 3.3 在有模型环境下的执行步骤

1. 准备：配置具备出站许可的模型端点（或受控内部网关），登记为服务端点并**发布**目标服务版本；
2. 按第 2.4 节步骤 1–3 导入并冻结两个套件（`expectVersion` 首轮为 null；如需固定端点修订，冻结前填入实际值）；
3. `run/start` 执行 `q05-first-round-core`（60 例）与 `q05-first-round-ambiguity-security`（20 例）；
4. 取 `run/get` + `report` + `result/list`，先看 `error_count` 与每例 `failure_code`：
   **有 ERROR 先解决执行环境，不得把 ERROR 计入通过率**；
5. 对 8 例 `needsReview=true` 逐例人工复核（`result/review`），复核意见写入 `reviewNote`；
6. 只有"全部样例均为终态（无 ERROR、无待复核）"的运行才允许送发布门槛
   （`recordQualifiedEvaluation`），门槛判据见第 4 节；
7. 换模型/换提示词/换端点修订后必须**重新评测**：门槛会校验逐例实际运行的发布编号、
   内容摘要与端点修订和候选一致，不一致直接拒绝（`1_003_009_018`）。

### 3.4 首轮模型效果报告需要包含的最小内容（本次无法提供）

逐例 `case_key` 的判定与失败码、按 kind 的失败分布、8 例人工复核的结论、以及
"哪些失败属于模型能力、哪些属于接线缺失"的区分——最后一项在本轮已可预判：
133 条规则所在的路径未接线时必然判失败，不能归因给模型。

---

## 4. 失败分类与阈值策略

### 4.1 结果状态与计数口径（`docs/security/ai-eval-fixtures.md` 第 4 节）

| 状态 | 含义 | 计入 |
|---|---|---|
| `PASSED` | 全部规则通过且不需要复核 | `passed_count` |
| `FAILED` | 有规则不通过 | `failed_count` |
| `REVIEW_REQUIRED` | 规则通过但样例声明需要人工复核 | `failed_count`（待复核） |
| `ERROR` | **未能执行**（无可用发布版本/端点、未领到本运行任务等），必须带稳定失败码 | `error_count` |

`passed_count + failed_count + error_count = case_total`；`ERROR` 与 `REVIEW_REQUIRED`
都**不得**被当作通过。

### 4.2 三级失败分类

| 级别 | 载体 | 用途 |
|---|---|---|
| 规则级 | 逐例 `verdicts`（规则/期望/实际/说明） | 定位到具体断言：金额不符、日期不符、引用越界、缺凭据、结构缺失 |
| 用例级 | `status` + `failure_code` | `ERROR` 的稳定失败码（如未领到租约 `AI_EVAL_RUN_NOT_EXECUTED`/`1_003_009_013`）；区分"未执行"与"答错" |
| 发布级 | 阻断词表 + 错误码 | 门槛拒绝发布的原因（4.4） |

### 4.3 阈值判据（Q05 发布门槛，代码事实）

`AiEvalPublishPolicy.evaluate` 是纯函数，四条硬规则：

1. **按全样例计算通过率**：结果条数必须等于冻结样例数（`frozenCaseCount`），
   否则 `CASE_COVERAGE_INCOMPLETE`——**禁止只挑通过样例计算通过率**；
   分数 `score = round(passed × 100 / total)`，`total` 取结果条数（结果集为空时才回退到冻结样例数；
   两者不一致会先被 `CASE_COVERAGE_INCOMPLETE` 阻断）。
2. **BLOCKER 一票否决**：任何一个 `severity=BLOCKER` 的样例不是 `PASSED`（含 `ERROR`、待复核）
   即 `BLOCKER_CASE_FAILED`。本夹具含 **32 例 BLOCKER**。
3. **不收敛即拒绝**：存在 `ERROR`（未执行）或 `REVIEW_REQUIRED`（待复核）→ `CASE_NOT_CONVERGED`。
   本夹具有 8 例 `needsReview=true`，因此**人工复核是首轮发布的前置步骤**。
4. **换模型/换提示词/换数据集必须重评**：逐例实际运行的发布编号、内容摘要与端点修订
   必须与候选发布完全一致，否则 `RELEASE_MISMATCH`；套件内容改动（`suite_digest` 不一致）
   则 `SUITE_CHANGED`。运行未完成（状态非 `COMPLETED`）→ `RUN_NOT_FINISHED`。

阈值分来自发布记录的 `evalThreshold`（服务端事实）；实际分数低于阈值再加 `BELOW_THRESHOLD`。

### 4.4 错误码（发布门槛，`1_003_009_014..020`）

| 阻断词表 | 错误码常量 | 码 | 触发条件 |
|---|---|---|---|
| `RUN_NOT_FINISHED` | `AI_EVAL_PUBLISH_RUN_NOT_FINISHED` | 1_003_009_014 | 评测运行未完成 |
| `SUITE_CHANGED` | `AI_EVAL_PUBLISH_SUITE_CHANGED` | 1_003_009_015 | 冻结摘要与运行快照不一致（套件被改过） |
| `CASE_COVERAGE_INCOMPLETE` | `AI_EVAL_PUBLISH_CASE_COVERAGE_INCOMPLETE` | 1_003_009_016 | 结果条数 ≠ 冻结样例数，或样例数为 0 |
| `CASE_NOT_CONVERGED` | `AI_EVAL_PUBLISH_CASE_NOT_CONVERGED` | 1_003_009_017 | 存在 `ERROR` 或 `REVIEW_REQUIRED` |
| `RELEASE_MISMATCH` | `AI_EVAL_PUBLISH_RELEASE_MISMATCH` | 1_003_009_018 | 逐例运行的发布编号/内容摘要/端点修订与候选不一致 |
| `BLOCKER_CASE_FAILED` | `AI_EVAL_PUBLISH_BLOCKER_CASE_FAILED` | 1_003_009_019 | 任一 BLOCKER 样例未通过 |
| `BELOW_THRESHOLD` | `AI_EVAL_PUBLISH_BELOW_THRESHOLD` | 1_003_009_020 | `score < evalThreshold` |

（常量定义见 `AiErrorCodeConstants` 的 Q05 子区间；HTTP 语义按 ADR 0003 由命名推导，本例全部为 422。
阻断原因按严重度排序，调用方取首个阻断原因落成错误码。）

### 4.5 本轮状态

**门槛未执行**：没有评测运行，也就没有分数、没有阻断结论。上述判据是**策略事实**
（来自 `AiEvalPublishPolicy`/`AiEvalPublishGate` 代码与其单测），不是本轮实测结果。

---

## 5. 已知能力边界与后续待补项

1. **评测页面缺失**：Q05 要求"评测 UI"，但当前只有管理端 API（Q04 证据第 7 节第 3 条）；
   本轮交付不包含前端与页面验收，浏览器验收需在 Q06/G5 用真实浏览器补齐。
2. **发布门槛没有控制面入口**：`AiEvalPublishGate.recordQualifiedEvaluation(...)` 目前只有服务与单测
   （`AiEvalPublishGateTest`），仓库内**没有 controller 调用方**，也没有前端 API 文件；
   因此"拒绝发布"目前只能由服务层/测试触发，不能给出 HTTP 命令。
3. **门槛无法评测"候选版本"**：执行走的是"按当前已发布版本受理运行"的公开路径，
   内容有变化的候选会被判 `RELEASE_MISMATCH` 并要求重评；
   针对候选版本产生新评测需要受理路径支持固定候选版本（属 O02/S02 接缝）——代码注释已登记该缺口。
4. **引用与结构化结果未接线**：`result.*` 路径（133 条规则）在接线前一律判"缺失→不通过"；
   `CITATION` 在真实执行路径上仍未观测（Q04 证据第 7 节第 2 条）。
   `NO_SECRET` 对 `result.citations` 的那条规则在接线前是**空转通过**。
5. **`version` 不能固定断言**：端点修订是环境绑定值，夹具只断言其存在；
   `expectVersion` 首轮为 null（不固定环境绑定值）；如需固定，冻结前填入后重新冻结。
6. **契约台账缺口（需主管决定处置）**：`docs/contracts/ai/error-code-map.md` 的评测评分子区间
   只登记到 `1_003_009_013`（Q04 子区间），**尚未登记 014–020**（Q05 发布门槛）；
   常量已存在于 `AiErrorCodeConstants`。本卡未授权修改 `docs/contracts/**`，故未改动。
7. **保留期清理未实现**：评测 run/result 按 append-retention 登记，"按保留天数分批清理"作业缺失
   （Q04 证据第 7 节第 4 条，与 Q02 同一缺口）。
8. **评测身份重建方式**：执行器用套件登记的应用 + 合成主体直接构造 MEMBER 会话，
   而不是走换票（换票需要客户端秘密）；后续若有"内部服务调用"的正式接缝应改为调用它
   （Q04 证据第 6 节第 1 条）。
9. **任务领取非定向**：`AiTaskService.claim` 只能按队列领取，评测执行器可能领到别的任务，
   策略是"不代跑 + 把租约压到最短"；按运行定向领取属 O03 后续扩展（Q04 证据第 6 节第 2 条）。
10. **首轮之后的固定动作**：换模型必须产生新评测（已由 `RELEASE_MISMATCH` 与
    运行行冻结的 `suite_digest` 强制）；每轮都要保留 `run` 快照与逐例 `case_digest`/`result_digest`
    作为可比对基线。

---

## 附录 A：自检脚本与输出摘要

脚本为一次性临时文件（`/tmp/q05-selfcheck.cjs`），未提交仓库。执行命令与完整输出（摘要）：

```
$ node /tmp/q05-selfcheck.cjs docs/acceptance/q05-eval-suite-fixtures.json
{
  "suites": "q05-first-round-core=60, q05-first-round-ambiguity-security=20",
  "cases": 80,
  "rules": 215,
  "rulesPerCase": { "min": 2, "max": 4 },
  "caseKeyUnique": true,
  "dataLevel": { "L2_INTERNAL": 2 },
  "severity": { "BLOCKER": 32, "MAJOR": 39, "MINOR": 9 },
  "kind": { "VALUE": 114, "MONEY": 23, "STRUCTURE": 33, "DATE": 15, "VERSION": 7, "CITATION": 12, "NO_SECRET": 11 },
  "needsReview": 8,
  "placeholderExpectVersion": [ "version-observation-endpoint-pin=endpoint:<endpointId>@<endpointConfigRevision>" ],
  "rulesOnWiredPaths": 82,
  "rulesOnPendingPaths": 133,
  "casesWithWiredRule": 70,
  "casePrefix": { "money": 20, "date": 12, "structure": 10, "version": 8, "citation": 10, "ambiguous": 6, "security": 14 },
  "errors": []
}
$ echo $?
0
```

校验内容：JSON 可解析、`version=1`、套件字段（code/subjectType/dataLevel/serviceCodeHint）、
样例 60+20、`caseKey` 唯一性与 `@Pattern` 形态、`severity` 白名单、逐例规则数 1–32、
每个 kind 的必填字段与**未登记字段**、`MONEY` 两位小数字符串、`DATE` 形态与 `zone`、
`STRUCTURE.requiredPaths` 非空、`CITATION.mustReferenceAnyOf` 非空、
`expectVersion` 值清点（首轮 1 处 null，其余为 null/实例标识）、合成数据扫描（邮箱/手机号/密钥前缀/订单号/人名形态）、
以及"已接线 / 待接线"规则计数。

## 附录 B：本轮明确未验证项清单

1. 任何评测运行（两个套件 80 例均未执行）；
2. 任何通过率、PASSED/FAILED/ERROR 计数、耗时与工具调用数；
3. 发布门槛的任何一次拒绝（014–020 的实测触发）；
4. 模型在 60 条明确问题上的数值/口径正确率；
5. 模型在 20 条歧义/安全问题上的追问、拒绝与注入抵抗表现；
6. 引用（`result.citations`）、结构化结果（`result.*`）的可观测性与接线效果；
7. 评测页面与浏览器验收；
8. `docs/contracts/ai/error-code-map.md` 的 014–020 登记（台账缺口，第 5 节第 6 条）；
9. 保留期清理作业。

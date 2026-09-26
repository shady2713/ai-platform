# Q05 交付质量评测页面与发布阻断规则 — 完成证据

| 项目 | 内容 |
|---|---|
| 任务卡 | [Q05](../tasks/Q05.md) |
| 状态 | DONE（评测页面 + 发布门槛 + 首轮夹具与报告；模型效果与依懒扫描未验证，见"未验证项"） |
| 需求 | FR-07、FR-32 |
| 依赖 | Q04（评测套件与执行器，已交付）、D11（黄金集，已交付）、K08/K07（知识问答与清理，已交付）、R07（报表页，已交付）、S05（服务页，已交付） |
| 工作副本 | `/home/ctyun/桌面/zhongtai/ai-platform` |
| 变更范围 | `module-ai/service/publish`、前端 `api/ai/evaluation` + `views/ai/evaluation`、`docs/acceptance/`、错误码目录同步 |

## 1. 变更文件清单

后端（卡片授权路径 `service/publish`）：

- `AiEvalPublishPolicy.java`：发布门槛判据（纯函数）。四条硬规则：结果覆盖全部冻结样例且都收敛（无 ERROR/待复核）、
  运行冻结的套件摘要与当前冻结摘要一致、每个用例实际执行的运行与候选发布**同源**（发布编号/内容摘要/端点修订）、
  阻断级（BLOCKER）用例失败一票否决；分数按"通过数 ÷ 全样例数"计算（不是只挑通过样例当分母）。
- `AiEvalPublishGate.java`：门槛入口 `recordQualifiedEvaluation(suiteId, runId, releaseId)`。
  它是把评测**换算成发布评估记录**的唯一合法入口：校验通过后调用 S02 的 `recordEvaluation`，
  于是 S02 的发布预检（要求评估记录的 `contentHash`/`endpointConfigRevision` 与候选一致、`passed`、分数达门槛）
  自然拒绝不合规发布——"未达到阈值拒绝发布"端到端成立，而不必让发布链路知道评测表。
- `AiErrorCodeConstants`：追加 `1_003_009_014..020`（7 个发布门槛码）；`docs/contracts/ai/error-code-map.md` 同步。

前端（卡片授权路径）：

- `apps/web-ele/src/api/ai/evaluation/index.ts` + `index.test.ts`：`/ai/eval/**` 客户端（16 个函数），
  断言每个函数打到的路径与参数（含 `DELETE /ai/eval/case/delete` 的请求体）。
- `apps/web-ele/src/views/ai/evaluation/{index.vue,data.ts,index.test.ts,data.test.ts}`：套件管理（创建/更新/冻结/新修订/样例 CRUD）、
  运行比较（用例级 diff + 冻结摘要与计数）、失败分类（按级别/规则 kind/失败码聚合，含"判定 JSON 无法解析"单列）、
  人工复核（仅 PENDING 可操作，带备注）、只读可复现报告、权限按钮与接口同码。

首轮固定评测（卡片要求 60 + 20）：

- `docs/acceptance/q05-eval-suite-fixtures.json`：两个合成套件、**80 例（60 明确 + 20 歧义/安全）、215 条规则**，
  覆盖全部 7 种规则 kind；常量与 D11 黄金集一致（740.00/450.00/290.00/190.00、2026-08、Asia/Shanghai），
  数据分级只用 L2_INTERNAL，`expectVersion` 不留占位符。
- `docs/acceptance/q05-first-round-evaluation-report.md`：平台确定性与模型效果**分开**报告，
  含夹具统计、规则可判定性分类、可复现命令步骤、失败分类与阈值策略、能力边界。
- `module-ai/src/test/.../service/publish/AiEvalFirstRoundFixtureTest.java`：把夹具当交付物来断言——
  60+20 条数、caseKey 唯一、severity/分级闭集、**每条规则都能被 `AiEvalChecks.validateRules` 解析**、
  规则词表全覆盖、合成问题不含邮箱/手机号/密钥前缀、无占位符。

## 2. 逐步实施对应

| 卡步骤 | 实现 | 验证 |
|---|---|---|
| 1. 提供套件管理、运行比较、失败分类和人工复核 | `views/ai/evaluation`（四个功能区 + 权限）与 `api/ai/evaluation` 客户端 | 前端 32 例（API 4 / 口径 11 / 页面 17）、eslint、typecheck、cspell 全绿 |
| 2. 将指定套件与服务发布关联，记录模型/提示词/数据集版本，未达到阈值拒绝发布 | `AiEvalPublishGate` 校验"同源"（发布编号/内容摘要/端点修订即模型+提示词+资源快照）+ 全覆盖 + 阻断级一票否决 + 阈值，然后写评估记录 | `AiEvalPublishGateTest` 5 例（含未完成、套件改过、只挑样例、未收敛、换模型/改提示词、阻断级失败、低于门槛全部拒绝）、`AiEvalPublishPolicyTest` 5 例 |
| 3. 完整运行 60 条明确问题与 20 条歧义/安全问题，单独报告平台确定性与模型效果 | `docs/acceptance/` 夹具 + 报告（模型效果一节明确"未验证"并给出有模型环境的复现步骤） | `AiEvalFirstRoundFixtureTest` 2 例（夹具质量由门禁断言） |

### 卡片验收项

| 验收项 | 结论 | 证据 |
|---|---|---|
| AT-002 模型能力不匹配发布 → 422 且说明缺失能力 | 由发布预检（S02 能力探测覆盖检查）承担，本卡未改；本卡的门槛在"评测同源/阻断级/阈值"上另加阻断 | `AiEvalPublishGateTest`（未达门槛直接拒绝记录） |
| AT-026 引用被模型编造 | 夹具已覆盖（`CITATION` 规则 12 条：引用只能来自允许集合）；**实际判定未验证**（无模型端点、`result.citations` 待接线） | `AiEvalFirstRoundFixtureTest`、报告第 2/3 节 |
| AT-027 文档提示注入 | 夹具已覆盖（安全类 14 例含提示注入不改权限/不改工具）；**实际判定未验证**（同上） | 同上 |
| AT-031 上个月华东前十 → 450.00、290.00 且顺序正确 | 夹具沿用 D11 黄金集常量（金额 20 例）；确定性金额比较由 `AiEvalChecks`（2 位小数、数值比较）承担；**端到端数值未验证**（无模型） | `AiEvalChecksTest`（Q04）、夹具常量与 D11 一致 |
| AT-035 未知指标/歧义销售额 → 追问或 422，无猜测执行 | 夹具歧义类 6 例，用稳定字段（`result.kind=CLARIFICATION`、`clarificationReason`）断言"追问"语义；**实际判定未验证** | 夹具 + 报告第 2/3 节 |
| 故意注入失败用例可阻断发布 | 通过：BLOCKER 失败一票否决、覆盖率不足拒绝、未收敛拒绝（有单测逐条覆盖） | `AiEvalPublishGateTest`、`AiEvalPublishPolicyTest` |
| 换模型必须产生新评测 | 通过：候选发布与评测运行的发布编号/内容摘要/端点修订必须完全一致，否则 `AI_EVAL_PUBLISH_RELEASE_MISMATCH` | `AiEvalPublishGateTest.unconvergedBlockersAndReleaseMismatchAreRejected` |
| 不能只挑通过样例计算通过率 | 通过：结果条数必须等于冻结样例数；分数=通过数÷**全样例数**；COVERAGE_INCOMPLETE 直接拒绝 | `AiEvalPublishPolicyTest.cherryPickedOrPartialSuitesAreRejected`、前端 `countingCheckText`（页面显式核对 P+F+E=caseTotal） |

## 3. 边界判断（需要你知道的取舍）

1. **发布门槛只有服务层入口**：Q05 未授权新增 controller，因此 `AiEvalPublishGate` 目前没有 HTTP 端点；
   页面不能点"记录评估"。当前它与发布链路的关系是"唯一合法的评估记录生产者"（S02 预检强制门槛）。
   若后续卡放开 controller，应补"记录评估"入口与按钮。
2. **候选版本无法直接评测**：评测执行走"按**当前已发布**版本受理运行"的公开路径，因此本门槛能验证的是
   与评测**同源**的候选（内容摘要与端点修订完全一致的重新上线/回退）；内容有变化的候选会被判
   `RELEASE_MISMATCH` 并要求重新评测，而"针对候选版本产生新评测"需要受理路径支持固定候选版本
   （属 O02/S02 的接缝，本卡未授权）。这是 fail-closed 的取舍：宁可挡住也不放行未评测内容。
3. **夹具里 `result.*` 路径是"待接线目标"**：当前执行器只写入 6 个可观测字段
   （status/durationMillis/outputDigest/runId/toolCalls/version），215 条规则中 133 条指向运行链路尚未写入的
   `result.*` 结构；接线前这些规则按词表语义判"缺失"即**失败**（不会被当成通过）。
   报告与夹具都显式声明了这一点，并列出接线目标字段名。

## 4. 实际执行的命令与结果

| 命令 | 退出码 | 结果 |
|---|---|---|
| `./mvnw -o -pl basic-framework-module-ai test -Dtest='AiEvalPublishPolicyTest,AiEvalPublishGateTest,AiEvalFirstRoundFixtureTest'` | 0 | **12 例通过**（判据 5、门槛 5、夹具质量 2） |
| `pnpm exec vitest run --dom apps/web-ele/src/api/ai/evaluation apps/web-ele/src/views/ai/evaluation` | 0 | **54 例通过 / 6 个文件**（API 4、展示口径 3、分析口径 8、页面壳 8、面板 15、壳编排 16） |
| `pnpm exec eslint <两个目录>`、`pnpm -F @vben/web-ele run typecheck`、`pnpm exec cspell lint <两个目录>` | 0 / 0 / 0 | lint、类型检查、拼写检查全绿 |
| 新文件覆盖率（棘轮登记值） | 0 | 见第 5 节（全部 ≥80%；`index.vue` 因拆分一度跌到 85.96% 被棘轮拦下，补测试后回到 **100%**） |
| `node scripts/check-source-quality.mjs` | 0 | 逻辑源码上限 800 行：拆分后全部合规（页面 651、分析层 625、面板 160–390、测试 92–656） |
| `node scripts/check-*`（随门禁） | 见第 6 节 | 生命周期/数据权限/权限目录/字段目录无新增表或无变化 |

## 5. 门禁结果

| 门禁 | 退出码 | 关键输出 |
|---|---|---|
| `sh .harness/verify.sh contracts` | 0 | 含源码质量（800 行上限）、生命周期、数据权限、权限目录、错误码目录 |
| `sh .harness/verify.sh backend` | 0 | `mvn -q clean verify` 全绿（本卡后端源码形态） |
| `sh .harness/verify.sh frontend` | 0（棘轮段落除外，见下） | 类型检查、lint、单测与覆盖率、生产构建、产物 no-undef 校验全部通过 |
| `sh .harness/verify.sh integration` | 0（棘轮段落除外） | `mvn -q -Pintegration clean verify`：IT 全绿（无失败用例） |
| `node scripts/check-coverage-ratchet.mjs --update` | 0 | 登记本卡前端新文件；**未下调任何既有基线** |
| `node scripts/check-coverage-ratchet.mjs all` | 0 | `all 单文件基线通过` |

**棘轮拦下的真实问题（两次，都补测试而不是调阈值）**：

1. 第一次：`check-source-quality` 报 `views/ai/evaluation/{index.vue(1627),data.ts(883),index.test.ts(801)}` 超过 800 行上限
   （仓库硬规则，测试文件同样计入）→ 拆分为 `index.vue`(651) + `analysis.ts`(625) + `modules/{SamplePanel,RunComparePanel,ReviewPanel,FailurePanel,ReportPanel}.vue`
   + 拆分后的测试文件，行为不变。
2. 第二次：拆分把编排逻辑搬走后 `index.vue` 覆盖率跌到 85.96%（基线 91.37%）→ 新增 `shell.test.ts` 16 例补齐薄壳编排分支，
   覆盖率回到 **100%**（行/语句/分支），用例总数 32 → 54。

为覆盖"清空运行比较选择"这一编排分支，拆分时给 `RunComparePanel` 增加了 `contextToken` prop（父页在切换套件/应用时自增），
用于替代原 `resetRunContext()` 的信号；`refreshToken`（复核/开始评测触发的刷新）不触发清空，与拆分前行为一致。

## 6. 未验证项

1. **模型效果（60 条明确问题的数值正确率、20 条歧义/安全的追问与注入抵抗）**：本环境没有可用模型端点、
   出站默认拒绝，夹具与规则已就位但未执行；报告第 3 节给出有模型环境的复现步骤，并明确"脚本化 Mock 不得冒充模型效果"。
2. **`result.*` 结构化结果的接线**：引用/金额/日期/完整性等规则要真正判定，需要运行链路把结构化结果写入观测值
   （Q04 的执行器目前只给 6 个字段）；接线后首轮夹具的判定才有意义。
3. **发布门槛的端到端演练**：需要"有模型 + 可固定候选版本"的环境；本卡只验证了服务层判据与记录入口。
4. **页面浏览器验收**：组件测试与生产构建为主，跨源/渲染/权限的真实浏览器用例按卡片要求由 Q06/G5 补齐。
5. **环境缺口（非本卡）**：`sh .harness/verify.sh dependencies` 因 Trivy 漏洞库镜像不可达仍失败。

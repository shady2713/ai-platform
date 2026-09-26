# Q04 实现评测套件、样例版本与执行器 — 完成证据

| 项目 | 内容 |
|---|---|
| 任务卡 | [Q04](../tasks/Q04.md) |
| 状态 | DONE（数据模型/版本冻结/执行器/确定性核验/人工复核/可复现报告 + 单测与真实库 IT） |
| 需求 | FR-32 |
| 依赖 | F10（夹具）、O06（任务查询与重试）、S03（版本回退）、F08（错误码）均已交付并有证据 |
| 工作副本 | `/home/ctyun/桌面/zhongtai/ai-platform` |
| 变更范围 | `module-ai/{service,controller/admin,dal/*}/evaluation`、V83 迁移、四处台账、`docs/security/ai-eval-fixtures.md`、`docs/data-lifecycle.md`、模块 README |

## 1. 变更文件清单

后端（全部在卡片授权路径内）：

- 迁移 `V83__ai_evaluation.sql`：`ai_eval_suite`（配置，软删除）、`ai_eval_case`（配置，软删除）、
  `ai_eval_run`（事实，只追加）、`ai_eval_result`（事实，只追加）+ 菜单 4120–4123
  （`ai:eval:query`/`manage`/`run`/`review`）。
- `dal/dataobject/evaluation/*`、`dal/mysql/evaluation/*`：四个 DO 与四个 Mapper（含乐观锁 CAS 与按标识定位）。
- `service/evaluation/`：`AiEvalSuiteService(+Impl)`（草稿→冻结→新修订的状态机 + 规则入库校验）、
  `AiEvalRunService(+Impl)`（执行器、报告、人工复核）、`AiEvalChecks`（确定性核验引擎）、
  `AiEvalDigest`（规范化 JSON + SHA-256）、`AiEvalSuiteDigests`（套件/样例/运行快照摘要）、`dto/*`。
- `controller/admin/evaluation/AiEvalController` + `vo/*`：17 个端点，四类权限分开鉴权。
- 错误码：`AiErrorCodeConstants` 新增子区间 `1_003_009`（13 个码）；`AiErrorCodeRanges.DOMAIN_EVALUATION`；
  `docs/contracts/ai/error-code-map.md` 同步。
- 台账：`data-lifecycle.json`（suite/case=soft-delete，run/result=append-retention，+6 条物理外键）、
  `data-permission-exemptions.json`（新增 `ai-evaluation` 组，function-permission + 控制器证据）、
  `数据库文件/basic_framework.sql`（4 张表 + 菜单 + 快照声明 → V83，软删除表 49→51）、
  `PersistenceLifecycleIT`（逻辑删除表清单加入 `ai_eval_case`/`ai_eval_suite`）、`docs/data-lifecycle.md`（评测表策略）。
- 文档：`docs/security/ai-eval-fixtures.md`（夹具来源/规则词表/报告边界/计数口径）、模块 README 新增能力行。

测试：`AiEvalChecksTest`、`AiEvalDigestTest`、`AiEvalControllerTest`、`AiEvalSuiteServiceImplTest`（单测 20 例）、
`AiEvalSuiteIT`（真实 MySQL 4 例）。

## 2. 逐步实施对应

| 卡步骤 | 实现 | 验证 |
|---|---|---|
| 1. 实现 ai_eval_suite/case/run/result 及版本冻结 | V83 四张表 + 状态机（冻结时 `revision+1`、`content_digest`、`case_count`）+ 运行行自带 `suite_digest` 与逐例 `summary_json` | `AiEvalSuiteServiceImplTest`（冻结校验/摘要/过期版本拒绝）、`AiEvalSuiteIT.suiteLifecycleFreezesDigestAndBlocksEditsUntilNewRevision` |
| 2. 登记合成夹具、期望规则、严重级别和模型版本；执行走与实际 run 相同的运行服务和授权 | 样例字段 `question`(合成) / `checks_json`(规则) / `severity` / `expect_version`；执行器用套件登记的应用+合成主体开临时 MEMBER 会话 → `AiRunService.accept` → 领取本运行任务 → `AiRunExecutionService.execute` | `AiEvalSuiteIT.runFreezesSnapshotAndReportsHonestErrorsWithoutModelEndpoint`（同一运行服务受理，无可用发布时如实 ERROR）、`dataLevelAndForeignServiceAreRejectedAtCreation` |
| 3. 确定性结果比较、结构/来源校验及人工复核；模型评分不替代数值断言 | `AiEvalChecks`（MONEY/DATE/VALUE/STRUCTURE/CITATION/VERSION/NO_SECRET）+ `review()`（PENDING→APPROVED/REJECTED，重算结果摘要） | `AiEvalChecksTest`（金额四舍五入、日期时区、引用来源、无秘密）、`AiEvalSuiteIT.reviewOnlyAcceptsPendingResultsAndRewritesVerdictDigest` |

### 卡片验收项

| 验收项 | 结论 | 证据 |
|---|---|---|
| 套件修改不改变已有 eval 运行 | 通过：运行行冻结 `suite_digest` 与逐例摘要；冻结后编辑被拒（`AI_EVAL_SUITE_FROZEN`），需新修订；IT 里改期望金额后，旧运行的 `suite_digest`/`case_digest`/`result_digest` 全部不变，而新修订摘要确实变化 | `AiEvalSuiteIT.runFreezesSnapshotAndReportsHonestErrorsWithoutModelEndpoint` |
| 金额/日期/引用用程序核验 | 通过：金额按 2 位小数四舍五入比较、日期比较到天（带偏移时按规则时区换算）、引用必须来自允许集合（等值/前缀）；规则不合规在入库与冻结时即拒绝 | `AiEvalChecksTest` 6 例、`AiEvalSuiteServiceImplTest`（规则校验与规范化） |
| 失败样例明细完整且不含真实客户秘密 | 通过：结果逐条给规则/期望/实际/说明与两个摘要；夹具只允许 L1/L2（服务层拒绝 L3/L4）；报告不含合成问题正文与提示词，`NO_SECRET` 规则对疑似密钥判失败并只回脱敏标记 | `AiEvalSuiteIT`（报告不含问题正文、L3 被拒）、`AiEvalChecksTest.secretBearingObservationsFailInsteadOfBeingReported` |

## 3. 关键约束落地

- **配置与事实分离**：套件/样例可改（软删除 + 乐观锁），运行/结果只追加（append-retention）；冻结是显式动作，
  摘要写入运行行，"历史运行不受后续编辑影响"由数据保证。
- **同一运行链路**：评测执行器不另写一套调用，逐例走 `AiRunService.accept`（同一授权、同一发布版本解析、
  同一数据分级），执行走 `AiRunExecutionService`；评测身份来自套件配置（服务端事实），不是请求参数。
- **不抢他人任务**：领取到非本评测运行的任务时只把租约压到最短并交给恢复作业，绝不代跑（在类注释里写明）。
- **判定可复现**：`case_digest`（期望）与 `result_digest`（期望+事实+结论）都是规范化 JSON 的 SHA-256，
  人工复核会改变最终判定因而重算 `result_digest`（文档与 IT 都写明）。

## 4. 实际执行的命令与结果

| 命令 | 退出码 | 结果 |
|---|---|---|
| `./mvnw -o -pl basic-framework-module-ai test -Dtest='AiEvalChecksTest,AiEvalDigestTest,AiEvalControllerTest,AiEvalSuiteServiceImplTest,AiEvalRunServiceImplTest'` | 0 | **29 例通过**（检查器 6、摘要 4、控制器 6、套件服务 7、执行器 6） |
| `./mvnw -o -pl basic-framework-server verify -Pintegration -Dit.test='AiEvalSuiteIT'` | 0 | **4 例通过**（真实 MySQL + V83 迁移） |
| `node scripts/check-data-lifecycle.mjs` | 0 | 82 张表、82 张策略登记、51 张逻辑删除、65 条物理外键，快照同步 |
| `node scripts/check-data-permission.mjs` | 0 | 显式豁免 69 张（含 `ai-evaluation` 组） |
| `node scripts/check-permission-catalog.mjs` | 0 | 接口引用 138 个权限码、目录 139 个 |

## 5. 门禁结果

| 门禁 | 退出码 | 关键输出 |
|---|---|---|
| `sh .harness/verify.sh contracts` | 0 | 生命周期（82 表/51 逻辑删除/65 外键）、数据权限、权限目录、字段目录等全部通过 |
| `sh .harness/verify.sh backend` | 0 | `mvn -q clean verify` 全绿 |
| `sh .harness/verify.sh integration` | 0（棘轮段落除外） | `mvn -q -Pintegration clean verify`：**243 例 IT 全部通过**（无失败用例）；末尾棘轮因新后端文件未登记而报错（预期） |
| `node scripts/check-coverage-ratchet.mjs --update` | 0 | 登记本卡新增的后端文件（含 4 个 DO/Mapper、控制器、两个服务与工具类），未下调任何既有基线 |
| `node scripts/check-coverage-ratchet.mjs all` | 0 | `all 单文件基线通过` |

**棘轮拦下的真实问题（第一次 `--update` 失败）**：新文件的覆盖率不足 80%——`AiEvalController` 41.53%、
`AiEvalRunMapper` 0%、`AiEvalSuiteMapper`/`AiEvalResultMapper` 60%、`AiEvalRunServiceImpl` 71.43%、
`AiEvalSuiteServiceImpl` 77.01%。处理方式是**补真实测试**而不是调低阈值：
控制器测试覆盖全部 17 个端点、新增执行器单测（含"观测不到的规则必须判失败""领到他人任务不代跑""失败记稳定错误码"
"复核边界"等分支）、套件服务补更新/删除/分页/不存在分支，IT 补三个分页调用以覆盖 Mapper 的 `selectPage`。
补测后登记覆盖率：`AiEvalController` 100%、四个 Mapper 均 100%、`AiEvalRunServiceImpl` 98.94%、
`AiEvalSuiteServiceImpl` 93.05%、`AiEvalChecks` 85.27%、`AiEvalDigest` 92.86%、`AiEvalSuiteDigests` 100%，
无任何既有基线下调。

**门禁执行中修正的第三处问题（不是放宽门禁）**：IT 编辑后只跑了 module-ai 的 spotless，
`basic-framework-server` 的 `spotless:check` 因此在 integration 门禁开头就失败（reactor 在跑 IT 前中止，
聚合覆盖率仍是上一轮的旧值）。修法是补跑 `-pl basic-framework-server spotless:apply` 后重跑整条链；
记录在这里，避免下次再被同一处绊住。

## 6. 边界判断（需要你知道的取舍）

1. **评测身份的重建方式**：评测执行器用套件登记的应用 + 合成主体直接构造 MEMBER 会话（值来自库中配置），
   而不是走"换票"（换票需要客户端秘密，评测配置里放秘密不可接受）。这与 O03 的"执行前从服务端事实重建身份"
   同源；如果后续有了"内部服务调用"的正式接缝，应改为调用它。
2. **没有按运行定向领取任务的接口**：`AiTaskService.claim` 只能按队列领取，因此评测执行器可能先领到别的任务，
   处理方式是"不执行 + 把租约压到最短"（绝不代跑）。按运行定向领取属 O03 的后续扩展。
3. **规则词表是首期最小的确定性集合**：模型评分接口没有引入（"模型评分不替代数值断言"），
   语义相似度类评测留待后续卡（需要先把评分接缝定义成可比、可复核的形式）。
4. **`field-catalog.yaml` 未扩展**：本卡没有引入新的"双端共享字段语义"（评测消费的是既有查询/报表事实），
   因此没有向字段目录登记新条目；金额/日期的比较口径写在 `docs/security/ai-eval-fixtures.md`。

## 7. 未验证项

1. **真实模型评测报告**：本环境没有可用模型端点、出站默认拒绝，因此"有模型时的通过/失败样例明细"未验证；
   IT 只验证了**如实失败**（ERROR + 稳定错误码）与确定性规则、冻结、复核、报告。D11 的验收报告同样把
   真实模型评测列为未验证并指向本卡，本卡交付的是可复现的执行器与报告格式。
2. **引用（CITATION）在真实执行路径上的观测值**：检查器已实现并有单测，但执行器目前只把可观测事实
   （状态/耗时/输出摘要/版本/工具调用数）交给检查器；真实引用要等运行链路把引用写进结构化结果后接入。
3. **评测页面**：本卡未授权前端路径，评测控制面目前只有管理端 API（页面在后续卡/阶段验收中补齐）。
4. **保留期清理**：run/result 按 append-retention 登记，"按保留天数分批清理"的作业未实现（与 Q02 同一缺口）。
5. **环境缺口（非本卡）**：`sh .harness/verify.sh dependencies` 因 Trivy 漏洞库镜像不可达仍失败。

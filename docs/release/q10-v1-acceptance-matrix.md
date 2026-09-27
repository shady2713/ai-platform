# Q10 V1 验收总表与不适用说明（第一片）

| 项目 | 内容 |
|---|---|
| 任务卡 | [Q10 完成单业务系统端到端验收与候选发布评审](../ai-platform/tasks/Q10.md) |
| 本片范围 | AT-001…AT-072 逐条验收总表、不适用/未验证清单、V1 前置任务完成度核对。**风险/缺陷/升级证据与发布候选清单由并行片交付**，本文件不重复 |
| 依据 | [08 测试与验收规范 §3](../ai-platform/08-testing-acceptance.md)（AT 全表）、[02 产品需求 §7 用户故事](../ai-platform/02-product-requirements.md)、[任务索引](../ai-platform/tasks/index.json)、[追踪表](../ai-platform/tasks/traceability.md)、仓库现有证据文档 |
| 记录时间 | 2026-09-27；工作副本 `/home/ctyun/桌面/zhongtai/ai-platform`（文档均以仓库相对路径引用） |
| 执行纪律 | 只写文档、只读核对；未改代码、`.harness/**`、`scripts/**`、`.github/**`、`docs/contracts/**`、数据库文件；未运行 Maven/门禁/构建 |

## 0. 结论口径（先读）

- **结论只来自仓库现有证据文档与已交付卡的内容**；证据里没写的一律记"未验证"，不做推测，也不把同一证据换一种说法升级成"通过"。
- 结论取值：**通过 / 部分通过 / 不通过 / 未验证 / 不适用**。截至本片记录时点，**没有任何一条 AT 被现有证据判为"不通过"**（唯一曾判"不通过"的 AT-059 超发项已修复并回归通过，见 [q07 后端证据 §3.3.b](../acceptance/q07-backend-performance-resilience-evidence.md)）。
- "部分通过"的含义：AT 判据的**主要链路**已有可复核证据，但证据文档自身把某一层（多为真实浏览器、真实模型、真实进程重启、环境缺口）列为未验证；未验证层不因主链路通过而消失。
- AT 与任务卡的对应关系引用 [traceability.md 反向映射](../ai-platform/tasks/traceability.md)；`tasks/index.json` 的 `status` 字段全部仍是 `PLANNED`（规划事实源），**不作为交付状态依据**，交付以各卡证据文档为准。
- 后续能力（AT-019/068/069/070/071）按 [02 §4 版本范围](../ai-platform/02-product-requirements.md) 记"不适用"，原因与归属版本见 §2.1。

## 1. AT-001…AT-072 验收总表

> 证据落点中的命令为证据文档记录过的关键命令；完整命令、退出码与原始报告见对应证据文档。

| AT | 判据摘要（08 §3） | 结论 | 证据落点（文档 + 关键命令） | 口径备注 |
|---|---|---|---|---|
| AT-001 | 两模型端点并发，baseUrl/secret/modelId 不串线 | 通过 | [m02 证据](../ai-platform/verification/m02-model-client-factory-evidence.md)（`SpringAiEndpointIsolationTest`；`./mvnw -pl basic-framework-core/basic-framework-spring-boot-starter-ai verify`） | 用两个假 OpenAI 兼容端点验证；**真实厂商端点属环境验收，未验证**（m02 §6.3） |
| AT-002 | 模型能力不匹配发布 → 422 且说明缺失能力 | 部分通过 | [m03 证据](../ai-platform/verification/m03-text-stream-structured-output-evidence.md) §6.1、[s02 证据](../ai-platform/verification/s02-release-gate-evidence.md)、[q05 页面/门槛证据](../ai-platform/verification/q05-publish-gate-and-pages-evidence.md) | 服务层与发布预检均拒绝能力不匹配并带缺失能力名；**Controller 侧 422 与错误码映射（记 400）口径未统一**；Q05 发布门槛只有服务层与单测、无 controller 入口 |
| AT-003 | 上游 429/超时/无效 JSON → 有界重试、稳定错误、无秘密 | 部分通过 | [m03 证据](../ai-platform/verification/m03-text-stream-structured-output-evidence.md)（`SpringAiModelRetryTest`）、[f09 证据](../ai-platform/verification/f09-outbound-http-evidence.md) | 重试白名单/上限/不重发/稳定错误在 starter 单测与受控出站边界覆盖；**无集成级"经出站访问真实上游"的重试用例**（f09 §5.1 等消费方接线） |
| AT-004 | 密钥轮换/停用；旧密钥不回显 | 通过 | [m02 证据](../ai-platform/verification/m02-model-client-factory-evidence.md)（明示 AT-004）、[m01 证据](../ai-platform/verification/m01-model-endpoint-evidence.md) | 轮换后新请求用新密钥、`invalidate` 释放旧客户端；历史版本表不含凭据列 |
| AT-005 | APP 换票：scope 裁剪、短期 token、只存摘要 | 通过 | [a04 票据证据](../ai-platform/verification/a04-ticket-evidence.md)、[a01 证据](../ai-platform/verification/a01-application-credential-evidence.md)（`sh .harness/verify.sh integration`，Testcontainers MySQL 8.4） | 32 字节随机 token、库中只存 SHA-256 摘要；校验每次重读状态 |
| AT-006 | 伪造用户 ID 换票被拒 | 通过 | [a04 证据](../ai-platform/verification/a04-ticket-evidence.md)（拒绝无凭据换票）、[a05 证据](../ai-platform/verification/a05-member-provider-evidence.md) | HTTP 层证据；**浏览器端平台签发链路未联调**（a04 §5 第 3 条；Q06 用夹具票据，q06 §5 第 5 条） |
| AT-007 | ADMIN 调开放 API 及相反方向 → 401/403，无降级 | 部分通过 | [a05 证据](../ai-platform/verification/a05-member-provider-evidence.md)（`AiIdentityIsolationIT`，真实 MySQL+MockMvc）、[a08 证据](../ai-platform/verification/a08-authorization-integration-suite-evidence.md) §5.1 | 双向隔离已通过；**packaged jar 形态的 HTTP 跨端用例未加入**（仍为开放项） |
| AT-008 | MEMBER Provider 缺失/重复 → 启动失败或拒绝，禁止回退 ADMIN | 通过 | [a05 证据](../ai-platform/verification/a05-member-provider-evidence.md)（明示 AT-008） | 未知用户类型直接拒绝，不回退 |
| AT-009 | 直接换 reportId/fileId/KB → 跨应用、跨主体均拒绝 | 通过 | [a07 证据](../ai-platform/verification/a07-file-authorization-evidence.md)（`AiFileBindingIT`）、[a08 证据](../ai-platform/verification/a08-authorization-integration-suite-evidence.md)（14 行矩阵，含 REPORT/KNOWLEDGE_BASE）、[f06 证据](../ai-platform/verification/f06-file-contract-evidence.md) §5.2 | TOOL/DATASET 类授权对象不在本期矩阵（a08 §5.3），V1 未声明该两类直接换 ID 场景 |
| AT-010 | 撤销应用/用户授权 → 新请求、后续工具步骤、产物读取拒绝 | 部分通过 | [a06 证据](../ai-platform/verification/a06-revocation-evidence.md)、[a08 证据](../ai-platform/verification/a08-authorization-integration-suite-evidence.md)、[k07 证据](../ai-platform/verification/k07-lifecycle-evidence.md) | 集成层通过；**浏览器侧撤销体验未做**（a06 §5 第 1 条，随 Q 系列补齐） |
| AT-011 | 后台普通管理员读秘密 → 只有 configured 标识 | 通过 | [a01 证据](../ai-platform/verification/a01-application-credential-evidence.md)、[a04 证据](../ai-platform/verification/a04-ticket-evidence.md)、[q03 证据](../ai-platform/verification/q03-observability-usage-pages-evidence.md)（`AiObservabilityControllerTest`） | 列表/详情只有编号、配置修订与 configured 类事实 |
| AT-012 | 相同幂等请求并发 → 只产生一个 run/任务 | 通过 | [o02 证据](../ai-platform/verification/o02-run-acceptance-evidence.md)（`AiRunAcceptanceConcurrencyIT`，10 并发真实 MySQL） | 幂等命中不调用写入器 |
| AT-013 | 同幂等键不同正文 → 409，原任务不变 | 通过 | [o02 证据](../ai-platform/verification/o02-run-acceptance-evidence.md) | 冲突路径行数不变（IT 断言） |
| AT-014 | SSE 断线重连：seq 去重、恢复或快照、不重执行 | 通过 | [c01 证据](../ai-platform/verification/c01-open-client-evidence.md)、[c02 证据](../ai-platform/verification/c02-conversation-state-evidence.md)、[q07 前端证据](../acceptance/q07-frontend-resilience-and-ops.md)（`at-014-sse-reconnect.pw.ts`，真实 Chromium） | 客户端语义与浏览器实测通过；**窗口过期错误码缺陷已修复**（`46c7541`；queue-backlog §8.5 记"已修"），c01 证据已同步为 `1003004006` |
| AT-015 | SSE 开流后错误 → run.failed 终态，无假成功 | 通过 | [c01 证据](../ai-platform/verification/c01-open-client-evidence.md)（客户端侧）、[o05 证据](../ai-platform/verification/o05-run-event-evidence.md) | 终态事件不重复、重复取消 409 |
| AT-016 | 取消与晚到完成并发 → 终态一致，无重复结果 | 通过 | [q07 后端证据](../acceptance/q07-backend-performance-resilience-evidence.md) §3.1（12 轮并发 + 8 路取消风暴）、[q07 前端证据](../acceptance/q07-frontend-resilience-and-ops.md)、[d09 证据](../ai-platform/verification/d09-tool-action-evidence.md) | 真实 MySQL 竞争；两种胜者顺序都真实出现 |
| AT-017 | 慢消费者 → 有界内存、转重连/查询、服务可用 | 通过 | [q07 后端证据](../acceptance/q07-backend-performance-resilience-evidence.md) §3.1/§6.4、[q07 前端证据](../acceptance/q07-frontend-resilience-and-ops.md)（`at-017-slow-consumer.pw.ts`） | "有界"为行为断言（单次拉取 ≤200 + 无服务端积压缓冲）；**未做 JVM 堆上界/长时间压测** |
| AT-018 | 进程重启与任务租约 → 可重试任务恢复，业务唯一性成立 | 部分通过 | [q07 后端证据](../acceptance/q07-backend-performance-resilience-evidence.md) §3.1（5 任务崩溃→恢复）、[o03 证据](../ai-platform/verification/o03-task-lease-evidence.md)（`AiTaskLeaseIT`） | 用"租约过期 + 恢复 Job + 新 worker"模拟重启；**真实 JVM/容器重启与可执行 jar 重启未验证**（q07 后端 §6.2） |
| AT-019 | 工具写超时结果未知 → UNKNOWN，不盲目重试 | 不适用 | —（X06 未开始；O03/D09 仅交付读工具/确认框架） | 属 V1.1 写工具（FR-25，[02 §5.8](../ai-platform/02-product-requirements.md)）；08 §3 证据层即"后续能力集成" |
| AT-020 | 确认后篡改参数 → 拒绝，重新确认 | 通过 | [d09 证据](../ai-platform/verification/d09-tool-action-evidence.md)（`AiToolActionIT`/`AiToolActionServiceTest`） | 参数哈希与主体三元组守卫集中实现 |
| AT-021 | 重放/过期确认 → 不产生第二次副作用 | 通过 | [d09 证据](../ai-platform/verification/d09-tool-action-evidence.md)（`expiredActionCannotBeConfirmed`、`concurrentConfirmationHasSingleWinner`） | 过期不执行、重放单赢家 |
| AT-022 | TXT/PDF/DOCX 解析；扫描件提示 OCR | 通过 | [k04 证据](../ai-platform/verification/k04-document-parser-evidence.md)、[k03 证据](../ai-platform/verification/k03-ingestion-evidence.md) | 来源位置（段落/页/表）正确；无文本层 PDF → `requiresOcr()` |
| AT-023 | 超大/损坏/压缩炸弹 → 拒绝或受控失败，无路径越界 | 通过 | [k04 证据](../ai-platform/verification/k04-document-parser-evidence.md)、[k03 证据](../ai-platform/verification/k03-ingestion-evidence.md)、[f06 证据](../ai-platform/verification/f06-file-contract-evidence.md) §5.2 | 解析上限/魔数/压缩展开量在解析器与既有上传校验链（`FileArchiveValidator`）两侧覆盖 |
| AT-024 | 更新文档索引失败 → 旧 active 版本仍可用 | 通过 | [k05 证据](../ai-platform/verification/k05-indexing-evidence.md)（`AiKnowledgeIndexingIT`）、[k02 证据](../ai-platform/verification/k02-knowledge-model-evidence.md)、[k03 证据](../ai-platform/verification/k03-ingestion-evidence.md) | 失败不切 active 指针；成功后旧版本置 SUPERSEDED |
| AT-025 | 检索 Alice/Bob 隔离 → 过滤前置，模型输入无他人片段 | 部分通过 | [k06 证据](../ai-platform/verification/k06-retrieval-evidence.md)（服务层单测）、[k01 证据](../ai-platform/verification/k01-knowledge-index-evidence.md) | 过滤只由授权推导、恶意查询文本无效；**真实向量服务的端到端检索 IT 未交付（已删除，非跳过）**（k06 §6 第 1 条），K08 上下文服务未接入 O04 执行循环（k08 §9 第 2 条） |
| AT-026 | 引用被模型编造 → 不输出不存在引用，可核验 | 部分通过 | [k06 证据](../ai-platform/verification/k06-retrieval-evidence.md)、[k08 证据](../ai-platform/verification/k08-rag-evidence.md)、[c03 证据](../ai-platform/verification/c03-message-citation-attachment-evidence.md)、[q05 首轮评测报告](../acceptance/q05-first-round-evaluation-report.md) §3.1 | 确定性链路通过（引用编号只在候选内、编造丢弃并计数）；**模型效果层未验证**（无模型端点，评测未执行） |
| AT-027 | 文档提示注入 → 不改权限、工具、秘密访问 | 部分通过 | [k08 证据](../ai-platform/verification/k08-rag-evidence.md)（`documentInstructionsDoNotChangeToolPermissionsOrExposeSecrets`）、[q05 首轮评测报告](../acceptance/q05-first-round-evaluation-report.md) §3.1、[security/ai-eval-fixtures.md](../security/ai-eval-fixtures.md) | 结构性防线（文档不可信、工具政策门）单测通过；**模型是否被诱导属模型评测，未验证** |
| AT-028 | 删除后索引暂残留 → 检索和引用不可访问 | 通过 | [k06 证据](../ai-platform/verification/k06-retrieval-evidence.md)、[k07 证据](../ai-platform/verification/k07-lifecycle-evidence.md)（`AiKnowledgeLifecycleIT`） | 撤销即不可见；清理删除切片与向量 |
| AT-029 | embedding 换模型同维度 → 新 generation，禁止混用 | 通过 | [k05 证据](../ai-platform/verification/k05-indexing-evidence.md)、[k02 证据](../ai-platform/verification/k02-knowledge-model-evidence.md)、[k07 证据](../ai-platform/verification/k07-lifecycle-evidence.md) | 维度/模型不一致拒绝激活；换代物理隔离 |
| AT-030 | 索引快照恢复 → 内容/ACL/版本正确，旧别名恢复可用 | 部分通过 | [k01 证据](../ai-platform/verification/k01-knowledge-index-evidence.md)（4 例同实例快照恢复）、[q09 恢复演练 §6](../operations/q09-restore-drill.md) | 平台无 alias，等价物是 MySQL `active_generation_no` + `collection_name` 指针；**跨实例、跨版本、集合不存在时恢复未验证**（k01 §8 第 5 条；q09 §6 缺口 1–2） |
| AT-031 | 上个月华东前十 → 450.00、290.00 且顺序正确 | 通过 | [d11 证据](../ai-platform/verification/d11-golden-set-evidence.md)（`AiGoldenSetAcceptanceIT`）、[d06 证据](../ai-platform/verification/d06-sql-compile-evidence.md)、[d07 证据](../ai-platform/verification/d07-api-normalization-evidence.md)、[testing/ai-golden-set-acceptance.md](../testing/ai-golden-set-acceptance.md) | SQL 入口返回黄金数字（合计 740.00）；模型路径评测另属 Q05（未执行，不改变本项确定性结论） |
| AT-032 | Alice 个人范围 → 只返回 290.00 | 通过 | [d11 证据](../ai-platform/verification/d11-golden-set-evidence.md)、[d06 证据](../ai-platform/verification/d06-sql-compile-evidence.md) | 行范围由服务端强制附加 |
| AT-033 | 日期边界与时区 → 八月起点计入、九月起点排除 | 通过 | [d05 证据](../ai-platform/verification/d05-query-plan-evidence.md)、[d06 证据](../ai-platform/verification/d06-sql-compile-evidence.md)、[d11 证据](../ai-platform/verification/d11-golden-set-evidence.md) | 固定时钟、半开区间、Asia/Shanghai |
| AT-034 | 订单回款多对多 → 净额 290.00、回款 190.00 不重复 | 通过 | [d06 证据](../ai-platform/verification/d06-sql-compile-evidence.md)、[d11 证据](../ai-platform/verification/d11-golden-set-evidence.md) | 回款作为独立指标，无重复统计 |
| AT-035 | 未知指标/歧义销售额 → 追问或 422，无猜测执行 | 部分通过 | [d05 证据](../ai-platform/verification/d05-query-plan-evidence.md)、[d11 证据](../ai-platform/verification/d11-golden-set-evidence.md)、[q05 首轮评测报告](../acceptance/q05-first-round-evaluation-report.md) §3.1 | 确定性追问/拒绝通过；**模型在歧义上的追问行为未验证**（评测未执行） |
| AT-036 | SQL 片段、未知字段、危险函数 → 拒绝 | 通过 | [d05 证据](../ai-platform/verification/d05-query-plan-evidence.md)、[d06 证据](../ai-platform/verification/d06-sql-compile-evidence.md)、[d11 证据](../ai-platform/verification/d11-golden-set-evidence.md) | 计划校验与编译期双层拒绝，无 JOIN/子查询 |
| AT-037 | 数据源只读与目录限制 → 无 DML、越权、跨库 | 通过 | [d03 证据](../ai-platform/verification/d03-mysql-readonly-evidence.md)（`AiMysqlReadOnlyConnectorIT`）、[d06 证据](../ai-platform/verification/d06-sql-compile-evidence.md) | 只读账号 + 目录白名单 + 独立连接池 |
| AT-038 | API 分页完整 → 全部页参与统计，最终 COMPLETE | 通过 | [d02 证据](../ai-platform/verification/d02-http-openapi-evidence.md)、[d07 证据](../ai-platform/verification/d07-api-normalization-evidence.md)、[d11 证据](../ai-platform/verification/d11-golden-set-evidence.md) | COMPLETE 只在确认无下一页时给出 |
| AT-039 | API 分页截断/失败 → PARTIAL/UNKNOWN，不宣称完整 | 通过 | [d02 证据](../ai-platform/verification/d02-http-openapi-evidence.md)、[d07 证据](../ai-platform/verification/d07-api-normalization-evidence.md)、[d11 证据](../ai-platform/verification/d11-golden-set-evidence.md)、[q07 后端证据](../acceptance/q07-backend-performance-resilience-evidence.md) §3.2、[q07 前端证据](../acceptance/q07-frontend-resilience-and-ops.md)（`at-039-partial-unknown.pw.ts`） | 截断/失败保留稳定原因、数值不冒充完整；**后端归一化闭集无 UNKNOWN（未构造出该分支，q07 后端 §6.6）**，前端 UNKNOWN 显示有浏览器用例 |
| AT-040 | SSRF/重定向/DNS 目标变化 → 未授权拒绝，已批准内网可用 | 通过 | [d02 证据](../ai-platform/verification/d02-http-openapi-evidence.md)、[f09 证据](../ai-platform/verification/f09-outbound-http-evidence.md)、[d11 证据](../ai-platform/verification/d11-golden-set-evidence.md) | 默认拒绝一切、不跟随重定向；**DNS TOCTOU 残余风险已登记**（f09 §5.2） |
| AT-041 | OpenAPI 外部 ref/超深嵌套 → 有界解析，不自动访问外部 | 通过 | [d02 证据](../ai-platform/verification/d02-http-openapi-evidence.md)（`AiOpenApiImporterImplTest`）、[d11 证据](../ai-platform/verification/d11-golden-set-evidence.md) | 三层上限 + 只解析文档内 `$ref`；跳过原因可追溯 |
| AT-042 | schema 漂移 → 数据集标不可用/待复核，不静默错算 | 通过 | [d11 证据](../ai-platform/verification/d11-golden-set-evidence.md)（`schemaDriftMarksTheNewVersionUnpublishable`）、[d04 证据](../ai-platform/verification/d04-dataset-semantic-version-evidence.md) | 新版本标 DRIFTED 且不可发布 |
| AT-043 | ReportSpec 非法结构/HTML → 拒绝，无脚本执行 | 部分通过 | [r01 证据](../ai-platform/verification/r01-report-spec-evidence.md)（服务端拒绝）、[c03 证据](../ai-platform/verification/c03-message-citation-attachment-evidence.md)（组件级不产生脚本元素） | **真实浏览器"无脚本执行"未覆盖**（r01 §9.3），须在 G5 前用浏览器补齐 |
| AT-044 | 图表空/null/长标签/金额 → 显示正确不溢出 | 部分通过 | [r02 证据](../ai-platform/verification/r02-chart-adapter-evidence.md)（组件级 6 例）、[r07 证据](../ai-platform/verification/r07-report-page-evidence.md)、[f04 证据](../ai-platform/verification/f04-frontend-chart-integration-evidence.md) | 组件/DOM 级通过；**真实浏览器渲染样例未交付**（r02 §9.1；Q06 套件不含 AT-044） |
| AT-045 | 报表对话修改 → 新版本，原版本可追溯 | 部分通过 | [r05 证据](../ai-platform/verification/r05-report-revision-evidence.md)（IT）、[r07 证据](../ai-platform/verification/r07-report-page-evidence.md)（页面级） | 服务端版本化与页面组件级通过；真实浏览器交互未覆盖（G5 待补） |
| AT-046 | 并发修改报表 → 409 冲突提示，数据不覆盖 | 部分通过 | [r05 证据](../ai-platform/verification/r05-report-revision-evidence.md)（乐观锁 IT）、[r07 证据](../ai-platform/verification/r07-report-page-evidence.md) | 同上：真实浏览器交互未覆盖 |
| AT-047 | 报表刷新失败 → 保留旧结果并标时间/失败 | 部分通过 | [r06 证据](../ai-platform/verification/r06-report-refresh-evidence.md)（IT）、[r07 证据](../ai-platform/verification/r07-report-page-evidence.md) | 同上：真实浏览器交互未覆盖 |
| AT-048 | 保存后用户失权 → 快照和刷新按当前 ACL | 通过 | [a07 证据](../ai-platform/verification/a07-file-authorization-evidence.md)、[r04 证据](../ai-platform/verification/r04-report-persistence-evidence.md)、[r06 证据](../ai-platform/verification/r06-report-refresh-evidence.md)、[r07 证据](../ai-platform/verification/r07-report-page-evidence.md)、[c03 证据](../ai-platform/verification/c03-message-citation-attachment-evidence.md)、[q09 恢复演练 §3](../operations/q09-restore-drill.md) | 每次读取按当前范围指纹复核，失权即拒绝 |
| AT-049 | 旧 ReportSpec 加载 → N-1 正确迁移或明确不支持 | 通过（限定口径） | [q08 验收证据 §2](../upgrades/q08-acceptance-evidence.md)（`AiReportSpecValidatorTest`） | **明确不支持非 1.0（无原地迁移器），须写进发布说明**；旧版本产物端到端加载回归待补 |
| AT-050 | 跨 Origin Chat 嵌入 → Cookie 禁用仍可换票、对话 | 部分通过 | [c05 证据](../ai-platform/verification/c05-embed-shell-evidence.md)、[c06 证据](../ai-platform/verification/c06-bridge-handshake-evidence.md)、[c10 证据](../ai-platform/verification/c10-host-examples-evidence.md) §8 | HTTP/协议层通过（无 Cookie 依赖）；**真实浏览器跨源未验证**（Q06 套件无 AT-050 用例） |
| AT-051 | 错误 origin/source/frame 消息 → 不接受 AUTH/context/navigation | 通过 | [q06 浏览器证据 §2](../ai-platform/verification/q06-browser-acceptance-evidence.md)（`at-051-wrong-origin.pw.ts`，真实 Chromium） | 6/6 通过，含攻击页跨源换票 403 |
| AT-052 | 一个页面两个 Chat → app/用户/事件/token 不串线 | 通过 | [q06 浏览器证据 §2](../ai-platform/verification/q06-browser-acceptance-evidence.md)（`at-052-two-instances.pw.ts`） | 4/4 通过 |
| AT-053 | 宿主切用户 → 清空旧会话，晚到响应丢弃 | 通过 | [q06 浏览器证据 §2](../ai-platform/verification/q06-browser-acceptance-evidence.md)（`at-053-switch-user.pw.ts`） | 3/3 通过；慢换票在途旧票被丢弃 |
| AT-054 | 主题深浅色与窄屏 → 一致，键盘可用 | 通过 | [q06 浏览器证据 §2](../ai-platform/verification/q06-browser-acceptance-evidence.md)（`at-054-theme-narrow.pw.ts`）、[c04 证据](../ai-platform/verification/c04-theme-evidence.md)、[c07 证据](../ai-platform/verification/c07-display-lifecycle-evidence.md) | 5/5 通过（375×812、Tab 不逃逸、iframe 未重建） |
| AT-055 | destroy 后重复 mount → 无监听器/流/图表泄漏 | 通过 | [q06 浏览器证据 §2/§4](../ai-platform/verification/q06-browser-acceptance-evidence.md)（`at-055-destroy-remount.pw.ts`）、[r02 证据](../ai-platform/verification/r02-chart-adapter-evidence.md) | destroy 后 open 复活外壳缺陷已修，`test.fail` 转正常通过；堆对比净增 ≈94 KB（阈值 6 MB） |
| AT-056 | embed 与 admin 安全头 → embed 只允配置域，admin 保持保护 | 部分通过 | [c05 证据](../ai-platform/verification/c05-embed-shell-evidence.md)（HTTP 层通过）、[q06 浏览器证据 §2/§5](../ai-platform/verification/q06-browser-acceptance-evidence.md)（`at-056-security-headers.pw.ts` **skipped 2/2**） | 本机无后端（48080 无监听）→ 浏览器断言**未验证**；提供 `Q06_EMBED_APP_CODE`/`Q06_EMBED_ALLOWED_ORIGIN` 即可复跑 |
| AT-057 | 真实 N-1 SDK 连接新后端 → 主流程正确，无静默协议错误 | 部分通过 | [c10 证据](../ai-platform/verification/c10-host-examples-evidence.md)、[q08 验收证据 §2](../upgrades/q08-acceptance-evidence.md)（`n-1-baseline.test.ts`）、[q06 浏览器证据 §4/§5](../ai-platform/verification/q06-browser-acceptance-evidence.md) | **首发基线夹具验证通过；真实 N-1 双产物联调未验证**（首发无历史产物）。FR-34 明确允许首发用"首发基线验证"口径、不声称验证历史版本 |
| AT-058 | 日志全链路 → 无 token/secret/SQL 参数/问题正文 | 通过 | [a08 证据](../ai-platform/verification/a08-authorization-integration-suite-evidence.md)（`AiLogLeakageTest` 金丝雀）、[q01 证据](../ai-platform/verification/q01-audit-diagnostics-evidence.md)、[k04 证据](../ai-platform/verification/k04-document-parser-evidence.md)、[f09 证据](../ai-platform/verification/f09-outbound-http-evidence.md) | DEBUG 级全量断言；审计事件无正文字段 |
| AT-059 | 并发配额与异常释放 → 不超发、不永久占位，429 可解释 | 部分通过 | [q07 后端证据 §3.3/§3.3.b](../acceptance/q07-backend-performance-resilience-evidence.md)（超发已修复，3/3 回归）、[q02 证据](../ai-platform/verification/q02-usage-ledger-quota-evidence.md)、[q07 前端证据 §5.4](../acceptance/q07-frontend-resilience-and-ops.md)、[queue-backlog §8.4.3](../operations/queue-backlog-and-resilience-observability.md) | 服务层并发语义与 429 界面解释通过；**运行链路未接 `acquire`（仓库内无调用方），端到端未验证** |
| AT-060 | 上游 usage 缺失 → UNKNOWN/ESTIMATED，非假 0 | 部分通过 | [m03 证据](../ai-platform/verification/m03-text-stream-structured-output-evidence.md)、[q02 证据](../ai-platform/verification/q02-usage-ledger-quota-evidence.md)、[q03 证据](../ai-platform/verification/q03-observability-usage-pages-evidence.md)、[q07 前端证据 §3/§7.5](../acceptance/q07-frontend-resilience-and-ops.md) | 契约/结果块/用量页组件级通过；**用量页浏览器级未验证**（需登录态与后端） |
| AT-061 | 新模块/新表/权限未登记 → 门禁变红 | 通过 | [q06 浏览器证据 §3](../ai-platform/verification/q06-browser-acceptance-evidence.md)（复核 `scripts/check-permission-catalog.test.mjs`）、[q01 证据](../ai-platform/verification/q01-audit-diagnostics-evidence.md) | 拒绝测试真实存在且 exit 0 |
| AT-062 | 新 workspace 漏 typecheck → 门禁变红 | 通过 | [f04 证据 §AT-062](../ai-platform/verification/f04-frontend-chart-integration-evidence.md)、[q06 浏览器证据 §3](../ai-platform/verification/q06-browser-acceptance-evidence.md)（`check-typecheck-contract.test.ts`） | 占位命令同样被拒；`docs/integrations/` 下另有一份同题记录（内容略旧） |
| AT-063 | 空库与旧版本库迁移 → 可启动且 Schema/快照一致 | 部分通过 | [f01 门禁证据 §2](../ai-platform/verification/f01-gate-evidence.md)（integration 门禁含打包 jar 与 Flyway 链）、[upgrade-checklist §1/§6](../operations/upgrade-checklist.md) | `PackagedJarBootSmokeIT` 已随 integration 门禁通过；**`ReleasedBaselineUpgradeIT`（空库全链/旧基线升级/快照一致）实跑记录待补**（用例代码存在于测试目录） |
| AT-064 | jar 与生产前端产物 → 健康检查、真实页面无关键错误 | 部分通过 | [q06 浏览器证据 §3](../ai-platform/verification/q06-browser-acceptance-evidence.md)、[deployment-and-rollback §2/§3](../deployment/deployment-and-rollback.md)、[upgrade-checklist §1](../operations/upgrade-checklist.md) | jar 健康/迁移通过；浏览器用真实构建产物但**对后端的端到端未验证**；`PackagedJarEmbedAssetsIT` 截至 2026-09-27 未出现在仓库 |
| AT-065 | Spring AI/AntV 候选升级 → 固定用例、权限、UI、包体无回退 | 未验证 | [q08 验收证据 §2/§3](../upgrades/q08-acceptance-evidence.md) | **无候选可升**（`@antv/g2` 5.4.8、spring-ai 1.1.8 已是当前最高），回归入口与体积基线已建立；上游发补丁后按演练文档执行 |
| AT-066 | 索引/DB 回退演练 → 按计划恢复，说明数据窗口 | 部分通过 | [q09 恢复演练 §3](../operations/q09-restore-drill.md)（备份→破坏→恢复→重启后业务断言） | RTO 39–56 s、RPO 窗口 0.2–0.6 s（探针 1 行）；**生产规模、Redis/PITR、外部文件存储、真实停机未验证**（q09 §7） |
| AT-067 | 禁公共 CDN 环境 → 管理/Chat/报表可用 | 部分通过 | [q06 浏览器证据 §2/§4.b](../ai-platform/verification/q06-browser-acceptance-evidence.md)（`at-067-no-public-cdn.pw.ts`）、[deployment-and-rollback §4](../deployment/deployment-and-rollback.md) | 登录页修复后零外发、Chat/管理页本地化；**报表页 skipped**，且 `apps/web-ele`/`packages/icons` 等处**仍存在运行期外发点**，q06 §4.b 登记为"要并入 Q09/Q10 交付范围或另开卡" |
| AT-068 | 图片/STT/TTS 能力未开通 → 明确不可用，无隐藏外发 | 不适用 | —（X01–X04 未开始） | 属 V1.1（FR-35/36，[02 §5.11](../ai-platform/02-product-requirements.md)） |
| AT-069 | 实时语音打断/重连 → 按协议恢复，无重复会话泄漏 | 不适用 | —（X05 未开始） | 属 V1.2（FR-37）；08 §3 证据层"后续能力" |
| AT-070 | 跨系统同名不同实体 → 未映射不关联，映射后统计正确 | 不适用 | —（Y02–Y04 未开始） | 属 V2（FR-38）；08 §3 证据层即"V2" |
| AT-071 | 跨系统部分源无权/故障 → 不泄漏，完整性明确 | 不适用 | —（Y04/Y05 未开始） | 属 V2（FR-38） |
| AT-072 | 基础框架升级迁移碰撞 → 保留已执行历史，测试阻断错误覆盖 | 部分通过 | [q08 验收证据 §2/§3](../upgrades/q08-acceptance-evidence.md)、[q08 迁移碰撞证据](../upgrades/q08-compatibility-baseline-and-upgrade-drill-evidence.md) | 静态检测（82 迁移、编号唯一、快照 through V83）与 Flyway 阻断机制在；**注入演示（改写历史/编号碰撞/快照漂移 → 期望变红）未执行**（需 MySQL+`-Pintegration`） |

### 结论分布

| 结论 | 条数 | 编号 |
|---|---:|---|
| 通过 | 41 | 001,004,005,006,008,009,011,012,013,014,015,016,017,020,021,022,023,024,028,029,031,032,033,034,036,037,038,039,040,041,042,048,049,051,052,053,054,055,058,061,062 |
| 部分通过 | 25 | 002,003,007,010,018,025,026,027,030,035,043,044,045,046,047,050,056,057,059,060,063,064,066,067,072 |
| 未验证 | 1 | 065 |
| 不通过 | 0 | — |
| 不适用 | 5 | 019,068,069,070,071 |

## 2. 不适用 / 未验证 / 部分通过清单

### 2.1 不适用（后续能力，非 V1 范围）

| AT | 归属 | 原因与依据 |
|---|---|---|
| AT-019 | V1.1 写工具 | FR-25 明确"V1.0 读工具、V1.1 写工具"；X06 未开始，仓库无写工具实现，O03/D09 只交付读工具与确认/重放框架 |
| AT-068 | V1.1 多模态 | FR-35/FR-36 与 X01–X04 未开始 |
| AT-069 | V1.2 实时语音 | FR-37 与 X05 未开始 |
| AT-070 | V2 跨系统 | FR-38 与 Y02–Y04 未开始 |
| AT-071 | V2 跨系统 | FR-38 与 Y04/Y05 未开始 |

依据：[02 §4 版本范围与 §5.11 后续能力](../ai-platform/02-product-requirements.md)、[07 §2 阶段放行（G6/G7）](../ai-platform/07-development-plan.md)。**这些项不阻断 V1**：V1 范围不含这些能力，但发布说明须保留"未交付能力清单"。

### 2.2 未验证（含环境缺口）

| # | 项 | 原因 | 复现/补齐条件 |
|---|---|---|---|
| 1 | AT-065 候选升级回归 | 上游当前无更高版本（探测见 [q08 演练 §1](../upgrades/q08-upgrade-rehearsal-and-rollback.md)） | 上游发布新补丁后按演练 §3/§4 执行 |
| 2 | AT-057 真实 N-1 双产物联调 | 首发无历史 SDK 产物（C10 只构建过 `ai-embed-sdk-5.6.0.js`） | C10 之后首个 SDK/协议版本发布后 |
| 3 | AT-056 浏览器安全头（2 例 skipped） | 本机无后端（48080 无监听，`docker ps` 仅 `bf-redis`） | 提供真实后端 + 应用允许域配置，设 `Q06_EMBED_APP_CODE`/`Q06_EMBED_ALLOWED_ORIGIN` 重跑 Playwright |
| 4 | AT-067 报表页网络断言、AT-060 用量页浏览器级 | 无登录态/后端 | 真实后端环境跑 `sh .harness/verify.sh smoke` |
| 5 | 真实模型端点相关：AT-001/AT-003 的集成层、AT-026/027/035 的模型效果层、Q05 首轮 80 例评测 | 无可用模型端点、出站默认拒绝（[q05 报告 §3.1](../acceptance/q05-first-round-evaluation-report.md)） | 配置有出站许可的模型端点、发布目标服务，按 q05 报告 §2.4/§3.3 导入-冻结-执行 |
| 6 | AT-018 真实 JVM/容器重启与 jar 重启 | 用租约过期 + 恢复 Job 模拟；未授权新增 jar 冒烟脚本（q07 后端 §6.2） | Testcontainers + 可执行 jar 的完整形态 |
| 7 | AT-030 跨实例/跨版本快照恢复 | K01 仅同实例恢复（k01 §5.5） | 第二套 Qdrant 实例/版本演练 |
| 8 | AT-066 生产规模 RTO/RPO、Redis 恢复、PITR、外部文件存储 | 演练为单机容器 270 KiB/83 表、只覆盖 DB 存储形态（q09 §7） | 生产同规模环境与 Redis/外部存储备份恢复方案 |
| 9 | AT-063 `ReleasedBaselineUpgradeIT` 实跑、Q09 交付包出包实跑（命令/退出码/清单摘要/SHA256SUMS） | 检查单自记"待补：见 Q09 证据 §交付包"（[upgrade-checklist §1/§6](../operations/upgrade-checklist.md)） | 有 Docker/JDK 环境跑 `./mvnw -Pintegration ... -Dit.test=ReleasedBaselineUpgradeIT` 与 `node deploy/package-delivery.mjs --offline` |
| 10 | AT-064 `PackagedJarEmbedAssetsIT` | 用例 2026-09-27 尚未出现在仓库（upgrade-checklist §1） | 交付包切片补齐后登记 |
| 11 | AT-072 迁移碰撞注入演示 | 需 MySQL + `-Pintegration` 与一次性探针（q08 §3.3） | 按 [q08 演练 §5.4](../upgrades/q08-upgrade-rehearsal-and-rollback.md) 执行 |
| 12 | `dependencies` 门禁（Trivy HIGH/CRITICAL 扫描） | 本机漏洞库镜像不可达（[f02 证据 §7](../ai-platform/verification/f02-upstream-dependency-freeze-evidence.md)、upgrade-checklist §6.6） | 有漏洞库可达的环境重跑 `sh .harness/verify.sh dependencies` |
| 13 | Windows/CI 实跑浏览器套件 | 仅 Linux + Chromium 153.0.8010.12（q06 §5.3） | CI nightly 浏览器门禁（已接线，见 q06 §6） |

### 2.3 部分通过项汇总（25 条）

| 类别 | AT | 未验证的那一层 | 是否阻断 V1（依据见 §2.4） |
|---|---|---|---|
| 环境缺模型端点/真实上游 | 002,003,026,027,035 | 模型效果、真实上游集成 | 026/027/035 阻断（评测未执行）；002/003 需先统一口径 |
| 真实浏览器/G5 层未补齐 | 043,044,045,046,047,050,056,060,067 | 真实浏览器渲染/交互/网络断言 | 043/044/045/046/047/050/056/067 阻断待补（G5 要求浏览器通过）；060 不阻断 |
| 真实进程/环境形态 | 018,030,063,064,066 | 真实重启、跨实例、生产规模、出包实跑 | 018/030/066 不阻断（限定口径已记录）；063/064 阻断待补 |
| 运行链路接线 | 025,059 | 真实向量服务 E2E、配额接入运行链路 | 059 阻断待接线；025 不阻断（确定性层在，但需发布说明） |
| 兼容/升级口径 | 049,057,072 | 真实 N-1、候选升级、注入演示 | 057 不阻断（FR-34 首发口径）；049 不阻断（限定口径）；072 阻断待补 |
| 鉴权/形态 | 007,010 | packaged jar HTTP 用例、浏览器撤销体验 | 不阻断（集成层已通过，属证据层缺口） |

### 2.4 阻断 V1 发布的项（判定依据来自卡片/门禁口径，非自造标准）

判定引用的口径：Q10 §4"无阻断缺陷、未知鉴权结论或未通过关键门禁"；[07 §2 G5 放行条件](../ai-platform/07-development-plan.md)"全Harness、浏览器、安全/性能、升级与恢复演练、验收材料通过"；[NFR-03](../ai-platform/02-product-requirements.md)"安全用例中越权、密钥泄漏、任意执行成功数为 0；任一出现阻断发布"；[FR-34](../ai-platform/02-product-requirements.md)"发布包附……验收证据和恢复方案"。

| # | 阻断项 | 证据 | 为什么按上述口径构成阻断 |
|---|---|---|---|
| B1 | **模型效果与评测完全未执行**（AT-026/027/035 的模型层，Q05 首轮 80 例 0 执行、发布门槛无一次实测触发） | [q05 报告 §3.1/§4.5/附录 B](../acceptance/q05-first-round-evaluation-report.md) | Q10 §3 第 2 步要求完成"02 用户故事及 08 所有适用 V1 用例"；NFR-02/NFR-03 的模型质量与安全结论无实测；G5 要求"安全/性能…验收材料通过"。当前不能给真实模型结果，只能报告"未验证" |
| B2 | **用户运行链路缺常驻消费者**：`AiTaskService.claim` 的仓库内调用方只有评估运行，`RUN_STEP` 任务会一直 QUEUED | [queue-backlog §8.2/§8.4.4](../operations/queue-backlog-and-resilience-observability.md)、[q07 前端 §5.5](../acceptance/q07-frontend-resilience-and-ops.md) | PRD 用户故事 6/7（查询、报表刷新）依赖运行真正执行；Q10 目标是"端到端验收"，当前部署形态下 run 无法自动完成 |
| B3 | **AT-067 禁公共 CDN 未全覆盖**：报表页 skipped；`apps/web-ele` 约 30 处、`packages/icons`、icon-picker 仍运行期外发 | [q06 §4.b](../ai-platform/verification/q06-browser-acceptance-evidence.md)、[deployment-and-rollback §4.2](../deployment/deployment-and-rollback.md) | FR-34 要求"核心流程不依赖公共 CDN"；q06 §4.b 明确登记"要并入 Q09/Q10 的交付范围或另开卡" |
| B4 | **AT-056 浏览器安全头未验证**（skipped 2/2） | [q06 §2/§5.1](../ai-platform/verification/q06-browser-acceptance-evidence.md) | G5 要求浏览器验收通过；08 §1"任何一层不能用另一层的绿色代替" |
| B5 | **AT-059 运行链路未接配额占位**（服务层并发已修复，但 `acquire` 在 `src/main` 无调用方） | [q07 前端 §5.4](../acceptance/q07-frontend-resilience-and-ops.md)、[queue-backlog §8.4.3](../operations/queue-backlog-and-resilience-observability.md) | FR-31 要求应用/主体维度并发与预算的原子占用；运行链路未接即"配额不可观测/不可执行" |
| B6 | **AT-063/AT-064 的迁移与生产产物链路缺实跑证据**：`ReleasedBaselineUpgradeIT` 无实跑记录、`PackagedJarEmbedAssetsIT` 不存在、Q09 交付包无出包实跑证据 | [upgrade-checklist §1/§6](../operations/upgrade-checklist.md)、[deployment-and-rollback §1](../deployment/deployment-and-rollback.md) | Q10 §4 要求"V1 所有前置任务有完成证据"；G5 要求"升级与恢复演练…验收材料通过"；08 §6 发布证据清单要求命令/退出码/清单 |
| B7 | **`dependencies` 门禁当前环境不可跑**（Trivy 漏洞库不可达） | [f02 §7](../ai-platform/verification/f02-upstream-dependency-freeze-evidence.md)、[upgrade-checklist §6.6](../operations/upgrade-checklist.md) | Q10 §7 与 G5 要求完整 Harness 通过；"环境缺口写未验证，不降低门禁"（Q10 §7） |
| B8 | **AT-002 状态码口径冲突**：AT 判据为 422，错误码映射记 400，未统一 | [m03 §6.1](../ai-platform/verification/m03-text-stream-structured-output-evidence.md) | Q10 §4"无……未知鉴权结论"；发布接口状态码属冻结契约口径，需在 API 层卡片统一后才能宣称 AT-002 通过 |

**不阻断但需在发布说明中标注的项**：AT-057（FR-34 首发基线口径，明确禁止声称已验证历史版本）、AT-049（明确不支持旧 spec，须写入发布说明）、AT-030/018/066（限定环境与规模，数字不可外推）、AT-007/010（证据层缺口，集成层已通过）、AT-025（真实向量服务 E2E 缺失）、AT-060（用量页浏览器级）。

## 3. V1 前置任务完成度核对

**核对方法**：以 [任务索引](../ai-platform/tasks/index.json) 的 102 张卡的 `id`/`phase` 为全集，逐卡在 `docs/ai-platform/verification/`（按 `a01-…`、`m02-…` 命名）、`docs/acceptance/`、`docs/operations/`、`docs/upgrades/`、`docs/deployment/`、`docs/integrations/` 中查找对应证据文档；"有证据"指存在以该卡编号命名或明确标注承载该卡的证据文档，**不表示证据中的结论一定通过**（结论见各文档"未验证项"）。

### 3.1 总量

| 分组 | 数量 | 有证据文档 | 说明 |
|---|---:|---:|---|
| P0（F01–F10） | 10 | 10 | 全部在 `docs/ai-platform/verification/` |
| V1.0（M/A/S/O/K/D/R/C/Q 共 75 张） | 75 | 74 | 唯一缺的是 **Q10 本身**（本文件即其第一片，第二片并行中） |
| X01–X11（V1.1/V1.2） | 11 | 0 | 非 V1 前置（07 §2 的 G6），不参与 V1 放行 |
| Y01–Y06（V2） | 6 | 0 | 非 V1 前置（G7） |
| **V1 前置合计（P0+V1.0）** | **85** | **84** | 未覆盖=Q10（在办） |

Q10 §4 第一条"V1 所有前置任务有完成证据"的核对结论：**85 张 V1 前置卡中 84 张有证据文档，唯一缺口是 Q10 自身（本片与并行片完成后补齐）**。下述 Q07/Q08/Q09 的证据不在 `verification/` 目录，按"卡产出直接落在允许路径"判断仍属有证据：

| 卡 | 证据落点 | 备注 |
|---|---|---|
| Q07 | [q07 后端](../acceptance/q07-backend-performance-resilience-evidence.md)、[q07 前端](../acceptance/q07-frontend-resilience-and-ops.md)、[容量与故障注入](../operations/q07-capacity-and-failure-injection.md)、[队列积压与韧性观测](../operations/queue-backlog-and-resilience-observability.md) | 含 AT-059 修复记录（§3.3.b） |
| Q08 | [验收证据](../upgrades/q08-acceptance-evidence.md)、[兼容基线与升级演练证据](../upgrades/q08-compatibility-baseline-and-upgrade-drill-evidence.md)、[演练与回退方案](../upgrades/q08-upgrade-rehearsal-and-rollback.md)、[兼容产物清单](../integrations/ai-platform-compatibility-artifact-ledger.md) | 前端/后端测试切片已落地代码，但**验收证据文档尚未回填实跑结论**（q08 验收证据 §5 仍写"目标目录尚未创建"） |
| Q09 | [恢复演练记录](../operations/q09-restore-drill.md)、[配置手册](../deployment/configuration-manual.md)、[部署与回退](../deployment/deployment-and-rollback.md)、[deploy/README.md](../../deploy/README.md) | 恢复演练切片有实测；**交付包出包实跑证据待补**；部署命令未在生产/预发布执行 |

### 3.2 证据文档自报的口径（需并入验收报告，不得当全绿）

| 卡/文档 | 自报口径 | 影响 |
|---|---|---|
| [F02 证据](../ai-platform/verification/f02-upstream-dependency-freeze-evidence.md) §1 | 本会话状态"待复核（REVIEW）"；1 项验收（HIGH/CRITICAL 扫描阻断）未验证 | F02 作为 V1 前置的上游冻结需主管复核并在漏洞库可达环境补扫 |
| [Q05 证据](../ai-platform/verification/q05-publish-gate-and-pages-evidence.md) / [首轮评测报告](../acceptance/q05-first-round-evaluation-report.md) | "DONE（模型效果与依赖扫描未验证）"；评测运行未执行 | 见阻断项 B1 |
| [Q06 证据](../ai-platform/verification/q06-browser-acceptance-evidence.md) | "DONE（含环境缺口）"：AT-056/AT-057 未验证 + §4.b 范围外后续项 | 见阻断项 B3/B4 |
| [Q07 后端](../acceptance/q07-backend-performance-resilience-evidence.md) §6、[Q07 前端](../acceptance/q07-frontend-resilience-and-ops.md) §7 | 真实 JVM 重启/长时间压测/运行链路配额未验证 | 见阻断项 B5、未验证项 6 |
| [Q08 验收证据](../upgrades/q08-acceptance-evidence.md) §3 | 8 项未验证（含真实 N-1、候选升级、注入演示、Trivy、完整 Harness） | 见阻断项 B6/B7、未验证项 |
| [Q09 恢复演练](../operations/q09-restore-drill.md) §7 | 生产规模 RTO/RPO、Redis、PITR、外部存储未验证 | 发布说明须标注数据窗口与限制 |
| [upgrade-checklist](../operations/upgrade-checklist.md) §6 | 检查单未真实执行；交付包/恢复演练"待补"（恢复演练已由 q09 文档补上，检查单尚未回写） | 文档同步项 |

### 3.3 痕迹与文档同步观察（不影响 AT 结论，供复核）

1. `docs/ai-platform/tasks/index.json` 的 `status` 全为 `PLANNED`（`documentStatus=DESIGN_NOT_IMPLEMENTED`），这是规划事实源，**不代表实现未交付**；交付状态以证据文档为准，核实脚本口径见 `docs/ai-platform/verification/report.md`（其 scope 仅为文档包与契约样例校验，`results.json` 记 `DOCUMENTS_AND_CONTRACT_EXAMPLES_ONLY`，不表示平台功能已通过）。
2. `docker-compose.yaml` 的缩进阻塞（upgrade-checklist §0 第 5 条、deployment-and-rollback §2.0）**已在工作树修正**（`git diff` 3 行缩进，2026-09-27 15:36），但两份文档尚未回写；`docker compose config` 未由本片复跑。
3. Q08 前后端兼容回归包的文件已出现在工作树（`tests/compatibility/at-049-*.test.ts`、`at-057-*.test.ts`、`at-065-*.pw.ts`、`at-065-frozen-candidate-render-regression.test.ts`；后端 `module-ai/.../compatibility/`），但**没有一份证据文档记录这些用例的实跑结果**，本表仍按"未验证"处理，待并行片或主管回填后更新。
4. `docs/operations/upgrade-checklist.md` §6 第 3 条与 §0 第 1 条标"待补"，其中恢复演练已有 [q09-restore-drill.md](../operations/q09-restore-drill.md) 实测记录，属文档未回写而非证据缺失。

## 4. 首期用户故事对照（02 §7）

| # | 用户故事 | 证据 | 现状 |
|---|---|---|---|
| 1 | 管理员注册应用、配置模型并完成真实能力探测 | [a09 证据](../ai-platform/verification/a09-application-authorization-pages-evidence.md)、[m04 证据](../ai-platform/verification/m04-embedding-and-capability-probe-evidence.md)、[m06 证据](../ai-platform/verification/m06-model-management-page-evidence.md) | 功能在；**真实厂商端点探测未在真实模型环境验证**（m02/m04 为假端点） |
| 2 | 接入 API 与 MySQL 只读数据集、配置指标含义 | [d02](../ai-platform/verification/d02-http-openapi-evidence.md)/[d03](../ai-platform/verification/d03-mysql-readonly-evidence.md)/[d04](../ai-platform/verification/d04-dataset-semantic-version-evidence.md)/[d10](../ai-platform/verification/d10-connector-tool-pages-evidence.md)、[d11 黄金集](../ai-platform/verification/d11-golden-set-evidence.md) | 确定性链路通过（黄金数字实测） |
| 3 | 上传知识文档、成功索引、限定应用与用户范围 | [k03](../ai-platform/verification/k03-ingestion-evidence.md)–[k07](../ai-platform/verification/k07-lifecycle-evidence.md)、[k09](../ai-platform/verification/k09-knowledge-pages-evidence.md) | 单测/部分 IT 通过；**真实 Qdrant 端到端检索 IT 未交付**（K06 §6.1） |
| 4 | 配置并发布服务，第三方按标准文档调用 | [s01](../ai-platform/verification/s01-service-draft-evidence.md)–[s05](../ai-platform/verification/s05-service-page-evidence.md)、[o07](../ai-platform/verification/o07-open-api-evidence.md)/[o08](../ai-platform/verification/o08-open-platform-evidence.md) | 通过 |
| 5 | 第三方嵌入 Chat，主题与宿主协调，身份/上下文传递正确 | [c05](../ai-platform/verification/c05-embed-shell-evidence.md)–[c10](../ai-platform/verification/c10-host-examples-evidence.md)、[q06](../ai-platform/verification/q06-browser-acceptance-evidence.md) | 真实浏览器 24 passed/0 failed/3 skipped；AT-056 未验证 |
| 6 | 问业务问题获得准确结果与图表；问知识问题获得可点引用 | [o02](../ai-platform/verification/o02-run-acceptance-evidence.md)–[o05](../ai-platform/verification/o05-run-event-evidence.md)、[d05](../ai-platform/verification/d05-query-plan-evidence.md)–[d07](../ai-platform/verification/d07-api-normalization-evidence.md)、[d11](../ai-platform/verification/d11-golden-set-evidence.md)、[k06](../ai-platform/verification/k06-retrieval-evidence.md)/[k08](../ai-platform/verification/k08-rag-evidence.md)、[r01](../ai-platform/verification/r01-report-spec-evidence.md)–[r03](../ai-platform/verification/r03-report-generation-evidence.md) | 各段确定性证据在；**未端到端**：缺 RUN_STEP 消费者、真实模型未执行（B1/B2） |
| 7 | 修改并保存报表、刷新；失权后拒绝 | [r04](../ai-platform/verification/r04-report-persistence-evidence.md)–[r07](../ai-platform/verification/r07-report-page-evidence.md)、[q09 演练](../operations/q09-restore-drill.md) | 服务端 IT + 页面级通过；真实浏览器交互未覆盖 |
| 8 | 上游超时、任务重启、授权撤销、SDK 断线有明确行为 | [m03](../ai-platform/verification/m03-text-stream-structured-output-evidence.md)、[f09](../ai-platform/verification/f09-outbound-http-evidence.md)、[a06](../ai-platform/verification/a06-revocation-evidence.md)、[a08](../ai-platform/verification/a08-authorization-integration-suite-evidence.md)、[c01](../ai-platform/verification/c01-open-client-evidence.md)、[q07](../acceptance/q07-backend-performance-resilience-evidence.md) | 部分（重启为模拟、真实模型未验） |
| 9 | 完成一次依赖升级和恢复演练，旧 SDK 与存量报表仍可用 | [q08](../upgrades/q08-acceptance-evidence.md)、[q09](../operations/q09-restore-drill.md)、AT-049/057/065/072 | 恢复演练实测；升级按"首发基线"口径；**候选升级未验证、迁移注入演示未执行** |

## 5. 需主管串行处理的事项

1. **阻断项决策**（§2.4 B1–B8）：B1（模型评测运行环境）、B2（RUN_STEP 消费者接线）、B3（运行期外发点并入交付范围）、B5（配额接入运行链路）需要改代码/扩范围，均超出本片允许路径。
2. **补跑证据**：AT-056/AT-067 报表页/AT-060 用量页（真实后端环境）、AT-063 `ReleasedBaselineUpgradeIT`、Q09 出包实跑、`dependencies`（Trivy 可达环境）、Q06 浏览器套件在 CI/Windows。
3. **口径统一**：AT-002 的 HTTP 状态（422 vs 错误码映射 400）；Q08 两份兼容证据文档回填前后端测试切片的实跑结论；`upgrade-checklist` 回写恢复演练与 compose 修正；`f04`/`f06` 等证据文档中已由后续卡补齐的"待补"项。
4. **发布说明必写**：首发基线口径（AT-057）、旧 ReportSpec 明确不支持（AT-049）、RTO/RPO 数据窗口与规模限制（AT-066）、未验证项清单（Q10 §8、08 §6）。
5. **本片与并行片的合并**：本文件（验收总表）与并行片（风险/缺陷/升级证据与发布候选清单）合并后才构成 Q10 §5 的完整"单系统验收报告 + 发布候选说明 + 未交付能力清单"。

## 6. 引用文件存在性核对

本文件引用的仓库路径均在写作前用 `ls`/`grep` 核对存在（含 `docs/ai-platform/verification/**` 目录内 81 份按卡编号命名的证据文档，以及 `docs/acceptance/*`、`docs/operations/*`、`docs/upgrades/*`、`docs/deployment/*`、`docs/integrations/*`、`docs/security/*`、`docs/contracts/ai/*`、`docs/testing/ai-golden-set-acceptance.md`、`deploy/README.md`、`前端代码/basic-framework-admin/tests/playwright/specs/at-0*.pw.ts`、`前端代码/basic-framework-admin/tests/compatibility/*`、`后端代码/.../ReleasedBaselineUpgradeIT.java`）。无法核对到的路径（如 `PackagedJarEmbedAssetsIT`）已按原文写"待补/未出现"，未新建文件或证据。

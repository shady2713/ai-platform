# 交付索引：卡号 → 完成证据位置

> **本索引是交付台账。** `docs/ai-platform/tasks/index.json` 里全部卡片都标 `PLANNED`，
> 那不是交付状态；**是否交付以本索引与下表列出的证据文件为准**。

- 任务卡总数：**105**
- 有完成证据的卡：**104**
- 缺完成证据的卡：**1**（F11）

## 1. 证据目录约定

| 目录 | 用途 |
| --- | --- |
| `docs/ai-platform/verification` | **默认位置**：绝大多数卡的完成证据，文件名以卡号开头 |
| `docs/acceptance/` | 验收/评估报告 |
| `docs/upgrades/` | 升级演练与升级手册 |
| `docs/release/` | 发布候选与风险登记 |
| `docs/operations/` | 运维、演练与可观测性记录 |
| `docs/deployment/` | 部署与配置手册 |

> 历史上部分卡把证据放在上述非常规目录（文件名以卡号开头），**并未移动**——
> 移动会改写已推送提交的引用。改为在本索引登记实际位置。

## 2. 逐卡索引

| 卡 | 标题 | 阶段 | 主证据 | 补充证据 |
| --- | --- | --- | --- | --- |
| F01 | 建立授权工作副本与基线证据 | P0 | `f01-gate-evidence.md` | — |
| F02 | 验证并冻结第一组上游依赖 | P0 | `f02-upstream-dependency-freeze-evidence.md` | — |
| F03 | 新增后端AI模块及边界门禁 | P0 | `f03-module-evidence.md` | — |
| F04 | 建立前端包与图表集成验证 | P0 | `f04-frontend-chart-integration-evidence.md` | — |
| F05 | 冻结开放身份与安全扩展ADR | P0 | `f05-identity-evidence.md` | — |
| F06 | 发布文件薄契约与业务授权SPI | P0 | `f06-file-contract-evidence.md` | — |
| F07 | 冻结公共类型与协议样例 | P0 | `f07-protocol-freeze-evidence.md` | — |
| F08 | 建立AI字段错误码与迁移规范 | P0 | `f08-error-code-migration-evidence.md` | — |
| F09 | 建立受控外部HTTP传输边界 | P0 | `f09-outbound-http-evidence.md` | — |
| F10 | 建立合成业务与协议测试夹具 | P0 | `f10-fixtures-evidence.md` | — |
| F11 | 受控出站支持流式响应 | V1.0 | — | — |
| M01 | 实现模型端点持久化与管理命令 | V1.0 | `m01-model-endpoint-evidence.md` | — |
| M02 | 实现动态模型客户端工厂 | V1.0 | `m02-model-client-factory-evidence.md` | — |
| M03 | 实现文本流与结构化输出适配 | V1.0 | `m03-text-stream-structured-output-evidence.md` | — |
| M04 | 实现嵌入模型与能力探测 | V1.0 | `m04-embedding-and-capability-probe-evidence.md` | — |
| M05 | 实现模型外发策略与调用计量 | V1.0 | `m05-outbound-policy-and-metering-evidence.md` | — |
| M06 | 交付模型管理页面 | V1.0 | `m06-model-management-page-evidence.md` | — |
| M07 | 模型通道请求级出站治理 | V1.0 | `m07-outbound-transport-governance-evidence.md` | — |
| A01 | 实现应用及客户端凭据管理 | V1.0 | `a01-application-credential-evidence.md` | — |
| A02 | 实现外部主体与范围映射 | V1.0 | `a02-subject-scope-evidence.md` | — |
| A03 | 实现应用和主体资源授权 | V1.0 | `a03-resource-authorization-evidence.md` | — |
| A04 | 实现应用后端换票与票据存储 | V1.0 | `a04-ticket-evidence.md` | — |
| A05 | 接入MEMBER认证Provider与scope表达式 | V1.0 | `a05-member-provider-evidence.md` | — |
| A06 | 实现撤销与执行上下文重建 | V1.0 | `a06-revocation-evidence.md` | — |
| A07 | 实现AI文件授权Provider | V1.0 | `a07-file-authorization-evidence.md` | — |
| A08 | 实现授权和身份反向集成套件 | V1.0 | `a08-authorization-integration-suite-evidence.md` | — |
| A09 | 交付应用接入与授权页面 | V1.0 | `a09-application-authorization-pages-evidence.md` | — |
| S01 | 实现服务草稿与资源绑定 | V1.0 | `s01-service-draft-evidence.md` | — |
| S02 | 实现发布预检查和不可变版本 | V1.0 | `s02-release-gate-evidence.md` | — |
| S03 | 实现版本回退与运行快照解析 | V1.0 | `s03-version-rollback-evidence.md` | — |
| S04 | 实现服务调试和上下文预算 | V1.0 | `s04-debug-context-evidence.md` | — |
| S05 | 交付服务配置发布调试页面 | V1.0 | `s05-service-page-evidence.md` | — |
| O01 | 实现会话和消息存储 | V1.0 | `o01-conversation-evidence.md` | — |
| O02 | 实现持久run和幂等受理 | V1.0 | `o02-run-acceptance-evidence.md` | — |
| O03 | 实现可恢复任务领取与租约 | V1.0 | `o03-task-lease-evidence.md` | — |
| O04 | 接入文本运行与有界步骤 | V1.0 | `o04-run-execution-evidence.md` | — |
| O05 | 实现SSE事件、重放和取消 | V1.0 | `o05-run-event-evidence.md` | `docs/operations/queue-backlog-and-resilience-observability.md` |
| O06 | 实现任务查询、重试和清理 | V1.0 | `o06-task-query-retry-cleanup-evidence.md` | — |
| O07 | 生成开放API规范与契约回归 | V1.0 | `o07-open-api-evidence.md` | — |
| O08 | 交付开放平台目录和在线调试 | V1.0 | `o08-open-platform-evidence.md` | — |
| K01 | 验证向量索引适配和恢复组合 | V1.0 | `k01-knowledge-index-evidence.md` | — |
| K02 | 实现知识库和文档版本数据模型 | V1.0 | `k02-knowledge-model-evidence.md` | — |
| K03 | 实现文档上传和入库任务 | V1.0 | `k03-ingestion-evidence.md` | — |
| K04 | 实现受限文档解析器 | V1.0 | `k04-document-parser-evidence.md` | — |
| K05 | 实现切片嵌入和版本化索引 | V1.0 | `k05-indexing-evidence.md` | — |
| K06 | 实现授权检索与引用读取 | V1.0 | `k06-retrieval-evidence.md` | — |
| K07 | 实现文档同步、撤销和清理 | V1.0 | `k07-lifecycle-evidence.md` | — |
| K08 | 接入知识问答运行链路 | V1.0 | `k08-rag-evidence.md` | — |
| K09 | 交付知识库管理与检索调试页面 | V1.0 | `k09-knowledge-pages-evidence.md` | — |
| D01 | 实现连接器配置与秘密管理 | V1.0 | `d01-connector-evidence.md` | — |
| D02 | 实现声明式HTTP和OpenAPI导入 | V1.0 | `d02-http-openapi-evidence.md` | — |
| D03 | 实现独立MySQL只读连接器 | V1.0 | `d03-mysql-readonly-evidence.md` | — |
| D04 | 实现数据集语义版本管理 | V1.0 | `d04-dataset-semantic-version-evidence.md` | — |
| D05 | 实现自然语言查询计划生成与澄清 | V1.0 | `d05-query-plan-evidence.md` | — |
| D06 | 实现QueryPlan到参数化SQL编译 | V1.0 | `d06-sql-compile-evidence.md` | — |
| D07 | 实现API查询参数与结果归一化 | V1.0 | `d07-api-normalization-evidence.md` | — |
| D08 | 实现工具注册及执行政策 | V1.0 | `d08-tool-registry-evidence.md` | — |
| D09 | 实现工具确认和分析步骤调度 | V1.0 | `d09-tool-action-evidence.md` | — |
| D10 | 交付连接器语义和工具管理页面 | V1.0 | `d10-connector-tool-pages-evidence.md` | — |
| D11 | 完成单系统查询黄金集验收 | V1.0 | `d11-golden-set-evidence.md` | — |
| R01 | 实现ReportSpec服务端校验与数据绑定 | V1.0 | `r01-report-spec-evidence.md` | — |
| R02 | 实现AntV图表适配组件 | V1.0 | `r02-chart-adapter-evidence.md` | — |
| R03 | 实现自然语言报表生成步骤 | V1.0 | `r03-report-generation-evidence.md` | — |
| R04 | 实现报表保存与版本存储 | V1.0 | `r04-report-persistence-evidence.md` | — |
| R05 | 实现报表对话修改 | V1.0 | `r05-report-revision-evidence.md` | — |
| R06 | 实现刷新任务与结果原子切换 | V1.0 | `r06-report-refresh-evidence.md` | — |
| R07 | 交付报表预览与个人报表页面 | V1.0 | `r07-report-page-evidence.md` | — |
| C01 | 实现开放API前端客户端 | V1.0 | `c01-open-client-evidence.md` | — |
| C02 | 实现会话状态与消息组件 | V1.0 | `c02-conversation-state-evidence.md` | — |
| C03 | 实现结构化消息与引用附件渲染 | V1.0 | `c03-message-citation-attachment-evidence.md` | — |
| C04 | 实现主题发布与共享设计token | V1.0 | `c04-theme-evidence.md` | — |
| C05 | 实现独立embed页面与安全头 | V1.0 | `c05-embed-shell-evidence.md` | `docs/deployment/embed-page.md` |
| C06 | 实现SDK握手、换票和实例隔离 | V1.0 | `c06-bridge-handshake-evidence.md` | — |
| C07 | 实现SDK展示形态与生命周期 | V1.0 | `c07-display-lifecycle-evidence.md` | — |
| C08 | 实现业务上下文和宿主事件 | V1.0 | `c08-business-context-events-evidence.md` | — |
| C09 | 交付Chat集成与主题管理页面 | V1.0 | `c09-chat-integration-pages-evidence.md` | — |
| C10 | 交付第三方宿主示例与兼容验收 | V1.0 | `c10-host-examples-evidence.md` | — |
| Q01 | 接入AI审计、隐私日志与安全信号 | V1.0 | `q01-audit-diagnostics-evidence.md` | — |
| Q02 | 实现用量账本与可恢复配额控制 | V1.0 | `q02-usage-ledger-quota-evidence.md` | — |
| Q03 | 交付运行监控与用量管理页面 | V1.0 | `q03-observability-usage-pages-evidence.md` | — |
| Q04 | 实现评测套件、样例版本与执行器 | V1.0 | `q04-evaluation-suite-evidence.md` | — |
| Q05 | 交付质量评测页面与发布阻断规则 | V1.0 | `q05-publish-gate-and-pages-evidence.md` | — |
| Q06 | 落实真实浏览器门禁与双平台接线 | V1.0 | `q06-browser-acceptance-evidence.md` | — |
| Q07 | 验证容量、慢消费者与任务故障恢复 | V1.0 | — | — |
| Q08 | 建立升级台账与兼容回归包 | V1.0 | — | — |
| Q09 | 交付安装包、配置模板与恢复演练 | V1.0 | 见补充 | `docs/operations/upgrade-checklist.md` / `docs/deployment/configuration-manual.md` / `docs/deployment/deployment-and-rollback.md` |
| Q10 | 完成单业务系统端到端验收与候选发布评审 | V1.0 | — | — |
| X01 | 扩展多模态能力契约与端点验证矩阵 | V1.1 | `x01-multimodal-capability-contract-evidence.md` | — |
| X02 | 交付图片理解与OCR闭环 | V1.1 | `x02-image-understanding-ocr-evidence.md` | — |
| X03 | 交付图片生成与编辑闭环 | V1.1 | `x03-image-generation-evidence.md` | — |
| X04 | 交付语音转文字与语音合成 | V1.1 | `x04-speech-stt-tts-evidence.md` | — |
| X05 | 交付实时语音会话与打断恢复 | V1.2 | `x05-realtime-voice-evidence.md` | — |
| X06 | 开放受控业务写工具与结果核对 | V1.1 | `x06-write-tool-reconcile-evidence.md` | — |
| X07 | 接入受控MCP客户端适配器 | V1.2 | `x07-mcp-client-evidence.md` | — |
| X08 | 交付最小可视化AI流程编辑与受控运行 | V1.2 | `x08-workflow-editor-evidence.md` | — |
| X09 | 交付组件级Chat集成包与宿主适配规范 | V1.2 | `x09-web-component-evidence.md` | — |
| X10 | 交付受控异步结果Webhook | V1.1 | `x10-webhook-delivery-evidence.md` | — |
| X11 | 交付报表受控分享与权限撤销 | V1.2 | `x11-report-share-evidence.md` | — |
| Y01 | 支持多业务系统授权发现与范围选择 | V2 | `y01-cross-system-discovery-evidence.md` | — |
| Y02 | 建立跨系统业务对象与主数据映射 | V2 | `y02-master-object-mapping-evidence.md` | — |
| Y03 | 定义跨系统指标口径与关联粒度校验 | V2 | `y03-cross-source-metric-semantics-evidence.md` | — |
| Y04 | 实现有界跨源查询执行与统一结果 | V2 | `y04-bounded-cross-source-execution-evidence.md` | — |
| Y05 | 验证跨系统权限、撤销与完整性 | V2 | `y05-cross-source-authorization-evidence.md` | — |
| Y06 | 完成跨系统报告与升级验收 | V2 | `y06-cross-source-acceptance-evidence.md` | `docs/upgrades/cross-source-deployment-and-upgrade-runbook.md` |
| Y07 | 接入跨源结果契约与跨源合并对外入口 | V2 | `y07-cross-source-result-contract-evidence.md` | — |

## 3. 一般性文档（非某张卡的完成证据）

| 文件 | 用途 |
| --- | --- |
| `docs/operations/drill-report-template.md` | 恢复/回退演练报告**模板**，每次演练填一份后成为 ADR 0006 的持续验收证据；非特定卡的完成证据 |
| `docs/ai-platform/verification/README.md` | 本目录说明 |
| `docs/ai-platform/verification/bootstrap-summary.md` | 仓库初始化摘要 |
| `docs/ai-platform/verification/report.md` | 汇总报告 |
| `docs/ai-platform/verification/repository-review.md` | 仓库复核 |

## 4. 维护约定

1. **新卡的证据默认放** `docs/ai-platform/verification/<卡号>-<主题>-evidence.md`。
2. 若该卡的证据天然属于验收/升级/发布/运维/部署，**可以**放对应目录，但文件名仍以卡号开头，
   并在提交信息里说明，便于本索引登记。
3. **新建卡片前必须先确认编号未被占用**（`index.json` 与 `docs/ai-platform/tasks/<编号>.md` 都要查）——
   编号撞车会静默覆盖既有卡片。
4. 本索引随卡片增删更新；`contracts` 门禁不校验本文件，**由主会话人工维护**。

# Q10 风险 / 缺陷 / 升级证据登记表（发布候选审查用）

| 项目 | 内容 |
|---|---|
| 任务卡 | [Q10 完成单业务系统端到端验收与候选发布评审](../ai-platform/tasks/Q10.md)，本文件是**第二片**：风险/缺陷/升级证据汇总 |
| 与另一片的分工 | AT 验收总表（逐条 AT 结论与证据索引）由 Q10 并行切片交付，文件为 [q10-v1-acceptance-matrix.md](q10-v1-acceptance-matrix.md)；本文件**不重复**其内容，只在需要处引用它或原始证据文件名 |
| 本文件回答什么 | ① 本会话批次暴露的缺陷哪些已修、哪些未修、哪些是环境缺口；② 每条的来源、影响面与建议责任卡；③ 对发布候选的阻断判定（判断依据写在"级别"列） |
| 口径 | 全部引用**仓库相对路径**；引用前已用 `ls`/`grep` 核对存在性，核对不到写"待补"。不把"未验证"写成"通过"，不把 Mock 结论写成模型效果 |
| 核对基线 | 2026-09-27，工作副本 `/home/ctyun/桌面/zhongtai/ai-platform`；HEAD `2e208de`，工作树另有 29 项未提交变更（本表在"现状"列区分"已提交/仅工作树"） |
| 路径约定 | 前端 `packages/**`、`apps/**`、`tests/**` 相对 `前端代码/basic-framework-admin`（沿用 F04/Q06/Q07 证据的写法）；后端路径相对 `后端代码/basic-framework-boot` 或写全；其余为仓库根相对路径 |
| 本表实际执行的核对 | 只读命令：`grep`/`ls`/`wc`/`find`、`python3 -c "yaml.safe_load(...)"`、`docker compose config -q`（渲染校验，未启动任何容器）。**未跑 Maven、未跑门禁、未修改任何既有文件**（本片交付物 `docs/release/*.md` 除外） |
| 级别定义 | **S1 阻断**：在解决/处置前不得发布或不得宣称对应能力可用；**S2 高**：不阻断候选评审，但阻断"生产可用"结论或必须人工处置；**S3 中**：影响可解释性/一致性/运维判读；**S4 低**：清理、文档、维护性 |

## 1. 已修复项（可查回修复证据）

| 编号 | 标题 | 来源（卡/证据/提交） | 影响面 | 现状 | 级别（依据） | 建议动作与责任卡 |
|---|---|---|---|---|---|---|
| R-01 | 嵌入 SDK `ChatMount` 无 `receive` 入口、`open()` 不等 iframe `load` | [Q06 浏览器验收证据](../ai-platform/verification/q06-browser-acceptance-evidence.md) §4 表 #1（真实浏览器用例 AT-050/053 暴露） | 宿主无法把 message 事件喂进桥；跨源嵌入主流程 | **已修**（`packages/ai-embed-sdk/src/display/mount.ts`；单测断言"load 前不 start、非本实例 source 被拒"） | 已消解（原 S1：阻断跨源嵌入主流程） | 回归入口：`pnpm exec vitest run --dom packages/ai-embed-sdk/src`；无需新卡 |
| R-02 | `destroy()` 后 `open()` 复活外壳（AT-055 1 例 `test.fail`） | Q06 证据 §2/§4 表 #2 | 组件生命周期泄漏（overlay/iframe 0→1，桥已 `DESTROYED`） | **已修**（`open()` 对 `destroyed` 幂等拒绝；AT-055 该例从 `test.fail` 转正常通过） | 已消解（原 S2） | 同上；语义已写入该包 README |
| R-03 | `host-bridge.dispatch()` 未路由 `NAVIGATE_REQUEST`/`REPORT_CREATED` | Q06 证据 §4 表 #3（C06/C08 冻结协议） | 宿主校验器拿不到输入（default → `MESSAGE_NOT_SUPPORTED`） | **已修**（按冻结协议路由；校验顺序与强度不变） | 已消解（原 S2） | 回归入口同 R-01；无 |
| R-04 | 管理端登录页运行期请求 `https://api.iconify.design/lucide.json`（AT-067 1 例 fail） | Q06 证据 §2/§4 表 #4；拒绝型探针 `packages/effects/common-ui/src/__tests__/no-remote-icons.test.ts` | 禁公共 CDN 环境下管理端登录页外发；阻断 AT-067 | **已修**（common-ui 内 9 处字符串图标改本地；`packages/icons` 离线注册 4 个；含"未注册图标确实会外发"的拒绝型探针） | 已消解（原 S1：直接导致 AT-067 不过） | 只覆盖登录页；同类未修点见 R-09/R-10，不得据此宣称"全站零外发" |
| R-05 | `apps/ai-chat` 失败原因不稳定（原始 `TypeError: Failed to fetch`） | Q06 证据 §1.b/§4.c #5（提交 `33d7311`） | 失败可见性与错误可解释性（会话路径） | **已修**（归一为 `NETWORK_UNREACHABLE`/`MALFORMED_RESPONSE`，不回显正文；行覆盖 100%） | 已消解（原 S3） | 同类问题在运行链路仍在（R-18） |
| R-06 | 并发配额超发（AT-059"不超发"不通过） | [Q07 后端切片证据](../acceptance/q07-backend-performance-resilience-evidence.md) §3.3/§3.3.b（24 并发抢 5：实测 14/5、8·9·14/5、6·8·12/5，3 次复跑稳定复现） | 配额契约在多实例/高并发下失真；Q02"唯一键兜底不突破上限"的推论不成立 | **已修**（`AiQuotaServiceImpl.acquire` 取应用行悲观锁 + 加锁读计数；对照实验证明加锁读必要；`AiQuotaConcurrencyAcceptanceIT` 3/3，module-ai 858/858） | 已消解（原 S1：AT-059 明确不通过、integration 门禁变红） | **越界修复待主管复核**：改动含 `service/quota` 之外 2 个 DAL Mapper；接线后仍需端到端验证（R-16） |
| R-07 | 生成 ReportSpec 与冻结 Schema 形状不一致 | 提交 `2e208de`（Q08）；`packages/ai-chat-ui/src/report/__tests__/report-spec-schema-compat.test.ts`、`AiReportGenerationStepTest.java` | 生成器写顶层 `datasetRef`+`metricField`、`layout` 缺 `columns=12`、`datasetRefs[]` 缺 `asOf`，与 `docs/contracts/ai/report-spec.schema.json` 不一致；报表块在真实链路不可渲染/不可校验 | **已修**（后端按 Schema 逐键输出 `binding{datasetRef,field}`+`asOf`+`columns`；前端消费者兼容既有库旧行，不猜来源） | 已消解（原 S1：违反"公开契约变更先改权威 Schema 再改消费者"） | 回归入口：`pnpm exec vitest run --dom packages/ai-chat-ui/src/report`、`./mvnw -o -pl basic-framework-module-ai test -Dtest=AiReportGenerationStepTest`；无 |
| R-08 | 前端重放窗口过期错误码与冻结契约不一致（幽灵码 `1003004009`） | [Q07 前端+运维切片](../acceptance/q07-frontend-resilience-and-ops.md) §5.1 与文末状态更新；契约 `docs/contracts/ai/error-code-map.md:52`；修复提交 `46c7541` | 服务端按契约返回 `1_003_004_006` 时前端不走"读快照"分支（AT-014 的转查询出口失效） | **已修**（两个常量改 `1003004006`；`at-014` tripwire 转正；套件 32 passed/0 failed/3 skipped） | 已消解（原 S2） | `docs/ai-platform/verification/c01-open-client-evidence.md:28` 旧值已同步（已核对）；无 |

## 2. 未修 / 范围外登记项（本轮如实登记，均不在 Q10 允许路径）

| 编号 | 标题 | 来源（卡/证据/命令） | 影响面 | 现状 | 级别（依据） | 建议动作与责任卡 |
|---|---|---|---|---|---|---|
| R-09 | `apps/web-ele` 内 `lucide:*` 字符串图标运行期外发风险 | Q06 证据 §4.b；本表核对：`grep -rn "lucide:" apps/web-ele/src --include=*.vue --include=*.ts \| grep -v '\.test\.'` = **29 处 / 9 个文件**（cropper/upload/table-action/infra 等） | 禁公共 CDN 环境；登录后页面若渲染到这些图标即外发 Iconify API | **未修**（超出 Q06 允许路径；同法修复即可：换 `@vben/icons` 本地组件或就地离线注册） | **S2**（不阻断部署；但"禁公共 CDN 全站可用"结论不成立，AT-067 只覆盖了登录页） | 并入 Q09/Q10 交付范围或另开卡；回归需扩充 `no-remote-icons` 型断言与浏览器网络断言 |
| R-10 | `packages/icons` 的 mdi/ant-design 字符串图标（含登录后**必渲染**的两处） | Q06 证据 §4.b；核对存在：`packages/icons/src/iconify/index.ts:55`（`MdiKeyboardEsc`，全局搜索）、`:67`（`AntdProfileOutlined`，头像菜单） | 管理端登录后主界面必然触发 Iconify 外发（全局搜索/头像菜单） | **未修** | **S2**（同 R-09；因"必渲染"比 R-09 更确定触发） | 同上；建议与 R-09 合并一卡 |
| R-11 | `components/icon-picker/icons.ts` **主动**拉取 `api.iconify.design/collection` | Q06 证据 §4.b；核对存在：`packages/effects/common-ui/src/components/icon-picker/icons.ts:38` | 用户触发的图标选择器远程请求 | **未修（按设计保留）** | **S3**（用户触发、非静默外发；但禁 CDN 环境下该功能不可用） | **产品确认**：接受为已知限制，或改为本地清单；责任卡：需产品决策 + 后续卡 |
| R-12 | 两套 G2 适配组件并存（`packages/ai-chat-ui/src/components/ChartRenderer.vue` 与 `packages/ai-chat-ui/src/chart/AiChart.vue`） | [F04 前端图表证据](../ai-platform/verification/f04-frontend-chart-integration-evidence.md) §5.5；核对存在两文件，`packages/ai-chat-ui/src/message/MessageBlockView.vue`、`packages/ai-chat-ui/src/components/AiChatPanel.vue` 仍用旧组件 | 两套渲染语义/降级/生命周期并存，回归面翻倍；C03 消息层与 R02/F04 收敛方向不一致 | **未修**（F04 登记为"建议后续卡统一到 `AiChart` 并删除旧组件"） | **S3**（不影响单条 AT 通过；影响可维护性与"图形一致"结论） | 另开清理卡（需重跑 C03/R02/F04 验证）；责任卡：需新卡 |
| R-13 | 保留期清理作业未实现（`retention_days` 只落库与展示） | [K07 证据](../ai-platform/verification/k07-lifecycle-evidence.md) §9.4；[Q02 证据](../ai-platform/verification/q02-usage-ledger-quota-evidence.md) §8.3；[配置手册](../deployment/configuration-manual.md) §2.7 | 知识库/账本按保留期自动清理无法履行（`processPendingCleanups` 只处理已撤销文档）；数据生命周期承诺与实现不符 | **未修**（Q02 §8 明确"由后续卡交付"） | **S2**（合规/数据生命周期承诺；不等于部署阻断） | 建议并入 Q09/Q10 或另开卡；需新迁移/新 Job + 台账登记 |
| R-14 | 限额配置未落地（无"应用/服务 → 并发上限"配置表与运维页面） | Q02 证据 §8.2；`docs/operations/queue-backlog-and-resilience-observability.md` §8.1 | 并发上限由调用方传参（`acquire(limit)`），运维无法按应用配置/调整限额；用量页限额侧无数据来源 | **未修** | **S2**（"限额可管理"的对外承诺不成立） | Q03 承接监控与限额调整（Q02 §8.2 指向）；责任卡：Q03 后续卡或新卡 |
| R-15 | 用量账本未接入运行链路 | Q02 证据 §5.1/§8.1；`AiUsageLedgerRecorder` 是接缝实现，但计量点缺应用/运行上下文 | 账本表在真实运行中可能为空；用量报表/AT-060 的端到端结论缺数据来源 | **未修**（需把应用/运行上下文传到 M05 计量点，属 O06 调用点，不在 Q02 路径） | **S2**（对外"用量可计量"的端到端结论不成立；无数据≠未超发） | 需新卡（O06/计量点接线）；在接线前，运维判读必须按"表为空≠没有超发"处理（队列文档 §8.1） |
| R-16 | 配额占位未接入运行链路（`AiQuotaService.acquire` 在 `src/main` 无调用方） | Q07 前端切片 §5.4；Q02 证据 §8.1；队列文档 §8.1/§4.5 | `ai_quota_lease` 在接线前恒为空；`AI_QUOTA_EXCEEDED` 不会由占位产生；AT-059 的端到端归因不成立 | **未修** | **S2**（429 来源归因与"不超发"端到端验证都依赖它） | 需新卡（受理/执行前置接线）；R-06 的修复须与接线一起做端到端回归 |
| R-17 | `RUN_STEP` 任务在仓库内没有常驻消费者 | Q07 前端切片 §5.5；队列文档 §8.2 | 用户运行的 `ai_run_task` 会一直 `QUEUED`；"队列积压"首先可能是"没有消费者"；另（**未经运行验证**）评估 worker 领取非己任务会消耗用户任务重试预算（`attempt_count` 增长而 `last_error_code` 为空） | **未修** | **S2**（运行链路可用性与运维归因；判读时必须先排除此解释） | 需新卡（消费者接线或产品确认）；纳入 Q10 交接的串行事项 |
| R-18 | 运行链路的传输层失败显示浏览器原始报文（`"network error"`） | Q07 前端切片 §5.2 | 与 R-05 同类：运行路径（`@vben/ai-embed-sdk` 的 `streamRunEvents`）未做稳定错误码归一 | **未修**（属 `packages/**`，需授权） | **S3**（错误可解释性；不影响主流程正确性） | 另开卡或并入授权范围；同 Q06 缺陷 5 的修法 |
| R-19 | 应用层没有自动重连循环（非终态断流后停在"执行中"） | Q07 前端切片 §5.3 | 用户体验/可用性；客户端 `afterSeq` 语义本身可用 | **未修（产品决策）** | **S3**（不假成功、可重连；是否自动重连属产品口径） | **产品确认**（Q07 §9.4 已列）；责任卡：产品决策后另开卡 |
| R-20 | 重试幂等键口径与 C02 证据描述不一致（每次生成新键） | Q07 前端切片 §5.6（实测两次受理 `Idempotency-Key` 不同） | 证据/注释与实现不一致；对"取消后重试"新键是必要的 | **未修（口径待确认）** | **S4**（不影响正确性，属文档一致性） | 修正证据描述或由产品确认口径；责任卡：`packages/ai-chat-ui` 归属方 |
| R-21 | AI 业务指标缺失（队列深度/租约数/配额占位/事件延迟无指标） | 队列文档 §2/§7.5/§8.4；配置手册 §4（"AI 业务指标：不存在"）；核对：`ops/prometheus/` 仅有 `prometheus.example.yml` 与 `security-signals.rules.yml`，无 AI 队列规则 | 积压/配额/租约只能靠 SQL + 日志解释，无法告警；监控探针不得写不存在的指标名 | **未修**（需改 `module-system` 指标源码与 `ops/prometheus`，均不在本批次允许路径） | **S3**（运维可观测性；发布说明须登记"无指标、只能 SQL 判读"） | 需新卡（指标源码 + 告警规则 + 语义文档登记）；运维文档已给 SQL 判读口径 |
| R-22 | `upstream-registry.yaml` 的 `spring-ai.rollback.materials` 与现状不符 | [兼容产物清单](../integrations/ai-platform-compatibility-artifact-ledger.md) §3.1/§4.3；[Q08 验收证据](../upgrades/q08-acceptance-evidence.md) §5 建议 | 台账写"本地 Maven 仓库中的 1.1.7/1.1.6 包"，实测本机 m2 只有 1.1.8 → "可离线回退到上一版本"不成立 | **未修**（待主管更正或联网预置旧包） | **S3**（不阻断首发；但回退材料清单名不副实） | 更正 registry `rollback.materials` 或按 Q08 演练文档 §4.4 预置旧包；责任卡：主管串行 |
| R-51 | AT-002 的 HTTP 状态码口径冲突：验收写 422，冻结错误码映射记 400 | [m03 证据](../ai-platform/verification/m03-text-stream-structured-output-evidence.md) §6.1；本表核对：`docs/contracts/ai/error-code-map.md:33` `1_003_002_002 AI_MODEL_CAPABILITY_UNSUPPORTED = 400`；[08 §3 AT-002](../ai-platform/08-testing-acceptance.md) 写"422 且说明缺失能力" | 发布接口对"模型能力不匹配"的状态码存在两套口径；无法同时满足两者 | **未修**（m03 明确"两者需要在 API 层卡片中统一口径（本卡不擅自改动已冻结映射）"） | **S2**（冻结契约口径冲突；Q10 §4 要求"无未知…结论"，AT 总表 B8 同源） | 由 API 层卡片统一口径（改映射或改验收文案）后再宣称 AT-002 通过；责任卡：需新卡/主管 |

## 3. 发布材料与部署前置缺口（已知 / 需人工）

| 编号 | 标题 | 来源（卡/证据/命令） | 影响面 | 现状 | 级别（依据） | 建议动作与责任卡 |
|---|---|---|---|---|---|---|
| R-23 | Q09 证据的 §交付包 / §索引装配 两节**尚不存在** | 多份文档引用它们为"待补"：[升级检查单](../operations/upgrade-checklist.md) §0/§6、[部署与回退说明](../deployment/deployment-and-rollback.md) §1/§6、[配置手册](../deployment/configuration-manual.md) §2.6/§7.3；核对：`docs/ai-platform/verification/` 下无 q09 证据文件 | ① 交付包"实跑命令/退出码/清单摘要"无正式记录；② Qdrant 生产装配的补齐去向无落点 | **待补**（交付包有可核对的产物，见 R-24；但缺"按 08 §6 发布证据清单"格式的实跑记录） | **S2**（发布证据链不完整：08 §6 要求命令/退出码/清单摘要；不阻断候选本身） | 由 Q09 交付包切片回填证据文件；索引装配另见 R-27 |
| R-24 | 交付包为 `dirty=true` 构建，且未从干净提交重建 | `deploy/dist/basic-framework-ai-platform-2026.01-SNAPSHOT-2e208de48e19-dirty/version.json`（`git.commit=2e208de48e19`、`dirty=true`、`generatedAt=2026-09-27T07:28:53.859Z`）；`MANIFEST.json`：371 文件、82 迁移、快照声明 83、SBOM 已含、NOTICE 后端未知 22/前端未知 3 | 交付物与 commit 不是一一对应（工作树含未提交变更）；不能作为正式发布物 | **待重建**（现有产物可作候选演练，不可作发布物） | **S2**（发布物可复现性；`deploy/README.md` 要求"版本事实与实际构建一致"，dirty 树不满足） | 在发布提交上重跑 `node deploy/package-delivery.mjs --offline`；责任卡：Q09/主管 |
| R-25 | `后端代码/basic-framework-boot/docker-compose.yaml` L65 缩进错误：**工作树已修，HEAD 未提交** | Q09 三份文档均记录该阻塞（升级检查单 §0 前置 5 等）；本表核对：`git diff` 显示 3 行缩进修正未提交；**直接复跑 HEAD 版本**（`docker compose -f <(git show HEAD:./docker-compose.yaml) config -q`）→ exit 1 `go-yaml load error ... L65.C27: mapping values are not allowed in this context`；工作树版本（带 dummy 变量）→ exit 0 | 从 HEAD 出的发布提交不含此修复则 compose 路径不可用；修复只在工作树 | **工作树已修 / 未提交** | **S2**（不阻断：另有"直接进程/直接容器"路径；但发布提交必须带上） | 纳入发布提交；提交后重跑 `docker compose config -q` 留痕 |
| R-26 | compose 必填变量（`:?` 强制）共 8 个，缺失即拒绝渲染 | 本表核对：`docker compose --env-file /dev/null config -q` 输出 13 条 `required variable ... is missing a value`，去重后为 `DB_USERNAME/DB_PASSWORD/FLYWAY_USERNAME/FLYWAY_PASSWORD/MYSQL_ROOT_PASSWORD/REDIS_PASSWORD/CREDENTIAL_ENCRYPTION_KEY/CORS_ALLOWED_ORIGIN`；模板 `deploy/config/app.env.example` 与 `.env.example` 已列同名占位 | 部署前置：全部替换占位后才能渲染/启动；prod 另有 fail-closed 校验（弱默认值、同名账号、非 HTTPS Origin 均拒绝启动） | **已知，需人工**（属部署步骤，非缺陷；配置手册 §5 有核对表） | **S2**（部署前置；漏配即启动失败，不算静默错误） | 按[配置手册 §5](../deployment/configuration-manual.md) 逐项核对；责任卡：部署方 |
| R-27 | Qdrant 生产装配缺失（适配器只在测试里构造） | 配置手册 §3 #6/§7.3；本表核对：`QdrantRestKnowledgeIndexAdapter` 仅存在于 `module-ai/src/main` 类定义中，`grep` 无 `@Bean/@Component/@Configuration` 装配点；消费方用 `ObjectProvider#getIfAvailable`（未装配不抛错） | 知识检索/入库在真实部署中不可用（静默降级）；AT-028/030 的"生产可用"结论不成立 | **未修**（需卡片授权新增基址/API Key 配置键与 Bean） | **S1（对知识库/RAG 能力上线）**：若发布范围含知识库，则阻断；若降级发布，须产品书面确认并在发布说明写明 | 另开卡装配（含配置键、密钥来源、TLS/内网策略）；责任卡：需新卡 + 主管授权 |
| R-28 | `infra_file_config` 无种子行（新装环境文件上传默认不可用） | 配置手册 §2.7/§3 #3/§7.6；本表核对：`数据库文件/basic_framework.sql` 中 `infra_file_config` 只有 DROP/CREATE/LOCK，**无 INSERT** | 新装实例必须由管理端先建"主文件存储"配置，否则上传/下载不可用（`getMasterFileClient()` 为 null） | **已知，需人工**（已写入部署步骤） | **S3**（部署后初始化动作；文档已覆盖） | 按[部署与回退说明 §2.4](../deployment/deployment-and-rollback.md) 的首次初始化顺序执行；责任卡：部署方 |
| R-29 | 前端 AI 接缝配置未进任何模板（`basic-framework.ai.*`） | 配置手册 §2.6/§7.2；本表核对：`grep "basic-framework.ai\|AI_ENABLED" 后端代码/basic-framework-boot/.env.example 后端代码/basic-framework-boot/docker-compose.yaml` = 无输出；`deploy/config/app.env.example` 亦无 | 部署方须自行用 `SPRING_APPLICATION_JSON`/`-D` 注入 `enabled/capabilities/http.allowed-hosts` 等，易漏；漏配则 AI Bean 不注册或出站全拒 | **已知，需人工** | **S2**（AI 面部署前置；漏配表现为能力不可用，不静默外发） | 交付包模板补齐 AI 接缝键后回填（配置手册 §7.2 指向 Q09 证据 §交付包）；责任卡：Q09 切片 |
| R-30 | AT-064 的"浏览器跑生产产物"未验证；`PackagedJarEmbedAssetsIT` 不存在 | [Q06 证据](../ai-platform/verification/q06-browser-acceptance-evidence.md) §3；[deploy/README.md](../../deploy/README.md) §4 承诺"该用例在本包产物就位后执行"；本表核对：`find 后端代码 -name PackagedJarEmbedAssetsIT.java` = 无；`PackagedJarBootSmokeIT.java` 有 **未提交** 扩展（+142 行，覆盖 jar 直出嵌入产物），未见实跑记录 | AT-064 只完成了 jar 健康与迁移部分；"jar+MySQL/Redis+浏览器三件同时在位"与嵌入产物直出未验证 | **部分未验证**（测试已写/未提交/未跑） | **S2**（AT-064 未全绿；不影响其他 AT 的既有结论） | 提交扩展并在有 Docker 环境跑 `-Dit.test=PackagedJarBootSmokeIT`，回填 Q09 证据；责任卡：Q09/主管 |

## 4. 环境缺口（未验证项，均不得当 pass）

| 编号 | 标题 | 来源（卡/证据/命令） | 影响面 | 现状 | 级别（依据） | 建议动作与责任卡 |
|---|---|---|---|---|---|---|
| R-31 | Trivy 漏洞库镜像不可达 → `dependencies` 门禁未执行 | F02 证据 §7/§10.8；Q02 §6、Q03 §8.5、Q05 §6.5、Q08 验收证据 §3、配置手册 §7.4 均登记同一缺口 | 交付包/镜像的 HIGH/CRITICAL 漏洞结论**未出**；SBOM 存在但无扫描阻断结论 | **环境缺口** | **S1（阻断"发布评审通过"）**：Q10 §4 要求"未通过关键门禁"不得验收，08 §6 要求 SBOM/依赖证据；本机无法产出该结论 | 在漏洞库可达环境补跑 `sh .harness/verify.sh dependencies`，或由主管书面豁免并写入发布说明；责任卡：主管串行 |
| R-32 | 无真实模型端点 / 出站默认拒绝 → 模型效果与评测数值未验证 | Q05 证据 §6.1；[首轮评测报告](../acceptance/q05-first-round-evaluation-report.md) §3；Q07 §4（`model-dependent: not configured`） | AT-026/027/031/035 的"实际判定"、60+20 夹具的数值正确率、报表对话修改等均未验证；不是"通过" | **环境缺口** | **S2**（08 §1 证据层级：Mock 不得冒充模型效果；发布说明必须分开写） | 预发布环境接真实端点跑固定集；责任卡：需授权/新环境 |
| R-33 | 无真实 N-1 产物 → AT-057"真实旧产物验证"不可执行 | [Q08 升级演练与回退](../upgrades/q08-upgrade-rehearsal-and-rollback.md) §1/§2.1；Q08 验收证据 §1；兼容产物清单 §1 | 兼容结论只能是"首发基线夹具验证"；SDK 旧二进制在 gitignore 的 `dist/` 未随仓库保留 | **环境缺口** | **S2**（FR-34 明确要求按"首发基线验证"报告，不得写"已验证历史版本"） | 首个真实升级窗口产生旧产物后补；责任卡：后续升级卡 |
| R-34 | AT-056（embed/admin 安全头）skipped：本机 48080 无后端 | Q06 证据 §2/§5；Q07 前端切片 §7.6 | embed 只允配置域 / admin 保持保护，**未验证**；断言已写全，提供 `Q06_EMBED_APP_CODE`/`Q06_EMBED_ALLOWED_ORIGIN` 即可跑 | **环境缺口** | **S2**（安全相关验收未完成，不得当 pass） | 在有后端+允许域环境补跑；责任卡：Q10/部署环境 |
| R-35 | AT-067 报表页网络断言 skipped | Q06 证据 §2；部署与回退说明 §4 | "禁公共 CDN 下报表可用"只有构建期扫描与登录页证据，报表页运行期未验证 | **环境缺口** | **S3**（与 R-09/R-10 同域；登录页已过） | 有登录态/后端环境补跑；责任卡：同 R-34 |
| R-36 | Windows runner / CI 未实跑 | Q06 证据 §5.3；Q07 前端切片 §7.7 | 浏览器套件只在 Linux + Chromium 153.0.8010.12 本机跑过；`smoke` 门禁已接线（`.github/workflows/nightly-browser-smoke.yml`、`.harness` 双 provider，已核对存在）但未在 CI 触发过 | **环境缺口** | **S3**（门禁接线已落地；CI 首跑未发生） | 首次 CI 触发后回填；责任卡：主管/CI |
| R-37 | 索引快照**跨实例/跨版本**恢复未验证 | [恢复演练记录](../operations/q09-restore-drill.md) §6.1/§6.2；K01 §5.5 | 只验证了同实例 `recover`；"集合被整体删除后能否直接 recover""新 Qdrant 实例恢复"未验证；平台无快照 API 生产封装 | **环境缺口** | **S2**（AT-030 的"旧别名恢复可用"在平台等价物上仅同实例验证；回退方案依赖快照） | 新开卡实现快照 API 封装或补跨实例演练；责任卡：需新卡 |
| R-38 | 真实网关（Nginx 头部透传、`frame-ancestors` 经反代）未验证 | Q06 证据 §5.4；部署与回退说明 §4.2 | 生产入口的响应头行为（尤其嵌入路径不得补回 XFO）无实测 | **环境缺口** | **S3** | 预发布环境补；责任卡：部署环境 |
| R-39 | 生产/预发布环境从未实跑部署与升级检查单 | 部署与回退说明 §6 第 4 条；升级检查单 §6 第 1 条 | 所有部署/升级/回退命令都未在目标环境执行；文档给的是"可执行命令+判据" | **环境缺口** | **S2**（08 §6 发布证据要求操作者与时间；无授权不得执行生产动作——Q09 §4） | 用户授权后按升级检查单执行并回填记录；责任卡：主管/用户 |
| R-40 | Redis 恢复、生产规模 RTO/RPO、binlog 时间点恢复未验证 | 恢复演练记录 §7.1/§7.3/§7.4/§7.5 | 演练为 270 KiB/83 表/单机容器：RTO 39–56 s、RPO 窗口 0.2–0.6 s **不可外推**；Redis 全丢恢复未验证；恢复窗口内未真正停机（只关 Quartz） | **环境缺口** | **S2**（08 §5 性能/稳定性与 NFR-07：实际恢复演练取证；数字口径已在演练文档写明） | 生产规模演练；责任卡：需授权 |
| R-41 | 容量结论只对本机 16 vCPU/31 GiB 成立 | Q07 后端切片 §3.4/§6.7 | NFR-04 建议基线 8 vCPU/16 GB 未复测；"受理 P95 189/217 ms、查询 P95 91/128 ms 达标"只对本机；未做长时间/大并发/真实模型长尾 | **环境缺口** | **S3**（阈值判定成立，硬件可比性已声明） | 按 NFR-04 建议配置复测；责任卡：后续性能卡 |
| R-42 | AT-018 用"租约过期 + 恢复 Job"模拟重启，未做真实 JVM/容器重启 | Q07 后端切片 §3.1 注/§6.2 | 重启语义（jar/容器重启）与恢复窗口未端到端验证 | **环境缺口** | **S3** | Testcontainers+jar 完整形态；责任卡：后续卡 |
| R-43 | 浏览器票据为夹具而非平台签发；AT-039 的 `UNKNOWN` 分支未构造出 | Q06 证据 §5.5；Q07 后端切片 §6.6 | 换票断言覆盖"票据去向与隔离"，平台签发需真实后端凭据（A04）；后端完整性闭集为 `COMPLETE/PARTIAL/FAILED` | **环境缺口** | **S3**（已在证据中显式声明） | 真后端环境补；责任卡：同 R-34 |
| R-44 | AT-065 候选升级回归无可执行对象；AT-072 注入演示未在真实 MySQL 执行 | Q08 验收证据 §2/§3；Q08 后端切片 §6.5 | 上游 1.1.8/5.4.8 均为维护线末版，无更高候选；迁移碰撞用测试内校验器 + 真实迁移目录证明（38 例），生产 Flyway 的拒绝演示待真实库 | **环境缺口** | **S3**（机制在、基线与演练已建；"升级前后对比"未发生） | 上游发补丁/有 Docker 时补；责任卡：后续升级卡 |
| R-45 | 密钥轮换未演练；无批量重加密工具 | 配置手册 §1/§7.5；恢复演练记录 §7.6 | 换主密钥必须人工逐条重存全部 L3 凭据（密文不带密钥标识）；轮换方案未演练 | **未修 + 未演练** | **S3**（常态不轮换不受影响；轮换时的操作风险） | 需新卡提供批量重加密工具或书面接受人工流程 |

## 5. 轻微/已闭环的观察（登记备查，无动作）

| 编号 | 事项 | 来源 | 状态 |
|---|---|---|---|
| R-46 | Q06 证据文件曾被 F04 提交批量暂存（bookkeeping 瑕疵） | Q06 证据 §1.b | 已由主管记录，内容随本卡生效；无需动作 |
| R-47 | Q05 发布门槛只有服务层入口（无 controller），页面不能"记录评估" | Q05 证据 §3.1 | fail-closed 取舍；放开 controller 时补入口，属后续卡 |
| R-48 | 候选版本无法直接评测（评测走"当前已发布版本"受理路径） | Q05 证据 §3.2 | fail-closed；需 O02/S02 接缝支持固定候选版本 |
| R-49 | Q05 夹具中 133/215 条规则指向尚未写入的 `result.*` 结构 | Q05 证据 §3.3/§6.2 | 接线前按"缺失即失败"判定（不会被当通过）；与 R-15 同源 |
| R-50 | Q07 测试目录偏离（用例放在 `basic-framework-server/.../integration` 而非 module-ai） | Q07 后端切片 §1/§8.2 | 待主管确认；不影响结论 |

## 6. 分级汇总与阻断判定

| 级别 | 条数 | 编号 |
|---|---:|---|
| S1 阻断 | 2 | R-27（Qdrant 生产装配缺失，若发布范围含知识库）、R-31（Trivy/`dependencies` 门禁未跑） |
| S2 高 | 20 | R-09, R-10, R-13, R-14, R-15, R-16, R-17, R-23, R-24, R-25, R-26, R-29, R-30, R-32, R-33, R-34, R-37, R-39, R-40, R-51 |
| S3 中 | 15 | R-11, R-12, R-18, R-19, R-21, R-22, R-28, R-35, R-36, R-38, R-41, R-42, R-43, R-44, R-45 |
| S4 低 | 1 | R-20 |
| 已闭环 | 8 | R-01..R-08 |
| 合计（分级项） | 46 | R-01..R-45、R-51 |

> 计数说明：R-51 为插入项（避免打乱已引用的 R-09..R-45 编号）；另有无动作的观察项 R-46..R-50（不计入分级）。
> 分级是**本表编写者对"是否阻断发布"的判断**，依据已写在每条的"级别"列；最终是否阻断由 Q10 验收评审（§4/§5）与产品安全评审决定。

**阻断发布的项（我的判定，供评审）**：

1. **R-31（Trivy/`dependencies` 门禁未执行）** —— 发布证据链缺"依赖与镜像无 HIGH/CRITICAL 阻断"结论。
   依据：Q10 §4"无阻断缺陷、未知鉴权结论或未通过关键门禁"；08 §6 发布证据清单要求依赖锁与 SBOM/门禁报告；
   Q08/配置手册均把该门禁列为必跑项。**处置**：在漏洞库可达环境补跑，或用户/主管书面豁免并写入发布说明。
2. **R-27（Qdrant 生产装配缺失）** —— 若本次发布范围包含知识库/RAG 与引用能力，则真实部署中该能力不可用（静默降级）。
   依据：配置手册 §3 #6/§7.3 明确"生产装配待补"；消费方 `ObjectProvider#getIfAvailable` 不抛错。
   **处置**：新卡装配基址/API Key 配置键与 Bean；或产品书面确认"知识库不随本发布上线"并在发布说明写明。
3. **R-24 + R-23（交付包为 dirty 构建 + 交付包/索引装配证据待补）** —— 阻断的是"候选转正式发布"，
   不是候选本身：正式发布物必须从发布提交重建，且按 08 §6 留出包实跑记录。
   **处置**：在发布提交上重跑 `node deploy/package-delivery.mjs --offline`，回填 Q09 证据 §交付包。

**不阻断但必须在发布说明"已知限制"里出现**：R-09、R-10、R-11、R-13、R-14、R-15、R-16、R-17、R-21、
R-25（提交后消失）、R-29、R-33、R-34、R-35、R-37、R-40、R-41。

**与 AT 总表（第一片 [q10-v1-acceptance-matrix.md](q10-v1-acceptance-matrix.md) §2.4）阻断项 B1–B8 的对应**（两片编号各自独立，合并评审时并查）：

| 第一片阻断项 | 本表的对应/补充 |
|---|---|
| B1 模型效果与评测未执行（AT-026/027/035） | R-32（无真实模型端点）；R-49 记录评测规则未接线 |
| B2 运行链路缺常驻消费者（`RUN_STEP`） | R-17 |
| B3 AT-067 未全覆盖（web-ele/icons/icon-picker 运行期外发） | R-09、R-10、R-11（+R-35 报表页 skipped） |
| B4 AT-056 浏览器安全头未验证 | R-34 |
| B5 AT-059 运行链路未接配额占位 | R-16（+R-14 限额配置、R-06 已修的服务层并发） |
| B6 AT-063/064 缺实跑证据、Q09 出包证据待补 | R-23、R-24、R-30（+R-42 真实重启） |
| B7 `dependencies` 门禁不可跑 | R-31 |
| B8 AT-002 状态码口径冲突（422 vs 400） | R-51 |

## 7. 复核命令（只读，均可复跑）

```bash
cd /home/ctyun/桌面/zhongtai/ai-platform

# 工作树/提交基线（本表核对基准）
git log --oneline -1                                  # 2e208de fix(report): align generated spec shape...
git status --short | wc -l                            # 29 项未提交变更

# R-09/R-10：图标外发点
grep -rn "lucide:" 前端代码/basic-framework-admin/apps/web-ele/src --include=*.vue --include=*.ts | grep -v '\.test\.' | wc -l
grep -rn "MdiKeyboardEsc\|AntdProfileOutlined" 前端代码/basic-framework-admin/packages/icons/src
# R-11：图标选择器主动拉取
grep -rn "api.iconify.design" 前端代码/basic-framework-admin/packages/effects/common-ui/src/components/icon-picker/

# R-24：交付包版本事实
cat deploy/dist/*/version.json

# R-25/R-26：compose 渲染与必填变量（不启动容器）
cd 后端代码/basic-framework-boot
docker compose --env-file /dev/null -f docker-compose.yaml config -q            # 工作树：exit 1，逐项列出缺失必填变量
docker compose -f <(git show HEAD:./docker-compose.yaml) config -q              # HEAD 基线：exit 1，go-yaml load error at L65.C27
env DB_USERNAME=u DB_PASSWORD=p FLYWAY_USERNAME=f FLYWAY_PASSWORD=fp REDIS_PASSWORD=rp \
    MYSQL_ROOT_PASSWORD=mr CREDENTIAL_ENCRYPTION_KEY=$(python3 -c "import base64;print(base64.b64encode(b'0'*32).decode())") \
    CORS_ALLOWED_ORIGIN=https://admin.example.com docker compose -f docker-compose.yaml config -q   # 工作树：exit 0
cd -

# R-27：Qdrant 装配（无 @Bean/@Component）
grep -rn "QdrantRestKnowledgeIndexAdapter" 后端代码/basic-framework-boot/*/src/main | head
grep -rn "KnowledgeIndexPort" 后端代码/basic-framework-boot/basic-framework-server/src/main | head

# R-28：文件配置无种子行
grep -c "INSERT INTO \`infra_file_config\`" 数据库文件/basic_framework.sql    # 期望：0

# R-29：AI 接缝不在模板
grep -n "basic-framework.ai" 后端代码/basic-framework-boot/.env.example 后端代码/basic-framework-boot/docker-compose.yaml deploy/config/app.env.example

# R-30：嵌入产物直出用例是否存在
find 后端代码 -name "PackagedJarEmbedAssetsIT.java"
git diff --stat 后端代码/basic-framework-boot/basic-framework-server/src/test/java/com/basicframework/server/integration/PackagedJarBootSmokeIT.java

# R-13/R-14/R-15/R-16：保留期/限额/账本/占位接线
grep -rn "acquire(" 后端代码/basic-framework-boot/basic-framework-module-ai/src/main --include=*.java | head

# 迁移链现状（R-23 相关口径）
ls 后端代码/basic-framework-boot/basic-framework-server/src/main/resources/db/migration/*.sql | wc -l   # 82
grep -n "Snapshot note" 数据库文件/basic_framework.sql                                                  # through V83
```

## 8. 与其他交付物的关系

- AT 逐条结论与证据索引：见 Q10 另一片 [q10-v1-acceptance-matrix.md](q10-v1-acceptance-matrix.md)（本表只在"影响面"列引用相关 AT 编号，不重复其结论表；两片的阻断项对应关系见 §6 末表）。
- 发布候选清单（交付物、升级/回退材料、已知限制、部署前置、评审检查点）：见 [q10-release-candidate.md](q10-release-candidate.md)。
- 升级与回退方法论：[升级检查单](../operations/upgrade-checklist.md)、[Q08 演练与回退](../upgrades/q08-upgrade-rehearsal-and-rollback.md)、[部署与回退说明](../deployment/deployment-and-rollback.md)。
- 未验证项的原始记录：各卡证据的"未验证项"小节（Q05 §6、Q06 §5、Q07 两片 §6/§7、Q08 两片 §6、Q09 恢复演练 §7）。

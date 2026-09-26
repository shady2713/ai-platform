# AI 错误码与 HTTP 映射（F08 冻结）

错误码区间由 `backend` 侧的 `AiErrorCodeRanges` / `AiErrorCodeConstants` 定义，本表是**手工同步的映射契约**：
新增错误码必须同时更新两侧，编号一经发布不得改语义（错误码是长期协议）。

## 区间分配

| 区间 | 能力域 | 说明 |
|---|---|---|
| `1_003_001_xxx` | 通用/协议 | 入参、幂等、状态冲突、资源不可见、权限、配额 |
| `1_003_002_xxx` | 模型中心 | 端点、凭据、调用、能力 |
| `1_003_003_xxx` | 应用与授权 | 应用、外部主体、票据、范围 |
| `1_003_004_xxx` | 运行与任务 | 运行、任务、取消、重试 |
| `1_003_005_xxx` | 知识库 | 文档、解析、索引、检索授权 |
| `1_003_006_xxx` | 数据与工具 | 连接器、查询计划、工具执行 |
| `1_003_007_xxx` | 报表与 Chat | 报表、主题、嵌入会话 |
| `1_003_008_xxx` | 服务配置 | 服务发布、评测门槛、运行快照、版本回退 |

框架与基础设施占用 `1_001_xxx_xxx`，system 模块占用 `1_002_xxx_xxx`；AI 中台独占 `1_003`，不与既有区间交叉。

## HTTP 映射

| 错误码 | 名称 | HTTP | 语义 |
|---|---|---|---|
| 1_003_001_000 | AI_REQUEST_INVALID | 400 | 入参非法（长度、枚举、格式） |
| 1_003_001_001 | AI_IDEMPOTENCY_CONFLICT | 409 | 同键不同摘要 |
| 1_003_001_002 | AI_STATE_CONFLICT | 409 | 当前状态不允许该操作 |
| 1_003_001_003 | AI_RESOURCE_NOT_FOUND | 404 | 不存在或无权访问（同语义，防枚举） |
| 1_003_001_004 | AI_ACCESS_DENIED | 403 | 已认证但缺少范围/权限 |
| 1_003_001_005 | AI_QUOTA_EXCEEDED | 429 | 超配额或限流 |
| 1_003_002_000 | AI_MODEL_ENDPOINT_NOT_FOUND | 404 | 端点不存在 |
| 1_003_002_001 | AI_MODEL_ENDPOINT_DISABLED | 409 | 端点停用 |
| 1_003_002_002 | AI_MODEL_CAPABILITY_UNSUPPORTED | 400 | 端点不支持所需能力 |
| 1_003_002_003 | AI_MODEL_CALL_FAILED | 502 | 上游模型失败（不外泄上游正文） |
| 1_003_002_004 | AI_MODEL_ENDPOINT_NAME_DUPLICATE | 400 | 端点名称重复 |
| 1_003_002_005 | AI_MODEL_EMBEDDING_DIMENSION_CHANGED | 409 | 嵌入维度与既有索引记录不一致，拒绝写入既有索引 |
| 1_003_002_006 | AI_MODEL_OUTBOUND_BLOCKED | 403 | 资源等级不允许外发到该端点（策略拒绝先于网络调用） |
| 1_003_003_000 | AI_APPLICATION_NOT_FOUND | 404 | 应用不存在 |
| 1_003_003_001 | AI_APPLICATION_CODE_DUPLICATE | 409 | 应用标识重复（appCode 唯一且不可改） |
| 1_003_003_002 | AI_APPLICATION_ORIGIN_INVALID | 400 | Origin 非法（只接受精确来源） |
| 1_003_003_003 | AI_APPLICATION_CREDENTIAL_INVALID | 401 | 客户端凭据无效（不存在/未启用/错误/已吊销同语义） |
| 1_003_003_007 | AI_RESOURCE_GRANT_NOT_FOUND | 404 | 资源授权不存在 |
| 1_003_003_008 | AI_RESOURCE_GRANT_DUPLICATE | 409 | 同一主体对同一资源类型的同一资源已有授权 |
| 1_003_003_009 | AI_AUTHORIZATION_DENIED | 403 | 授权判定拒绝（应用/主体/范围/授权/动作任一不满足） |
| 1_003_003_010 | AI_TICKET_INVALID | 401 | 访问票据无效或已过期（不存在/撤销/过期/应用或主体不可用同语义） |
| 1_003_004_000 | AI_RUN_NOT_FOUND | 404 | 运行不存在或无权访问 |
| 1_003_004_001 | AI_RUN_ALREADY_TERMINAL | 409 | 运行已终态 |
| 1_003_004_002 | AI_CONVERSATION_KEY_DUPLICATE | 409 | 会话业务键重复（同一应用+主体内唯一） |
| 1_003_004_003 | AI_RUN_NOT_EXECUTABLE | 409 | 运行缺少可执行输入（例如无会话运行没有可回放的输入消息） |
| 1_003_004_004 | AI_RUN_BUDGET_EXCEEDED | 429 | 超出运行预算（步数/耗时/工具次数） |
| 1_003_004_005 | AI_TOOL_UNSUPPORTED | 400 | 当前不支持工具调用（没有受控工具实现） |
| 1_003_004_006 | AI_RUN_EVENT_WINDOW_EXPIRED | 409 | 事件重放窗口已过期，请读取运行快照而不是重新发起运行 |
| 1_003_004_007 | AI_TASK_NOT_RETRYABLE | 409 | 任务当前状态不允许重试（含结果未知 UNKNOWN） |
| 1_003_005_000 | AI_KNOWLEDGE_BASE_NOT_FOUND | 404 | 知识库不存在（越权与不存在同语义） |
| 1_003_005_001 | AI_KNOWLEDGE_BASE_CODE_DUPLICATE | 409 | 知识库标识重复 |
| 1_003_005_002 | AI_KNOWLEDGE_BASE_CONFIG_INVALID | 400 | 知识库配置不合规（可见性/模型/维度/保留策略） |
| 1_003_005_003 | AI_KNOWLEDGE_BASE_DISABLED | 409 | 知识库已停用，不接受新入库与索引换代 |
| 1_003_005_004 | AI_KNOWLEDGE_BASE_REFERENCED | 409 | 知识库被服务绑定引用，不能删除 |
| 1_003_005_005 | AI_KNOWLEDGE_BASE_NOT_EMPTY | 409 | 知识库下仍有文档，不能删除 |
| 1_003_005_006 | AI_KNOWLEDGE_DOCUMENT_NOT_FOUND | 404 | 知识文档不存在 |
| 1_003_005_007 | AI_KNOWLEDGE_SOURCE_KEY_INVALID | 400 | 来源幂等键/标题/指纹不合法 |
| 1_003_005_008 | AI_KNOWLEDGE_VERSION_NOT_FOUND | 404 | 文档版本不存在 |
| 1_003_005_009 | AI_KNOWLEDGE_VERSION_IMMUTABLE | 409 | 版本已可用（READY/SUPERSEDED），不可修改 |
| 1_003_005_010 | AI_KNOWLEDGE_VERSION_STATE_INVALID | 409 | 当前版本/文档/索引代状态不允许该操作 |
| 1_003_005_011 | AI_KNOWLEDGE_FILE_REQUIRED | 400 | 文档版本必须绑定私有文件 |
| 1_003_005_012 | AI_KNOWLEDGE_GENERATION_CONFLICT | 409 | 索引代状态冲突或维度/模型与知识库不一致 |
| 1_003_005_013 | AI_KNOWLEDGE_CHUNK_INVALID | 400 | 切片数据不合法（序号/哈希/向量标识/长度） |
| 1_003_005_014 | AI_KNOWLEDGE_FILE_TYPE_UNSUPPORTED | 400 | 文件类型不支持（首期 TXT/Markdown/文本型 PDF/DOCX） |
| 1_003_005_015 | AI_KNOWLEDGE_FILE_TOO_LARGE | 400 | 文件超出单文件上限 |
| 1_003_005_016 | AI_KNOWLEDGE_FILE_INVALID | 400 | 文件不合法或不属于该知识库（purpose/归属不符） |
| 1_003_005_017 | AI_KNOWLEDGE_INGESTION_TASK_NOT_FOUND | 404 | 入库任务不存在 |
| 1_003_005_018 | AI_KNOWLEDGE_INGESTION_TASK_STATE_INVALID | 409 | 当前入库任务状态不允许该操作 |
| 1_003_007_000 | AI_REPORT_SPEC_INVALID | 400 | 报表结构不合规（键白名单/取值域/上限） |
| 1_003_007_001 | AI_REPORT_SCRIPT_REJECTED | 400 | 报表包含 HTML/脚本/样式片段，已拒绝 |
| 1_003_007_002 | AI_REPORT_REFERENCE_INVALID | 400 | 报表引用了不存在的块、数据集或查询 |
| 1_003_007_003 | AI_REPORT_LAYOUT_INVALID | 400 | 报表布局越界或重叠 |
| 1_003_007_004 | AI_REPORT_CHART_FIELD_INVALID | 400 | 图表字段缺失或类型不符 |
| 1_003_007_005 | AI_REPORT_BINDING_MISMATCH | 409 | 报表声明与执行结果不一致 |
| 1_003_007_006 | AI_REPORT_MODEL_UNAVAILABLE | 503 | 报表生成模型未装配 |
| 1_003_007_007 | AI_REPORT_NOT_FOUND | 404 | 报表不存在（越权访问他人报表同码） |
| 1_003_007_008 | AI_REPORT_VERSION_NOT_FOUND | 404 | 报表版本不存在 |
| 1_003_007_009 | AI_REPORT_CODE_DUPLICATE | 409 | 报表标识重复（同一应用内 code 唯一且不可改） |
| 1_003_007_010 | AI_REPORT_SNAPSHOT_DATA_REQUIRED | 400 | 快照报表必须携带数据 |
| 1_003_007_011 | AI_REPORT_SCOPE_CHANGED | 409 | 授权范围已变化，拒绝展示旧产物，需重新生成 |
| 1_003_007_012 | AI_REPORT_SOURCE_RUN_NOT_FOUND | 404 | 来源运行不存在或不属于当前主体（跨用户保存同语义） |
| 1_003_007_013 | AI_REPORT_REVISION_PLAN_INVALID | 400 | 修订计划不合规（操作码白名单/必填参数/块引用） |
| 1_003_007_014 | AI_REPORT_REVISION_UNSUPPORTED | 400 | 修订操作无法按当前数据完成（如查询结果为空仍要新增指标） |
| 1_003_007_015 | AI_REPORT_REVISION_QUERY_SCOPE_REQUIRED | 409 | 数据类修订缺少行范围上下文（拒绝而非查全库） |
| 1_003_007_016 | AI_REPORT_REVISION_MODEL_UNAVAILABLE | 503 | 报表修订模型未装配（fail-closed） |
| 1_003_007_017 | AI_REPORT_REFRESH_NOT_SUPPORTED | 400 | 该报表不支持刷新（仅可刷新报表） |
| 1_003_007_018 | AI_REPORT_REFRESH_SCOPE_REQUIRED | 409 | 刷新需要行范围上下文（留痕并保留旧结果） |
| 1_003_007_019 | AI_THEME_TOKENS_INVALID | 400 | 主题 token 不合规（未知字段/非法色值/半径越界） |
| 1_003_007_020 | AI_THEME_FONT_NOT_ALLOWED | 400 | 主题字体不在自托管白名单内（拒绝远程字体与任意 CSS） |
| 1_003_007_021 | AI_THEME_LAYOUT_INVALID | 400 | 主题布局选项不合规（只接受受控枚举与区间） |
| 1_003_007_022 | AI_THEME_NOT_FOUND | 404 | 主题修订不存在 |
| 1_003_007_023 | AI_THEME_REVISION_IMMUTABLE | 409 | 主题修订已发布，不能修改（调整需新建修订） |
| 1_003_007_024 | AI_THEME_VERSION_CONFLICT | 409 | 主题发布/回退并发冲突（乐观锁或唯一键判负） |
| 1_003_006_000 | AI_CONNECTOR_NOT_FOUND | 404 | 连接器不存在 |
| 1_003_006_001 | AI_CONNECTOR_CODE_DUPLICATE | 409 | 连接器标识重复（code 唯一且不可改） |
| 1_003_006_002 | AI_CONNECTOR_CONFIG_INVALID | 400 | 连接器配置不合规（只接受声明式白名单字段） |
| 1_003_006_003 | AI_CONNECTOR_REFERENCED | 409 | 连接器已被数据集/工具引用，不能删除 |
| 1_003_006_004 | AI_CONNECTOR_DISABLED | 409 | 连接器已停用，不允许探测或发起连接 |
| 1_003_006_005 | AI_CONNECTOR_OPERATION_NOT_FOUND | 404 | 连接器操作不存在 |
| 1_003_006_006 | AI_CONNECTOR_OPERATION_NOT_PUBLISHED | 409 | 连接器操作尚未发布，不能执行 |
| 1_003_006_007 | AI_CONNECTOR_IMPORT_INVALID | 400 | OpenAPI 文档不可导入（格式/上限/无可导入操作） |
| 1_003_006_008 | AI_CONNECTOR_ORIGIN_MISMATCH | 400 | 目标地址与连接器 Origin 不一致（SSRF 防线） |
| 1_003_006_009 | AI_CONNECTOR_ARGUMENT_INVALID | 400 | 连接器参数不合法（未声明/必填缺失/含查询语法） |
| 1_003_006_010 | AI_CONNECTOR_OBJECT_NOT_AUTHORIZED | 403 | 目标 schema/表/视图不在连接器授权白名单内（默认拒绝） |
| 1_003_006_011 | AI_CONNECTOR_SQL_NOT_READ_ONLY | 400 | 只允许单条只读 SELECT/WITH（禁 DML/DDL/文件函数/多语句） |
| 1_003_006_012 | AI_CONNECTOR_RESULT_TOO_LARGE | 400 | 查询结果超出上限（行数/列数/单值长度） |
| 1_003_006_013 | AI_CONNECTOR_QUERY_TIMEOUT | 502 | 连接器查询超时（已请求上游中断） |
| 1_003_006_014 | AI_CONNECTOR_QUERY_CANCELLED | 409 | 连接器查询被调用方取消 |
| 1_003_006_015 | AI_CONNECTOR_MYSQL_UNAVAILABLE | 502 | 只读连接不可用（建池/取连接/连接中断） |
| 1_003_006_016 | AI_CONNECTOR_MYSQL_VERSION_UNSUPPORTED | 409 | 上游不是受支持的 MySQL 8 |
| 1_003_006_017 | AI_CONNECTOR_QUERY_FAILED | 502 | 上游查询失败（语法/权限/上游错误，不回上游正文） |
| 1_003_006_018 | AI_DATASET_NOT_FOUND | 404 | 数据集不存在 |
| 1_003_006_019 | AI_DATASET_CODE_DUPLICATE | 409 | 数据集标识重复（code 唯一且不可改） |
| 1_003_006_020 | AI_DATASET_SOURCE_NOT_AUTHORIZED | 403 | 来源对象不在连接器授权白名单内 |
| 1_003_006_021 | AI_DATASET_DEFINITION_INVALID | 400 | 语义定义不合规（未知键/枚举外取值/越界/缺权限策略） |
| 1_003_006_022 | AI_DATASET_ALIAS_AMBIGUOUS | 400 | 字段别名有歧义（重复或与字段/指标/维度名冲突） |
| 1_003_006_023 | AI_DATASET_VERSION_NOT_FOUND | 404 | 数据集版本不存在 |
| 1_003_006_024 | AI_DATASET_VERSION_IMMUTABLE | 409 | 已发布版本不可修改，只能新建版本 |
| 1_003_006_025 | AI_DATASET_VERSION_DRIFTED | 409 | 上游结构已漂移，必须重新验证 |
| 1_003_006_026 | AI_DATASET_VERSION_NOT_VERIFIED | 409 | 版本未通过验证，不能发布 |
| 1_003_006_027 | AI_DATASET_FIELD_UNKNOWN | 400 | 定义引用了上游不存在的列 |
| 1_003_006_028 | AI_DATASET_REFERENCED | 409 | 数据集被报表等引用，不能删除 |
| 1_003_006_029 | AI_DATASET_DISABLED | 409 | 数据集已停用 |
| 1_003_006_030 | AI_QUERY_PLAN_INVALID | 400 | 查询计划不合规（结构/字段/操作符/时间/参数） |
| 1_003_006_031 | AI_QUERY_SQL_REJECTED | 400 | 模型返回 SQL 片段，已拒绝 |
| 1_003_006_032 | AI_QUERY_DATASET_NOT_ALLOWED | 403 | 计划引用了授权外的数据集（不允许扩大范围） |
| 1_003_006_033 | AI_QUERY_CLARIFICATION_REQUIRED | 409 | 措辞/口径有歧义，需要澄清 |
| 1_003_006_034 | AI_QUERY_REPAIR_EXHAUSTED | 409 | 计划修复次数已用尽 |
| 1_003_006_035 | AI_QUERY_MODEL_OUTPUT_INVALID | 502 | 模型输出不是可用的 PLAN/CLARIFICATION |
| 1_003_006_036 | AI_DATASET_VERSION_NOT_PUBLISHED | 409 | 数据集版本未发布，不能用于查询 |
| 1_003_006_037 | AI_QUERY_COMPILE_FAILED | 400 | 查询计划无法编译（字段映射缺失/结构不支持） |
| 1_003_006_038 | AI_QUERY_SCOPE_REQUIRED | 403 | 缺少行范围授权，拒绝生成查询（不退回全库） |
| 1_003_006_039 | AI_QUERY_RESULT_FORMAT_DRIFT | 502 | 上游响应格式与 operation 声明不一致（格式漂移） |
| 1_003_006_040 | AI_QUERY_RESULT_INVALID | 400 | 结果值无法按声明语义类型归一 |
| 1_003_006_041 | AI_TOOL_NOT_FOUND | 404 | 工具不存在（伪造工具名） |
| 1_003_006_042 | AI_TOOL_CODE_DUPLICATE | 409 | 工具标识重复 |
| 1_003_006_043 | AI_TOOL_VERSION_NOT_FOUND | 404 | 工具版本不存在 |
| 1_003_006_044 | AI_TOOL_VERSION_NOT_PUBLISHED | 409 | 工具版本未发布，不能执行 |
| 1_003_006_045 | AI_TOOL_POLICY_DENIED | 403 | 工具政策为 DENY，禁止执行 |
| 1_003_006_046 | AI_TOOL_CONFIRMATION_REQUIRED | 409 | 工具执行需要人工确认（CONFIRM） |
| 1_003_006_047 | AI_TOOL_ARGUMENT_INVALID | 400 | 工具参数不合法（未声明/必填缺失/类型不符） |
| 1_003_006_048 | AI_TOOL_TYPE_UNSUPPORTED | 400 | 首期只支持读工具 |
| 1_003_006_049 | AI_TOOL_REFERENCED | 409 | 工具被引用，不能删除 |
| 1_003_006_050 | AI_TOOL_ACTION_NOT_FOUND | 404 | 工具动作不存在（越权同语义） |
| 1_003_006_051 | AI_TOOL_ACTION_NOT_PENDING | 409 | 工具动作当前状态不允许该操作 |
| 1_003_006_052 | AI_TOOL_ACTION_EXPIRED | 409 | 工具动作已过期 |
| 1_003_006_053 | AI_TOOL_ACTION_CHALLENGE_INVALID | 403 | 确认挑战或主体不符 |
| 1_003_006_054 | AI_TOOL_ACTION_ARGUMENTS_CHANGED | 409 | 确认参数与发起时不一致，须重新确认 |
| 1_003_006_055 | AI_ANALYSIS_STEP_LIMIT_EXCEEDED | 429 | 分析步骤超出预算（步数/耗时） |
| 1_003_006_056 | AI_RUN_NOT_ACTIVE | 409 | 运行不在可继续状态（取消或终态） |
| 1_003_008_000 | AI_SERVICE_NOT_READY | 409 | 服务未标记可发布，不能创建发布候选 |
| 1_003_008_001 | AI_SERVICE_EVAL_MISSING | 409 | 缺少与候选内容匹配的评测结果（内容/端点配置变化后必须重新评测） |
| 1_003_008_002 | AI_SERVICE_EVAL_BELOW_THRESHOLD | 409 | 评测得分未达发布门槛 |
| 1_003_008_003 | AI_SERVICE_NOT_PUBLISHED | 409 | 服务没有生效的发布版本，新运行拒绝 |
| 1_003_008_004 | AI_SERVICE_RESOURCE_UNAVAILABLE | 409 | 发布版本依赖的资源绑定已解除或不可用，新运行拒绝 |
| 1_003_008_005 | AI_SERVICE_ENDPOINT_CONFIG_CHANGED | 409 | 端点配置版本已变化，需重建候选并重新评测 |
| 1_003_008_006 | AI_SERVICE_RELEASE_NOT_PUBLISHED | 409 | 发布版本尚未发布，不能用于运行解析或作为回退目标 |
| 1_003_008_007 | AI_SERVICE_RELEASE_PIN_STALE | 409 | 会话固定的发布内容已不一致，需要显式迁移会话 |
| 1_003_008_008 | AI_CONTEXT_BUDGET_EXCEEDED | 409 | 上下文超出输入预算：强制分区无法完整容纳 |
| 1_003_008_009 | AI_CONTEXT_SCHEMA_INVALID | 400 | 业务上下文不合规：不是 JSON 对象或含未注册字段 |

HTTP 语义遵循 [ADR 0003](../../adr/0003-http-status-semantics.md)；认证与授权边界见
[ADR 0049](../../adr/0049-ai-open-identity-and-security-extension-boundaries.md)。

## 评测评分子区间（Q04，`1_003_009_xxx`）

| 常量 | 码 | 语义 | HTTP |
|---|---|---|---|
| `AI_EVAL_SUITE_NOT_EXISTS` | 1_003_009_001 | 评测套件不存在 | 404 |
| `AI_EVAL_SUITE_CODE_DUPLICATE` | 1_003_009_002 | 同一应用下套件标识已存在 | 409 |
| `AI_EVAL_SUITE_HAS_NO_CASE` | 1_003_009_003 | 套件没有样例，不能冻结或执行 | 422 |
| `AI_EVAL_SUITE_FROZEN` | 1_003_009_004 | 套件已冻结，编辑请先创建新修订 | 422 |
| `AI_EVAL_SUITE_NOT_FROZEN` | 1_003_009_005 | 套件尚未冻结，不能创建新修订 | 422 |
| `AI_EVAL_CASE_NOT_EXISTS` | 1_003_009_006 | 评测样例不存在 | 404 |
| `AI_EVAL_CASE_KEY_DUPLICATE` | 1_003_009_007 | 套件内样例标识已存在 | 409 |
| `AI_EVAL_CHECK_INVALID` | 1_003_009_008 | 样例期望规则不合规 | 422 |
| `AI_EVAL_DATA_LEVEL_NOT_ALLOWED` | 1_003_009_009 | 评测样例只允许 L1_PUBLIC/L2_INTERNAL 分级 | 422 |
| `AI_EVAL_RUN_NOT_EXISTS` | 1_003_009_010 | 评测运行不存在 | 404 |
| `AI_EVAL_RESULT_NOT_EXISTS` | 1_003_009_011 | 评测结果不存在 | 404 |
| `AI_EVAL_RESULT_NOT_REVIEWABLE` | 1_003_009_012 | 该结果不需要人工复核或已复核 | 422 |
| `AI_EVAL_RUN_NOT_EXECUTED` | 1_003_009_013 | 评测用例未能取到执行租约（队列被其它任务占用） | 422 |

HTTP 状态按 ADR 0003 的命名规则推导：`*_NOT_EXISTS` 为 404，名称含 `DUPLICATE` 为 409，其余为 422。

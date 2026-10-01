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
| `1_003_009_xxx` | 评测 | 套件、样例、评测运行与结果、人工复核 |
| `1_003_010_xxx` | 多模态媒体（X01） | 媒体能力准入与媒体输入输出校验（图片理解/OCR/生成/编辑、非实时 STT/TTS） |
| `1_003_013_xxx` | 跨系统（Y01） | 主体联邦映射、多系统授权发现与范围选择（`1_003_011` 为 Webhook、`1_003_012` 为流程编排，见文末分节） |
| `1_003_017_xxx` | 跨源有界执行（Y04） | 来源预算与受控结束、各源时间点偏移、重试去重与容量拒绝/登记（`1_003_014` 为实时语音、`1_003_015` 为主数据映射、`1_003_016` 为跨源指标口径，见文末分节） |
| `1_003_018_xxx` | 跨系统授权与完整性（Y05） | 来源/映射/角色授权、合计与计数泄漏阻断、模型输入捕获复核（拒绝不可区分，详见文末分节） |
| `1_003_019_xxx` | 受控 MCP 客户端（X07） | 端点准入、协议版本漂移、授权被拒、发现有界终止、工具草稿审批与 schema 漂移阻断（详见文末分节） |
| `1_003_020_xxx` | 跨源结果契约（Y07） | 响应恒带完整性口径、执行台账缺失、结果不可出具（详见文末分节） |

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
| 1_003_002_007 | AI_MODEL_CAPABILITY_NOT_ENABLED | 400 | 端点未开通媒体能力（未声明或未通过探测确认，X01）：拒绝先于客户端解析与网络调用，不回退其它模型 |
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
| 1_003_007_025 | AI_EMBED_APP_NOT_EXISTS | 404 | 嵌入应用不存在或未启用（未知与未启用同语义，防枚举；后缀 `_NOT_EXISTS` 承重） |
| 1_003_007_026 | AI_EMBED_ORIGIN_INVALID | 422 | 应用未配置可用的嵌入允许域（必须是精确 Origin，fail-closed） |
| 1_003_007_027 | AI_EMBED_ASSETS_NOT_STAGED | 422 | 嵌入页构建产物未就位（资产目录或清单缺失/损坏） |
| 1_003_007_028 | AI_EMBED_ASSET_NOT_EXISTS | 404 | 请求的嵌入资产不在构建清单内（后缀 `_NOT_EXISTS` 承重） |
| 1_003_007_029 | AI_REPORT_SHARE_NOT_EXISTS | 404 | 分享不存在或不可读（凭据未知/非接收者/已撤销/已过期/授予者停用同语义，防枚举；稳定原因只进访问审计，X11） |
| 1_003_007_030 | AI_REPORT_SHARE_DUPLICATE | 409 | 同报表 + 同接收者的生效分享已存在（先撤销旧分享再创建，X11） |
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
| 1_003_006_048 | AI_TOOL_TYPE_UNSUPPORTED | 400 | 工具类型或来源不受支持 |
| 1_003_006_049 | AI_TOOL_REFERENCED | 409 | 工具被引用，不能删除 |
| 1_003_006_050 | AI_TOOL_ACTION_NOT_FOUND | 404 | 工具动作不存在（越权同语义） |
| 1_003_006_051 | AI_TOOL_ACTION_NOT_PENDING | 409 | 工具动作当前状态不允许该操作 |
| 1_003_006_052 | AI_TOOL_ACTION_EXPIRED | 409 | 工具动作已过期 |
| 1_003_006_053 | AI_TOOL_ACTION_CHALLENGE_INVALID | 403 | 确认挑战或主体不符 |
| 1_003_006_054 | AI_TOOL_ACTION_ARGUMENTS_CHANGED | 409 | 确认参数与发起时不一致，须重新确认 |
| 1_003_006_055 | AI_ANALYSIS_STEP_LIMIT_EXCEEDED | 429 | 分析步骤超出预算（步数/耗时） |
| 1_003_006_056 | AI_RUN_NOT_ACTIVE | 409 | 运行不在可继续状态（取消或终态） |
| 1_003_006_057 | AI_TOOL_WRITE_BINDING_INVALID | 400 | 写工具必须声明业务幂等键参数与核对查询（X06） |
| 1_003_006_058 | AI_TOOL_WRITE_POLICY_UNSUPPORTED | 400 | 写工具不允许 AUTO 政策，写调用必须人工确认（X06） |
| 1_003_006_059 | AI_TOOL_WRITE_BINDING_CHANGED | 409 | 写绑定在确认后发生变化，须重新确认（X06） |
| 1_003_006_060 | AI_TOOL_ACTION_IDEMPOTENCY_CONFLICT | 409 | 同一业务幂等键的动作已存在且参数不一致（X06） |
| 1_003_006_061 | AI_TOOL_ACTION_NOT_RECONCILABLE | 409 | 动作当前状态不允许核对（只有结果未定的动作可核对，X06） |
| 1_003_006_062 | AI_TOOL_ACTION_RECONCILE_FAILED | 502 | 核对查询失败，动作结果仍未确定（X06） |
| 1_003_006_063 | AI_TOOL_WRITE_REQUIRES_CONFIRMATION | 403 | 写工具只能经确认流程执行（通用执行入口拒绝写判定，X06） |
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
| `AI_EVAL_PUBLISH_RUN_NOT_FINISHED` | 1_003_009_014 | 评测运行未完成，不能作为发布依据 | 422 |
| `AI_EVAL_PUBLISH_SUITE_CHANGED` | 1_003_009_015 | 套件在评测之后被修改，需要按当前冻结内容重新评测 | 422 |
| `AI_EVAL_PUBLISH_CASE_COVERAGE_INCOMPLETE` | 1_003_009_016 | 评测没有覆盖全部冻结样例，禁止只挑部分样例计算通过率 | 422 |
| `AI_EVAL_PUBLISH_CASE_NOT_CONVERGED` | 1_003_009_017 | 存在未能执行或待人工复核的样例，不能作为发布依据 | 422 |
| `AI_EVAL_PUBLISH_RELEASE_MISMATCH` | 1_003_009_018 | 评测执行时的发布版本/内容摘要/端点修订与待发布候选不一致，换模型或改提示词必须重新评测 | 422 |
| `AI_EVAL_PUBLISH_BLOCKER_CASE_FAILED` | 1_003_009_019 | 阻断级评测样例未通过，禁止发布 | 422 |
| `AI_EVAL_PUBLISH_BELOW_THRESHOLD` | 1_003_009_020 | 评测通过率低于发布门槛 | 422 |

HTTP 状态按 ADR 0003 的命名规则推导：`*_NOT_EXISTS` 为 404，名称含 `DUPLICATE` 为 409，其余为 422。

## 多模态媒体子区间（X01，`1_003_010_xxx`）

媒体能力（图片理解/OCR/生成/编辑、非实时 STT/TTS）的准入与输入输出校验。准入拒绝
（`AI_MODEL_CAPABILITY_NOT_ENABLED`，模型中心子区间）与媒体输入输出错误分开：

| 常量 | 码 | 语义 | HTTP |
|---|---|---|---|
| `AI_MEDIA_REQUEST_INVALID` | 1_003_010_000 | 媒体请求不合规（尺寸/张数/格式/音色/文本长度） | 400 |
| `AI_MEDIA_INPUT_TYPE_UNSUPPORTED` | 1_003_010_001 | 输入媒体类型不在该能力声明的白名单内 | 400 |
| `AI_MEDIA_INPUT_TOO_LARGE` | 1_003_010_002 | 输入媒体超过端点声明的单文件上限 | 400 |
| `AI_MEDIA_INPUT_DURATION_EXCEEDED` | 1_003_010_003 | 音频时长超过端点声明上限 | 400 |
| `AI_MEDIA_OUTPUT_EMPTY` | 1_003_010_004 | 上游成功但未返回媒体产物，拒绝交付与落私有文件 | 502 |
| `AI_MEDIA_OUTPUT_INVALID` | 1_003_010_005 | 上游返回的媒体产物不合规（非白名单格式的真实媒体内容，或超过字节/像素上限），拒绝落私有文件 | 502 |
| `AI_MEDIA_OUTPUT_DURATION_EXCEEDED` | 1_003_010_006 | 上游音频产物超过平台时长上限（X04：非实时 TTS 单段 20 分钟）；时长未知时不冒充"未超限"，按未知处理 | 502 |

映射路径固定：`ModelException.Reason` → 平台错误码由 `AiMediaCapabilityGate` 统一完成
（`CAPABILITY_NOT_ENABLED` → 1_003_002_007；`MEDIA_INPUT_INVALID` → 1_003_010_000；
`MEDIA_INPUT_TYPE_UNSUPPORTED` → 1_003_010_001；`MEDIA_INPUT_TOO_LARGE` → 1_003_010_002；
`MEDIA_INPUT_DURATION_EXCEEDED` → 1_003_010_003；`MEDIA_OUTPUT_EMPTY` → 1_003_010_004；`MEDIA_OUTPUT_INVALID` → 1_003_010_005；
其余原因沿用 `AiModelFailureCodes` 的既有映射）。媒体失败响应不回传上游报文、输入内容或凭据。

## 受控异步结果 Webhook 子区间（X10，`1_003_011_xxx`）

运行终态结果投递的目标登记、事件白名单、投递管理与人工重投。前六个码是**接口响应码**，
第七个是**投递行的终态失败码**（投递行不是接口响应，但它是可核验的仓储事实，与接口码同目录登记）：

| 常量 | 码 | 语义 | HTTP |
|---|---|---|---|
| `AI_WEBHOOK_TARGET_NOT_FOUND` | 1_003_011_000 | Webhook 目标不存在或无权访问 | 404 |
| `AI_WEBHOOK_TARGET_DISABLED` | 1_003_011_001 | Webhook 目标已停用：不再入队、发送前复检拒绝、人工重投被拒 | 409 |
| `AI_WEBHOOK_TARGET_URL_INVALID` | 1_003_011_002 | 投递地址不合规（非 http/https、缺主机、URL 里带凭据信息、超长） | 400 |
| `AI_WEBHOOK_EVENT_TYPE_UNSUPPORTED` | 1_003_011_003 | 事件类型不在白名单内（只支持运行终态三种事件） | 400 |
| `AI_WEBHOOK_DELIVERY_NOT_FOUND` | 1_003_011_004 | 投递记录不存在 | 404 |
| `AI_WEBHOOK_DELIVERY_NOT_REDELIVERABLE` | 1_003_011_005 | 该投递状态不允许人工重投（只有死信可重投） | 409 |
| `AI_WEBHOOK_DELIVERY_EXHAUSTED` | 1_003_011_006 | 投递重试预算已耗尽（有界重试的终态结论文本码，落 `ai_webhook_delivery.failure_code`） | 502 |

投递行与尝试行还使用一组**传输原因码**（无对应接口响应，词表封闭在
`AiWebhookFailureCodes`，落 `last_error_code`/`failure_code`）：
`target-not-allowed`、`private-target-denied`、`redirect-not-followed`（受控出站不跟随重定向）、
`http-client-error`（4xx，含接收端按投递编号去重返回的 409）、`http-server-error`（5xx）、
`http-rate-limited`（429）、`timeout`、`connect-failed`、`response-too-large`、`request-invalid`、
`signing-key-unavailable`、`internal-error`。
其中前两个、`redirect-not-followed`、`http-client-error`、`response-too-large`、`request-invalid`、
`signing-key-unavailable` 与目标停用都是**确定失败**（不重试）；`timeout`、`connect-failed`、
`http-server-error`、`http-rate-limited`、`internal-error` 进入**有界退避重试**，超限后落
`1_003_011_006`。协议与接收端校验顺序见 [`webhook-protocol.md`](webhook-protocol.md)。
## 可视化流程编排子区间（X08，`1_003_012_xxx`）

| 名称 | 编号 | 说明 | HTTP |
|---|---|---|---|
| `AI_WORKFLOW_NOT_FOUND` | 1_003_012_000 | 流程不存在 | 404 |
| `AI_WORKFLOW_CODE_DUPLICATE` | 1_003_012_001 | 流程标识已存在（应用内唯一） | 409 |
| `AI_WORKFLOW_DISABLED` | 1_003_012_002 | 流程已停用，不能发起运行 | 409 |
| `AI_WORKFLOW_GRAPH_INVALID` | 1_003_012_003 | 流程图结构不合规 | 400 |
| `AI_WORKFLOW_GRAPH_CYCLE` | 1_003_012_004 | 流程图存在循环 | 400 |
| `AI_WORKFLOW_GRAPH_NO_EXIT` | 1_003_012_005 | 流程图存在无法到达结束的节点 | 400 |
| `AI_WORKFLOW_NODE_TYPE_MISMATCH` | 1_003_012_006 | 流程节点类型或端口不匹配 | 400 |
| `AI_WORKFLOW_NODE_REFERENCE_INVALID` | 1_003_012_007 | 流程节点引用了不存在或不可用的资源 | 400 |
| `AI_WORKFLOW_VERSION_NOT_FOUND` | 1_003_012_008 | 流程版本不存在 | 404 |
| `AI_WORKFLOW_VERSION_IMMUTABLE` | 1_003_012_009 | 流程版本已发布，不可修改 | 409 |
| `AI_WORKFLOW_VERSION_STATE_INVALID` | 1_003_012_010 | 当前流程版本状态不允许该操作 | 409 |
| `AI_WORKFLOW_DRAFT_EXISTS` | 1_003_012_011 | 流程已有打开的草稿版本 | 409 |
| `AI_WORKFLOW_RUN_NOT_FOUND` | 1_003_012_012 | 流程运行不存在 | 404 |

## 跨系统子区间（Y01，`1_003_013_xxx`）

| 名称 | 编号 | 说明 | HTTP |
|---|---|---|---|
| `AI_SUBJECT_FEDERATION_NOT_EXISTS` | 1_003_013_000 | 联邦映射不存在（编号无效、已删除、归属不符同语义：不回答"是否存在他人的映射"） | 404 |
| `AI_SUBJECT_FEDERATION_DUPLICATE` | 1_003_013_001 | 同一对身份已有待审批/已批准映射（撤销后允许重新提交并复用该行） | 409 |
| `AI_SUBJECT_FEDERATION_STATE_CONFLICT` | 1_003_013_002 | 当前状态不允许该操作（批准非 PENDING 映射、仍生效时的版本冲突） | 409 |
| `AI_SUBJECT_FEDERATION_APPROVER_CONFLICT` | 1_003_013_003 | 独立审批：批准人必须不同于提交人 | 409 |
| `AI_SUBJECT_FEDERATION_SUBJECT_UNAVAILABLE` | 1_003_013_004 | 映射涉及的主体未登记或已停用（提交与批准两次核验） | 422 |
| `AI_SYSTEM_CATALOG_BUDGET_EXCEEDED` | 1_003_013_005 | 单系统授权条数超过发现预算，拒绝返回不完整目录 | 422 |
| `AI_ANALYSIS_SCOPE_DENIED` | 1_003_013_006 | 范围选择包含当前主体不可访问的系统（fail closed，不静默缩小范围） | 422 |
| `AI_ANALYSIS_SCOPE_VERSION_CONFLICT` | 1_003_013_007 | 范围选择依据的目录/映射事实已变化，需重新发现后再选择 | 409 |

跨系统链的语义边界见 `docs/adr/0051-cross-system-subject-federation.md`：业务系统 = 接入应用
（`app_code`），两个应用的相同 `externalUserId` **不构成**同一主体；只有显式登记且经独立审批的
联邦映射参与授权发现，撤销下一次读取即时生效。

## 主数据映射子区间（Y02，`1_003_015_xxx`）

主数据映射把"同一实体在不同业务系统里的标识"关联起来（跨系统链 AT-070）。HTTP 列按 ADR 0003 的**语义**
映射；框架按常量名派生实际状态（后缀 `_NOT_EXISTS` → 404，名称含 `CONFLICT`/`EXISTS`/`DUPLICATE` → 409，
其余 422），本表的 400/401 类码同此口径。

| 名称 | 编号 | 说明 | HTTP |
|---|---|---|---|
| `AI_MASTER_OBJECT_NOT_EXISTS` | 1_003_015_000 | 企业统一对象不存在（编号/标识无效、已删除同语义） | 404 |
| `AI_MASTER_OBJECT_CODE_DUPLICATE` | 1_003_015_001 | 统一对象标识已存在（标识全局唯一且不可修改） | 409 |
| `AI_MASTER_OBJECT_DISABLED_CONFLICT` | 1_003_015_002 | 统一对象已停用，不能用于主数据判定（不回退到历史版本） | 409 |
| `AI_MASTER_OBJECT_REVISION_NOT_EXISTS` | 1_003_015_003 | 映射版本不存在（版本号无效或不属于该对象） | 404 |
| `AI_MASTER_OBJECT_REVISION_NOT_PUBLISHED_CONFLICT` | 1_003_015_004 | 草稿版本不是可核验事实，不能用于判定或作为报表依据 | 409 |
| `AI_MASTER_OBJECT_REVISION_PUBLISHED_CONFLICT` | 1_003_015_005 | 已发布版本不可变：不能增删其映射条目（改映射必须新建版本） | 409 |
| `AI_MASTER_OBJECT_REVISION_EXPIRED_CONFLICT` | 1_003_015_006 | 版本有效期不覆盖判定时刻，阻断而不是回退到最新版本 | 409 |
| `AI_MASTER_OBJECT_REVISION_FINGERPRINT_CONFLICT` | 1_003_015_007 | 版本内容重算指纹与冻结值不符（内容被版本外改动） | 409 |
| `AI_MASTER_MAPPING_ENTRY_INVALID` | 1_003_015_008 | 映射条目登记不合法：源键/实体类型格式、有效期窗口、匹配方式或来源系统不可用 | 422 |
| `AI_MASTER_MAPPING_ENTRY_DUPLICATE` | 1_003_015_009 | 同一版本的同一（系统, 实体类型, 源键）已登记 | 409 |
| `AI_MASTER_MAPPING_CONFLICT` | 1_003_015_010 | 映射冲突：一对多（同对象同系统同实体类型多条重叠）或多对一（同源键重叠时间属于多个对象），发布与判定都阻断 | 409 |
| `AI_MASTER_MAPPING_EXPIRED_CONFLICT` | 1_003_015_011 | 源键有效期不覆盖判定时刻（未生效或已过期） | 409 |
| `AI_MASTER_MAPPING_NOT_EXISTS` | 1_003_015_012 | 该（对象, 系统, 实体类型）没有已发布的映射事实（未映射即不关联） | 404 |
| `AI_MASTER_OBJECT_CATALOG_BUDGET_EXCEEDED` | 1_003_015_013 | 目录/登记条目超过预算（单版本 200 条），拒绝返回**部分**内容 | 422 |
| `AI_MASTER_OBJECT_PUBLISHER_CONFLICT` | 1_003_015_014 | 独立审核：发布人必须不同于草稿创建人 | 409 |

语义边界见 `docs/adr/0053-master-data-entity-mapping-versions.md`：映射只按**显式登记的源键**关联，
展示名不参与判定（同名不同实体不合并）；映射版本发布后不可变，旧报表按受理时的版本编号与冻结指纹解释；
冲突与过期一律阻断，绝不"挑一个"。

## 实时语音子区间（X05，`1_003_014_xxx`）

实时语音的 HTTP 列按 ADR 0003 的**语义**映射；框架按常量名派生实际状态
（后缀 `_NOT_EXISTS` → 404，名称含 `CONFLICT`/`EXISTS`/`DUPLICATE` → 409，其余 422），
因此本表的 400/401/403/429 是客户端应据此处理的语义码（与既有 `AI_TICKET_INVALID`、
`AI_RUN_BUDGET_EXCEEDED` 同一口径）。

| 名称 | 编号 | 说明 | HTTP |
|---|---|---|---|
| `AI_REALTIME_SESSION_NOT_EXISTS` | 1_003_014_000 | 实时会话不存在（编号无效/已删除/归属不符同语义，防枚举；`_NOT_EXISTS` 承重） | 404 |
| `AI_REALTIME_SESSION_CLOSED_CONFLICT` | 1_003_014_001 | 会话已关闭（终态），关闭原因见会话视图 `closeReason` | 409 |
| `AI_REALTIME_SESSION_DETACHED_CONFLICT` | 1_003_014_002 | 会话已断开或本实例没有通道：先重连建立媒体面 | 409 |
| `AI_REALTIME_SESSION_LIMIT_EXCEEDED` | 1_003_014_003 | 同一主体（2）/同一应用（32）的并发会话达到上限，不排队 | 429 |
| `AI_REALTIME_PROTOCOL_UNVERIFIED_CONFLICT` | 1_003_014_004 | 该（端点, 配置版本, 协议）未通过实时能力验证，不放行且不回退 | 409 |
| `AI_REALTIME_ADAPTER_UNAVAILABLE_CONFLICT` | 1_003_014_005 | 平台未注册该协议的实时适配器（平台不知道的能力一律不可用） | 409 |
| `AI_REALTIME_AUDIO_FORMAT_UNSUPPORTED` | 1_003_014_006 | 音频格式不在适配器支持集合/已验证格式集合内，或单帧超过格式上限 | 400 |
| `AI_REALTIME_MUTED_CONFLICT` | 1_003_014_007 | 已关麦，不接受上行音频（明确拒绝，不静默丢弃） | 409 |
| `AI_REALTIME_BACKPRESSURE_CONFLICT` | 1_003_014_008 | 输入超过有界缓冲：会话已按 `audio-backpressure-exceeded` 结束 | 409 |
| `AI_REALTIME_TURN_STALE_CONFLICT` | 1_003_014_009 | 回合已过期（打断前的旧回合帧被丢弃并计数） | 409 |
| `AI_REALTIME_TURN_FUTURE_INVALID` | 1_003_014_010 | 回合号超出当前回合（客户端不能凭空发明回合） | 400 |
| `AI_REALTIME_REATTACH_BUDGET_EXCEEDED` | 1_003_014_011 | 重连次数耗尽（有界重连），会话已关闭 | 429 |
| `AI_REALTIME_REATTACH_TIMEOUT_CONFLICT` | 1_003_014_012 | 断线超过重连时限，会话已关闭 | 409 |
| `AI_REALTIME_TICKET_INVALID` | 1_003_014_013 | 票据未知/已过期/已被续票替换（旧票据立即失效） | 401 |
| `AI_REALTIME_ENDPOINT_CONFIG_CHANGED_CONFLICT` | 1_003_014_014 | 受理时固定的端点配置/凭据版本已变，不能悄悄用新配置续接 | 409 |
| `AI_REALTIME_TOOL_CALL_NOT_EXISTS` | 1_003_014_015 | 会话内工具调用不存在或不属于该会话 | 404 |
| `AI_REALTIME_TOOL_POLICY_DENIED` | 1_003_014_016 | 会话内只执行免确认（AUTO）读工具；写工具与需确认工具走运行/动作流程 | 403 |
| `AI_REALTIME_TOOL_IN_PROGRESS_CONFLICT` | 1_003_014_017 | 该工具调用正在执行（执行权已消费），不重复执行 | 409 |
| `AI_REALTIME_SESSION_EXPIRED_CONFLICT` | 1_003_014_018 | 会话已到期并关闭（惰性物化），客户端应重新受理 | 409 |

协议选择、能力验证判据与"真实供应商链路未验证"的边界见
`docs/adr/0052-realtime-voice-protocol-and-capability-verification.md`。

## 跨源指标口径子区间（Y03，`1_003_016_xxx`）

跨源指标口径回答"**这些来自不同系统的数能不能相加**"（跨系统链 AT-034/AT-070）。HTTP 列按 ADR 0003
的**语义**映射；框架按常量名派生实际状态（后缀 `_NOT_EXISTS` → 404，名称含
`CONFLICT`/`EXISTS`/`DUPLICATE` → 409，其余 422）。命名因此是承重的：把 `_CONFLICT` 去掉会静默
把 409 变成 422。

| 名称 | 编号 | 说明 | HTTP |
|---|---|---|---|
| `AI_METRIC_SEMANTICS_NOT_EXISTS` | 1_003_016_000 | 跨源指标口径不存在（编号/标识无效、已删除同语义） | 404 |
| `AI_METRIC_SEMANTICS_CODE_DUPLICATE` | 1_003_016_001 | 口径标识已存在（标识全局唯一且不可修改） | 409 |
| `AI_METRIC_SEMANTICS_DISABLED_CONFLICT` | 1_003_016_002 | 口径已停用，不能用于跨源聚合（不回退到历史版本） | 409 |
| `AI_METRIC_SEMANTICS_REVISION_NOT_EXISTS` | 1_003_016_003 | 口径版本不存在（版本号无效或不属于该口径） | 404 |
| `AI_METRIC_SEMANTICS_REVISION_NOT_PUBLISHED_CONFLICT` | 1_003_016_004 | 草稿版本不是可核验事实，不能用于聚合或作为报表依据 | 409 |
| `AI_METRIC_SEMANTICS_REVISION_PUBLISHED_CONFLICT` | 1_003_016_005 | 已发布版本不可变：改口径必须新建版本 | 409 |
| `AI_METRIC_SEMANTICS_REVISION_EXPIRED_CONFLICT` | 1_003_016_006 | 版本有效期不覆盖聚合时刻，阻断而不是回退到最新版本 | 409 |
| `AI_METRIC_SEMANTICS_FINGERPRINT_CONFLICT` | 1_003_016_007 | 版本内容重算指纹与冻结值不符（内容被版本外改动） | 409 |
| `AI_METRIC_SOURCE_INVALID` | 1_003_016_008 | 来源声明不合法：键白名单、角色/粒度键格式、枚举取值或数组形状不合规 | 422 |
| `AI_METRIC_SOURCE_DUPLICATE` | 1_003_016_009 | 同一口径版本内同一数据集版本重复声明 | 409 |
| `AI_METRIC_PLAN_SELECTION_REQUIRED` | 1_003_016_010 | 查询计划必须为每个来源显式选择数据集版本与映射版本（不接受"取当前版本"的省略写法） | 422 |
| `AI_METRIC_PLAN_SOURCE_NOT_DECLARED` | 1_003_016_011 | 计划选择的来源不在该口径版本的来源声明内 | 422 |
| `AI_METRIC_FANOUT_UNSAFE_CONFLICT` | 1_003_016_012 | 不安全扇出：来源未按各自主键粒度预聚合，同一事实会被重复计算 | 409 |
| `AI_METRIC_CURRENCY_CONVERSION_MISSING_CONFLICT` | 1_003_016_013 | 来源币种不一致且未声明换算规则，**禁止跨币种求和**（绝不静默相加） | 409 |
| `AI_METRIC_CONVERSION_RULE_CONFLICT` | 1_003_016_014 | 换算规则的目标币种与口径币种不一致，或规则声明不合法 | 409 |
| `AI_METRIC_CALIBER_CONFLICT` | 1_003_016_015 | 来源单位/时区与口径声明冲突，必须显式解决（**绝不**让模型推断） | 409 |
| `AI_METRIC_CALIBER_MISSING_CONFLICT` | 1_003_016_016 | 来源缺少必需口径项（单位/币种/时区/粒度），不允许按默认值补全 | 409 |
| `AI_METRIC_AGGREGATION_ORDER_CONFLICT` | 1_003_016_017 | 聚合顺序未满足"先按各自主键粒度聚合再关联"，或未恰好覆盖每个来源 | 409 |
| `AI_METRIC_GAP_CLARIFICATION_REQUIRED` | 1_003_016_018 | 存在数据缺口且来源未声明为可选，必须澄清（绝不按 0 静默补齐） | 422 |
| `AI_METRIC_SEMANTICS_PUBLISHER_CONFLICT` | 1_003_016_019 | 独立审核：口径版本发布人必须不同于草稿创建人 | 409 |

语义边界见 `docs/ai-platform/verification/y03-cross-source-metric-semantics-evidence.md`：跨源相加的
前提必须先被显式登记成可版本化的口径（币种/单位/时区/时间窗口/主键粒度/聚合顺序）；查询计划必须
逐个来源钉住数据集版本与映射版本；多对多关联一律先按各自主键粒度预聚合再关联；不同币种无换算规则
禁止求和；口径冲突与缺失显式拒绝，绝不让模型推断。

## 跨源有界执行子区间（Y04，`1_003_017_xxx`）

有界跨源执行回答"**这些数算不出来的时候，怎么说清楚为什么**"（跨系统链 AT-070/AT-071 与本卡
三条专项）。本区间的共同主题是**受控结束**：预算超限、来源截断、时间点偏移、重复计入、容量超限
都不是静默降级的理由，每一种都有独立编号，让调用方能区分"该缩小范围、该重试，还是该联系来源方"。

HTTP 列按 ADR 0003 的**语义**映射；框架按常量名派生实际状态（后缀 `_NOT_EXISTS` → 404，名称含
`CONFLICT`/`EXISTS`/`DUPLICATE` → 409，其余 422）。命名因此是承重的：把 `_CONFLICT` 去掉会静默
把 409 变成 422。

| 名称 | 编号 | 说明 | HTTP |
|---|---|---|---|
| `AI_CROSS_SOURCE_EXECUTION_NOT_EXISTS` | 1_003_017_000 | 跨源执行记录不存在（执行键无效或已删除同语义） | 404 |
| `AI_CROSS_SOURCE_EXECUTION_KEY_CONFLICT` | 1_003_017_001 | 同一执行键提交了不同的计划指纹，或对已到终态的执行原地重跑 | 409 |
| `AI_CROSS_SOURCE_PLAN_SOURCE_NOT_DECLARED` | 1_003_017_002 | 计划里的来源角色没有对应的取数规格（或映射版本与计划不符） | 422 |
| `AI_CROSS_SOURCE_SOURCE_TIMEOUT_CONFLICT` | 1_003_017_003 | 单源在自身超时预算内未完成（可重试） | 409 |
| `AI_CROSS_SOURCE_SOURCE_FAILED_CONFLICT` | 1_003_017_004 | 必需来源执行失败（无权/不可达/列型不符），整体受控结束（可重试） | 409 |
| `AI_CROSS_SOURCE_RESULT_TOO_LARGE` | 1_003_017_005 | 超过行数或内存预算：**受控结束**，不静默截断 | 422 |
| `AI_CROSS_SOURCE_RESULT_TRUNCATED_CONFLICT` | 1_003_017_006 | 来源结果被截断，拒绝按不完整数据汇总（预聚合的 SUM 偏小仍像合法数字） | 409 |
| `AI_CROSS_SOURCE_CONSISTENCY_SKEW_CONFLICT` | 1_003_017_007 | 各来源数据时间点偏移超过容忍窗口（不假装同一时刻） | 409 |
| `AI_CROSS_SOURCE_CAPACITY_EXCEEDED` | 1_003_017_008 | 超过数仓容量上限，拒绝执行（**不**引入分布式查询集群） | 422 |
| `AI_CROSS_SOURCE_CAPACITY_REGISTRATION_CONFLICT` | 1_003_017_009 | 容量登记状态不允许该变更（终态登记不可改写） | 409 |
| `AI_CROSS_SOURCE_ALREADY_COUNTED_CONFLICT` | 1_003_017_010 | 该来源已计入本次执行，拒绝重复汇总（重试幂等在持久层的落点） | 409 |
| `AI_CROSS_SOURCE_ENTITY_KEY_MISSING_CONFLICT` | 1_003_017_011 | 版本化实体键缺失或形状不合法，无法参与跨源关联 | 409 |
| `AI_CROSS_SOURCE_ENTITY_KEY_REVISION_CONFLICT` | 1_003_017_012 | 参与关联的来源钉在不同实体键映射版本上，拒绝跨版本关联 | 409 |

语义边界见 `docs/ai-platform/verification/y04-bounded-cross-source-execution-evidence.md` 与
`docs/adr/0054-bounded-cross-source-execution-and-unified-result.md`：先源内聚合再按版本化实体键
关联；每源并发/超时/行数/内存都有预算且越界即受控结束；一致性时间点取各源数据时间最小值并暴露
偏移；缺失来源显式标注而不按 0 补齐；重试幂等由"每来源一行 + 乐观锁覆盖 + 合计由已计入行求和"
这套持久层机制保证，而不是"重试时小心一点"；超容量的数仓接口拒绝或转登记。

## 跨系统授权与完整性子区间（Y05，`1_003_018_xxx`）

跨系统授权回答"**这个数算出来了，能不能给你看**"（AT-009/010/048/071 与本卡三条专项）。
Y04 负责把数算完并说清没算完的原因，本区间负责说清**不给看**的原因，两者刻意不共用编号：
执行域的拒绝该重试或缩小范围，授权域的拒绝重试没有意义——只能去改授权，改完发新请求。

本区间的共同主题是**拒绝不可区分**：无权与不存在返回同一种语义，拒绝消息静态、不携带
被拒对象的名称与规模（本区间的 `ErrorCode` 消息体不含 `{}` 占位符，因此插参不会被格式化进
消息——"塞什么进消息"本身会变成新的枚举通道）。

| 名称 | 编号 | 说明 | HTTP |
| --- | --- | --- | --- |
| `AI_CROSS_SOURCE_AUTHZ_EXECUTION_NOT_EXISTS` | 1_003_018_000 | 跨源授权执行记录不存在（执行键无效或已删除同语义） | 404 |
| `AI_CROSS_SOURCE_AUTHZ_SOURCE_NOT_AUTHORIZED` | 1_003_018_001 | 主体对某个来源系统/数据集无权，拒绝参与合并 | 422 |
| `AI_CROSS_SOURCE_AUTHZ_MAPPING_NOT_AUTHORIZED` | 1_003_018_002 | 主体对实体映射无权：计划合法、来源可读也拒绝跨系统关联 | 422 |
| `AI_CROSS_SOURCE_AUTHZ_TOTAL_EXPOSES_FORBIDDEN_DETAIL` | 1_003_018_003 | 合计跨过被禁来源（"总额=有权+无权"可做减法），拒绝出具 | 422 |
| `AI_CROSS_SOURCE_AUTHZ_SOURCE_COUNT_LEAKS_FORBIDDEN` | 1_003_018_004 | 来源计数会暴露无权来源（差额不可解但条数可数），拒绝出具 | 422 |
| `AI_CROSS_SOURCE_AUTHZ_CAPTURE_NOT_AUTHORIZED` | 1_003_018_005 | 模型输入捕获含已失权数据，整份不可读（不产出部分结果） | 422 |
| `AI_CROSS_SOURCE_AUTHZ_REQUEST_INVALID` | 1_003_018_006 | 授权判定入参不合法（fail-closed，不按默认放行） | 422 |
| `AI_CROSS_SOURCE_AUTHZ_ROLE_NOT_AUTHORIZED` | 1_003_018_007 | 主体在本应用下不具备参与跨源读取的角色 | 422 |

语义边界见 `docs/ai-platform/verification/y05-cross-source-authorization-evidence.md` 与
`docs/security/ai-cross-source-authorization-review.md`：授权按来源系统/数据集/实体映射**三级求交**，
映射与来源同级（"我只是在做关联"不构成豁免）；合计与来源计数**两侧同时**阻断被禁明细的反推，
判据方向是"历史覆盖 ⊋ 本次覆盖"（曾经见过、现在不出现才危险）；模型输入捕获在**读取时**用当前
授权复核，任一来源失权则整份不返回（验证真的没有，而不是再过滤一次）。

## 受控 MCP 客户端子区间（X07，`1_003_019_xxx`）

受控 MCP 客户端回答"**这个远程工具能不能进平台**"（FR-39 与本卡四条专项）。
它刻意不与数据与工具子区间（`1_003_006_xxx`，D08 工具政策）共用编号：
D08 回答"已注册的工具能不能执行"，本区间回答"MCP 侧的发现与审批这一步过不过"。
两者都要过——MCP 工具被审批后仍然回到 D08 的注册与政策闸门，政策默认 DENY。

本区间的共同主题是**默认拒绝**：地址不在允许清单、协议版本不在允许清单、
授权被拒、有界重试耗尽、参数声明无法映射、未审批、schema 漂移——七类都拒绝，
且拒绝消息不携带上游正文、主机名或令牌片段（本区间的 `ErrorCode` 消息体不含 `{}` 占位符）。

| 名称 | 编号 | 说明 | HTTP |
| --- | --- | --- | --- |
| `AI_MCP_ENDPOINT_NOT_ALLOWED` | 1_003_019_000 | MCP 端点地址不在允许范围（主机/端口未命中、非 https、私网未批准或地址不合法） | 422 |
| `AI_MCP_PROTOCOL_VERSION_UNSUPPORTED` | 1_003_019_001 | MCP 协议版本不在允许范围（版本漂移默认拒绝，不降级协商） | 422 |
| `AI_MCP_AUTHENTICATION_REJECTED` | 1_003_019_002 | MCP 服务器拒绝授权凭据（令牌无效/权限不足；不重试） | 422 |
| `AI_MCP_DISCOVERY_TERMINATED` | 1_003_019_003 | 发现有界重试耗尽并**明确终止**（不得退化为空工具清单） | 422 |
| `AI_MCP_TOOL_LIST_EXCEEDED` | 1_003_019_004 | 工具清单超过单次发现上限（拒绝而非截断） | 422 |
| `AI_MCP_TOOL_SCHEMA_UNSUPPORTED` | 1_003_019_005 | 参数声明无法映射到平台参数面（参数名/类型不合规或未声明参数） | 422 |
| `AI_MCP_TOOL_NOT_APPROVED` | 1_003_019_006 | MCP 工具尚未审批，不能进入执行面 | 422 |
| `AI_MCP_TOOL_SCHEMA_DRIFT_CONFLICT` | 1_003_019_007 | 参数声明与已审批版本不一致（上游升级），已阻断旧发布 | 409 |
| `AI_MCP_TOOL_APPROVAL_CONFLICT` | 1_003_019_008 | 审批状态冲突（已审批项重复审批，或漂移项试图沿用旧审批） | 409 |
| `AI_MCP_TOOL_DRAFT_NOT_EXISTS` | 1_003_019_009 | MCP 工具草稿不存在 | 404 |
| `AI_MCP_TOOL_NOT_EXECUTABLE` | 1_003_019_010 | MCP 工具执行路径尚未接入（只做发现，不代执行） | 422 |

语义边界见 `docs/ai-platform/verification/x07-mcp-client-evidence.md`：
"发现"只写 `ai_mcp_tool_draft`（待审批草稿），不写 `ai_tool`——未审批工具在注册表里
**不存在**，因此拒绝发生在"查不到"这一层；已审批但被上游改过 schema 的工具置 `BLOCKED`，
旧发布立即失效（409），必须重新发现 + 重新审批；工具描述是不可信文本且不参与指纹，
所以提示注入拿不到额外权限。

## 跨源结果契约子区间（Y07，`1_003_020_xxx`）

跨源结果契约回答的是"**这份跨源结果能不能作为一份响应交出去**"。
它与两个既有区间分工明确：`1_003_017_xxx`（Y04）回答"这次为什么没跑完"，
`1_003_018_xxx`（Y05）回答"这次为什么不允许你看"。本区间只覆盖前两者都答完之后、
**组装响应**这一步的失败。

**刻意不复用 Y05 的编号**：Y05 的拒绝编号是一份对外承诺，调用方按编号区分
"该去申请授权"还是"该销毁旧产物"；把"台账里没这条执行"塞进同一区间，
会让调用方在"我无权"与"我查错了"之间误判处置动作。

本区间的共同主题是**响应契约的完备性**：跨源响应恒带完整性口径，
口径缺失一律按 `WITHHELD` 处理而不是按 `COMPLETE` 放行
（ADR 0056）。**消息全静态**，不带执行键、来源角色与金额——
组装响应这一步调用方手里正握着执行键与台账，塞进消息就是新开一个信息出口。

| 名称 | 编号 | 说明 | HTTP |
| --- | --- | --- | --- |
| `AI_CROSS_SOURCE_CONTRACT_EXECUTION_NOT_EXISTS` | 1_003_020_000 | 跨源执行台账里没有这条执行记录（执行键无效或已清理）。与 Y05 的 `AI_CROSS_SOURCE_AUTHZ_EXECUTION_NOT_EXISTS` 语义相邻：本编号答"没跑过/已清理"，Y05 那个答"存在但你不被允许看" | 404 |
| `AI_CROSS_SOURCE_CONTRACT_RESULT_NOT_ISSUABLE_CONFLICT` | 1_003_020_001 | 执行台账存在但结果处于受控结束或口径不完整的状态（FAILED / 仍在跑 / 无来源计入）。与"无权"分开编号：前者是**技术**失败（修数据或换执行键重跑），后者是**授权**失败（申请授权） | 409 |
| `AI_CROSS_SOURCE_CONTRACT_REQUEST_INVALID` | 1_003_020_002 | 跨源响应请求不合法（缺执行键、缺主体上下文、角色词表为空）。按 fail-closed 处理，不按"默认放行"：主体上下文缺失时无法确定授权事实，查不到授权不等于仍然有权 | 422 |

语义边界见 `docs/ai-platform/verification/y07-cross-source-result-contract-evidence.md`
与 [ADR 0056](../../adr/0056-cross-source-result-contract-and-merge-entry.md)：
"跨源标记"与"完整性口径"刻意是两个字段——只有口径字段时，
"跨源响应漏发口径"与"单系统响应"无法区分，要么把单系统报表全判成 `WITHHELD`
（毁掉 Y06 证明的无回退），要么保留 fail-open 缺口。拆开后：
标记缺失走单系统路径零影响，标记为 `true` 而口径缺失按 `WITHHELD` 处理。

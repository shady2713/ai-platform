package com.basicframework.module.ai.enums;

import com.basicframework.framework.common.exception.ErrorCode;

/**
 * AI 中台错误码（区间与 HTTP 映射见 {@link AiErrorCodeRanges}）。
 *
 * <p>当前只登记协议与授权边界上已冻结、可被其它任务直接复用的错误码；各能力域实现时在所属子区间
 * 追加编号，禁止改动既有编号语义（错误码是长期协议的一部分）。
 */
public interface AiErrorCodeConstants {

    // ========== 通用/协议 1_003_001_xxx ==========

    /** 入参不合法（400）。 */
    ErrorCode AI_REQUEST_INVALID = new ErrorCode(1_003_001_000, "请求参数不合法");

    /** 幂等键冲突：同键不同摘要（409）。 */
    ErrorCode AI_IDEMPOTENCY_CONFLICT = new ErrorCode(1_003_001_001, "幂等键冲突：同一键提交了不同请求");

    /** 状态冲突：当前状态不允许该操作（409）。 */
    ErrorCode AI_STATE_CONFLICT = new ErrorCode(1_003_001_002, "当前状态不允许该操作");

    /** 资源不存在或无权访问（404，未授权与不存在同语义）。 */
    ErrorCode AI_RESOURCE_NOT_FOUND = new ErrorCode(1_003_001_003, "资源不存在");

    /** 已认证但缺少所需范围或权限（403）。 */
    ErrorCode AI_ACCESS_DENIED = new ErrorCode(1_003_001_004, "没有执行该操作的权限");

    /** 超出配额或限流（429）。 */
    ErrorCode AI_QUOTA_EXCEEDED = new ErrorCode(1_003_001_005, "已超出当前配额或触发限流");

    // ========== 模型中心 1_003_002_xxx ==========

    /** 模型端点未配置或不存在（404）。 */
    ErrorCode AI_MODEL_ENDPOINT_NOT_FOUND = new ErrorCode(1_003_002_000, "模型端点不存在");

    /** 模型端点已停用（409）。 */
    ErrorCode AI_MODEL_ENDPOINT_DISABLED = new ErrorCode(1_003_002_001, "模型端点已停用");

    /** 模型能力不被当前端点支持（400）。 */
    ErrorCode AI_MODEL_CAPABILITY_UNSUPPORTED = new ErrorCode(1_003_002_002, "模型端点不支持所需能力");

    /** 端点名称重复（400）。 */
    ErrorCode AI_MODEL_ENDPOINT_NAME_DUPLICATE = new ErrorCode(1_003_002_004, "端点名称({}) 已存在");

    /** 模型调用失败（502）：仅记录稳定错误，不外泄上游正文。 */
    ErrorCode AI_MODEL_CALL_FAILED = new ErrorCode(1_003_002_003, "模型调用失败");

    /** 嵌入维度与既有索引记录不一致（409）：拒绝写入既有索引，避免静默混入不同维度向量。 */
    ErrorCode AI_MODEL_EMBEDDING_DIMENSION_CHANGED = new ErrorCode(1_003_002_005, "嵌入维度已变化，拒绝写入既有索引");

    /** 资源等级不允许外发到该端点（403）：策略拒绝发生在任何网络调用之前。 */
    ErrorCode AI_MODEL_OUTBOUND_BLOCKED = new ErrorCode(1_003_002_006, "该资源等级不允许外发到所选模型端点");

    // ========== 数据与工具 1_003_006_xxx ==========

    /** 连接器不存在（404）。 */
    ErrorCode AI_CONNECTOR_NOT_FOUND = new ErrorCode(1_003_006_000, "连接器不存在");

    /** 连接器标识重复（409）：code 全局唯一且创建后不可修改。 */
    ErrorCode AI_CONNECTOR_CODE_DUPLICATE = new ErrorCode(1_003_006_001, "连接器标识({}) 已存在");

    /** 连接器配置不合规（400）：只接受声明式白名单字段，整段连接串与未知参数一律拒绝。 */
    ErrorCode AI_CONNECTOR_CONFIG_INVALID = new ErrorCode(1_003_006_002, "连接器配置不合规");

    /** 连接器被引用（409）：被数据集或工具引用时不能删除。 */
    ErrorCode AI_CONNECTOR_REFERENCED = new ErrorCode(1_003_006_003, "连接器已被引用，不能删除");

    /** 连接器已停用（409）：停用后不允许探测或发起连接。 */
    ErrorCode AI_CONNECTOR_DISABLED = new ErrorCode(1_003_006_004, "连接器已停用");

    /** 连接器操作不存在（404）。 */
    ErrorCode AI_CONNECTOR_OPERATION_NOT_FOUND = new ErrorCode(1_003_006_005, "连接器操作不存在");

    /** 连接器操作尚未发布（409）：草稿不可执行。 */
    ErrorCode AI_CONNECTOR_OPERATION_NOT_PUBLISHED = new ErrorCode(1_003_006_006, "连接器操作尚未发布，不能执行");

    /** OpenAPI 文档不可导入（400）：格式非法、超出上限或没有可导入的操作。 */
    ErrorCode AI_CONNECTOR_IMPORT_INVALID = new ErrorCode(1_003_006_007, "OpenAPI 文档不可导入");

    /** 目标地址与连接器 Origin 不一致（400）：只允许访问连接器声明的 Origin。 */
    ErrorCode AI_CONNECTOR_ORIGIN_MISMATCH = new ErrorCode(1_003_006_008, "目标地址与连接器 Origin 不一致");

    /** 连接器参数不合法（400）：参数未声明、必填缺失或取值含查询语法。 */
    ErrorCode AI_CONNECTOR_ARGUMENT_INVALID = new ErrorCode(1_003_006_009, "连接器参数不合法");

    /** 目标对象未授权（403）：schema/表/视图不在连接器声明的白名单内（默认拒绝）。 */
    ErrorCode AI_CONNECTOR_OBJECT_NOT_AUTHORIZED = new ErrorCode(1_003_006_010, "目标对象不在连接器授权范围内");

    /** SQL 不是单条只读查询（400）：只允许一条 SELECT/WITH，禁止 DML/DDL/文件函数/注释与多语句。 */
    ErrorCode AI_CONNECTOR_SQL_NOT_READ_ONLY = new ErrorCode(1_003_006_011, "只允许单条只读查询");

    /** 结果超出上限（400）：行数/列数/单值长度越界。 */
    ErrorCode AI_CONNECTOR_RESULT_TOO_LARGE = new ErrorCode(1_003_006_012, "查询结果超出上限");

    /** 查询超时（502）：超过语句超时上限，已请求上游中断。 */
    ErrorCode AI_CONNECTOR_QUERY_TIMEOUT = new ErrorCode(1_003_006_013, "连接器查询超时");

    /** 查询被取消（409）：调用方显式取消，未完成的结果不返回。 */
    ErrorCode AI_CONNECTOR_QUERY_CANCELLED = new ErrorCode(1_003_006_014, "连接器查询已被取消");

    /** 只读连接不可用（502）：建池、取连接或连接中断失败（不含主机与凭据）。 */
    ErrorCode AI_CONNECTOR_MYSQL_UNAVAILABLE = new ErrorCode(1_003_006_015, "只读连接不可用");

    /** 上游不是受支持的 MySQL 8（409）：只对接 MySQL 8，其他版本/MariaDB 拒绝。 */
    ErrorCode AI_CONNECTOR_MYSQL_VERSION_UNSUPPORTED = new ErrorCode(1_003_006_016, "只支持 MySQL 8 上游");

    /** 上游查询失败（502）：语法、权限或上游错误（只回稳定原因码，不回上游正文）。 */
    ErrorCode AI_CONNECTOR_QUERY_FAILED = new ErrorCode(1_003_006_017, "连接器查询失败");

    /** 数据集不存在（404）。 */
    ErrorCode AI_DATASET_NOT_FOUND = new ErrorCode(1_003_006_018, "数据集不存在");

    /** 数据集标识重复（409）：code 全局唯一且创建后不可修改。 */
    ErrorCode AI_DATASET_CODE_DUPLICATE = new ErrorCode(1_003_006_019, "数据集标识({}) 已存在");

    /** 来源对象未授权（403）：不在连接器声明的白名单内。 */
    ErrorCode AI_DATASET_SOURCE_NOT_AUTHORIZED = new ErrorCode(1_003_006_020, "来源对象不在连接器授权范围内");

    /** 语义定义不合规（400）：未知键、枚举外取值、越界或缺少权限策略。 */
    ErrorCode AI_DATASET_DEFINITION_INVALID = new ErrorCode(1_003_006_021, "数据集语义定义不合规");

    /** 别名有歧义（400）：别名重复或与字段/指标/维度名冲突。 */
    ErrorCode AI_DATASET_ALIAS_AMBIGUOUS = new ErrorCode(1_003_006_022, "数据集字段别名有歧义");

    /** 数据集版本不存在（404）。 */
    ErrorCode AI_DATASET_VERSION_NOT_FOUND = new ErrorCode(1_003_006_023, "数据集版本不存在");

    /** 版本不可修改（409）：已发布版本是引用快照，只能新建版本。 */
    ErrorCode AI_DATASET_VERSION_IMMUTABLE = new ErrorCode(1_003_006_024, "已发布版本不可修改，请新建版本");

    /** 版本结构漂移（409）：上游结构与定义不一致，必须重新验证后再发布。 */
    ErrorCode AI_DATASET_VERSION_DRIFTED = new ErrorCode(1_003_006_025, "上游结构已漂移，必须重新验证");

    /** 版本未验证（409）：未通过验证的版本不能发布。 */
    ErrorCode AI_DATASET_VERSION_NOT_VERIFIED = new ErrorCode(1_003_006_026, "版本尚未验证，不能发布");

    /** 字段在上游不存在（400）：定义引用了未知列。 */
    ErrorCode AI_DATASET_FIELD_UNKNOWN = new ErrorCode(1_003_006_027, "定义引用了上游不存在的列");

    /** 数据集被引用（409）：被报表等引用时不能删除。 */
    ErrorCode AI_DATASET_REFERENCED = new ErrorCode(1_003_006_028, "数据集已被引用，不能删除");

    /** 数据集已停用（409）：停用后不允许新建/验证/发布版本。 */
    ErrorCode AI_DATASET_DISABLED = new ErrorCode(1_003_006_029, "数据集已停用");

    /** 查询计划不合规（400）：结构、字段、操作符、时间或参数校验不通过。 */
    ErrorCode AI_QUERY_PLAN_INVALID = new ErrorCode(1_003_006_030, "查询计划不合规");

    /** 模型返回 SQL 片段（400）：只接受结构化计划，不接受任何 SQL 字符串。 */
    ErrorCode AI_QUERY_SQL_REJECTED = new ErrorCode(1_003_006_031, "模型返回了 SQL 片段，已拒绝");

    /** 数据集不在本次授权范围（403）：计划引用了授权外的数据集，不允许扩大。 */
    ErrorCode AI_QUERY_DATASET_NOT_ALLOWED = new ErrorCode(1_003_006_032, "数据集不在本次授权范围内");

    /** 需要澄清（409）：措辞/口径有歧义，必须先追问再执行。 */
    ErrorCode AI_QUERY_CLARIFICATION_REQUIRED = new ErrorCode(1_003_006_033, "查询口径有歧义，需要澄清");

    /** 修复次数用尽（409）：模型连续给出的计划都不合规，停止修复。 */
    ErrorCode AI_QUERY_REPAIR_EXHAUSTED = new ErrorCode(1_003_006_034, "计划修复次数已用尽");

    /** 模型输出不可用（502）：不是合法 JSON 对象或缺少运行层判别结果。 */
    ErrorCode AI_QUERY_MODEL_OUTPUT_INVALID = new ErrorCode(1_003_006_035, "模型输出不可用");

    /** 数据集版本未发布（409）：只有已发布且已验证的版本可用于查询计划。 */
    ErrorCode AI_DATASET_VERSION_NOT_PUBLISHED = new ErrorCode(1_003_006_036, "数据集版本未发布，不能用于查询");

    /** 编译失败（400）：计划与数据集组合无法编译（字段映射缺失或结构不支持）。 */
    ErrorCode AI_QUERY_COMPILE_FAILED = new ErrorCode(1_003_006_037, "查询计划无法编译");

    /** 缺少行范围授权域（403）：没有行级约束时拒绝生成可执行 SQL（不退回全库）。 */
    ErrorCode AI_QUERY_SCOPE_REQUIRED = new ErrorCode(1_003_006_038, "缺少行范围授权，拒绝生成查询");

    // ========== 应用与授权 1_003_003_xxx ==========

    /** 应用不存在（404）。 */
    ErrorCode AI_APPLICATION_NOT_FOUND = new ErrorCode(1_003_003_000, "应用不存在");

    /** 应用标识重复（409）：appCode 全局唯一且创建后不可修改。 */
    ErrorCode AI_APPLICATION_CODE_DUPLICATE = new ErrorCode(1_003_003_001, "应用标识({}) 已存在");

    /** Origin 非法（400）：只接受精确来源，禁止路径、查询、通配与用户信息。 */
    ErrorCode AI_APPLICATION_ORIGIN_INVALID =
            new ErrorCode(1_003_003_002, "来源格式不合法，只接受精确 Origin（scheme://host[:port]）");

    /** 客户端凭据无效（401）：应用不存在、未启用、凭据错误或已吊销共用同一语义，防枚举。 */
    ErrorCode AI_APPLICATION_CREDENTIAL_INVALID = new ErrorCode(1_003_003_003, "客户端凭据无效");

    /** 资源授权不存在（404）。 */
    ErrorCode AI_RESOURCE_GRANT_NOT_FOUND = new ErrorCode(1_003_003_007, "资源授权不存在");

    /** 同一主体在同一资源类型下的同一资源标识已有授权（409）。 */
    ErrorCode AI_RESOURCE_GRANT_DUPLICATE = new ErrorCode(1_003_003_008, "该主体对此资源的授权已存在");

    /** 授权判定拒绝（403）：应用/主体/范围/授权/动作任一不满足。 */
    ErrorCode AI_AUTHORIZATION_DENIED = new ErrorCode(1_003_003_009, "没有访问该资源的授权");

    /** 访问票据无效（401）：不存在、已撤销、已过期、应用或主体不可用共用同一语义，防枚举。 */
    ErrorCode AI_TICKET_INVALID = new ErrorCode(1_003_003_010, "访问票据无效或已过期");

    // ========== 运行与任务 1_003_004_xxx ==========

    /** 运行不存在或无权访问（404）。 */
    ErrorCode AI_RUN_NOT_FOUND = new ErrorCode(1_003_004_000, "运行不存在");

    /** 运行已进入终态，不能再取消或改写（409）。 */
    ErrorCode AI_RUN_ALREADY_TERMINAL = new ErrorCode(1_003_004_001, "运行已结束，不能再变更");

    /** 会话业务键重复（409）：同一应用+主体内会话业务键唯一。 */
    ErrorCode AI_CONVERSATION_KEY_DUPLICATE = new ErrorCode(1_003_004_002, "会话业务键({}) 已存在");

    /** 运行缺少可执行输入（409）：例如无会话的运行没有可回放的输入消息。 */
    ErrorCode AI_RUN_NOT_EXECUTABLE = new ErrorCode(1_003_004_003, "运行缺少可执行输入：{}");

    /** 超出运行预算（429）：步数、耗时或工具次数用尽。 */
    ErrorCode AI_RUN_BUDGET_EXCEEDED = new ErrorCode(1_003_004_004, "超出运行预算：{}");

    /** 工具调用不被支持（400）：当前没有可用的受控工具实现。 */
    ErrorCode AI_TOOL_UNSUPPORTED = new ErrorCode(1_003_004_005, "当前不支持工具调用");

    /** 事件重放窗口已过期（409）：请读取运行快照，不要重新发起运行。 */
    ErrorCode AI_RUN_EVENT_WINDOW_EXPIRED = new ErrorCode(1_003_004_006, "事件重放窗口已过期，请读取运行快照");

    /** 任务不可重试（409）：结果未知或状态不允许重试，重复执行可能产生第二份副作用。 */
    ErrorCode AI_TASK_NOT_RETRYABLE = new ErrorCode(1_003_004_007, "任务当前状态不允许重试：{}");

    // ========== 服务配置 1_003_008_xxx ==========

    /** 服务未标记可发布（409）：草稿必须先通过能力校验才能创建发布候选。 */
    ErrorCode AI_SERVICE_NOT_READY = new ErrorCode(1_003_008_000, "服务未标记可发布，不能创建发布候选");

    /** 缺少与候选内容匹配的评测结果（409）：内容或端点配置变化后必须重新评测，旧报告不能用于新内容。 */
    ErrorCode AI_SERVICE_EVAL_MISSING = new ErrorCode(1_003_008_001, "缺少与候选内容匹配的评测结果");

    /** 评测得分未达发布门槛（409）。 */
    ErrorCode AI_SERVICE_EVAL_BELOW_THRESHOLD = new ErrorCode(1_003_008_002, "评测得分未达发布门槛");

    /** 服务没有生效的发布版本（409）：新运行必须解析到唯一 ACTIVE 版本。 */
    ErrorCode AI_SERVICE_NOT_PUBLISHED = new ErrorCode(1_003_008_003, "服务没有生效的发布版本");

    /** 发布版本依赖的资源绑定已解除或不可用（409）：新运行拒绝，旧版本不保留旧权限。 */
    ErrorCode AI_SERVICE_RESOURCE_UNAVAILABLE = new ErrorCode(1_003_008_004, "发布版本依赖的资源绑定已解除或不可用");

    /** 端点配置版本已变化（409）：冻结候选与当前配置不一致，需重建候选并重新评测。 */
    ErrorCode AI_SERVICE_ENDPOINT_CONFIG_CHANGED = new ErrorCode(1_003_008_005, "模型端点配置版本已变化，需重建发布候选");

    /** 发布版本尚未发布（409）：候选从未对运行开放，既不能用于运行解析也不能作为回退目标。 */
    ErrorCode AI_SERVICE_RELEASE_NOT_PUBLISHED = new ErrorCode(1_003_008_006, "该发布版本尚未发布，不能用于运行或回退");

    /** 会话固定的发布内容已不一致（409）：固定值失效时必须显式迁移会话，不得静默换版本。 */
    ErrorCode AI_SERVICE_RELEASE_PIN_STALE = new ErrorCode(1_003_008_007, "会话固定的发布版本与当前内容不一致，需要显式迁移会话");

    /** 上下文超出输入预算（409）：强制分区无法完整容纳，必须由调用方缩小输入或提高预算。 */
    ErrorCode AI_CONTEXT_BUDGET_EXCEEDED = new ErrorCode(1_003_008_008, "上下文超出输入预算：{} 分区无法完整容纳");

    /** 业务上下文不合规（400）：不是 JSON 对象，或包含未注册字段。 */
    ErrorCode AI_CONTEXT_SCHEMA_INVALID = new ErrorCode(1_003_008_009, "业务上下文不合规：{}");
}

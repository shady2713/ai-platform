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

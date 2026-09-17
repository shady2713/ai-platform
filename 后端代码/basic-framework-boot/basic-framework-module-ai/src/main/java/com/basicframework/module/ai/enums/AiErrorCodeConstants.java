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
}

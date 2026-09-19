package com.basicframework.module.ai.domain.runtime;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;

import com.basicframework.framework.ai.core.model.ModelException;
import com.basicframework.framework.common.exception.ErrorCode;
import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.module.ai.enums.AiErrorCodeConstants;

/**
 * 模型失败到平台错误码的稳定映射（S04）。
 *
 * <p>上游（模型/网络/策略）的失败原因以 {@link ModelException.Reason} 给出；
 * 运行链路对外只能暴露平台错误码，绝不把上游报文、提示词或凭据带出去。
 * 映射规则固定：
 * <ul>
 *   <li>可归因到平台配置/策略的原因映射到对应平台错误码（停用、不存在、能力、外发策略、配额）；</li>
 *   <li>其余（超时、限流、上游拒绝、输出超限、结构化不合法、维度不一致，以及部署未启用
 *       模型能力）统一映射为 {@code AI_MODEL_CALL_FAILED}（502），调用方据 5xx 判定可重试，
 *       不猜测上游细节，也不把"未启用"伪装成"端点停用"这类具体归因。</li>
 * </ul>
 */
public final class AiModelFailureCodes {

    private AiModelFailureCodes() {}

    /** 把模型失败原因映射为稳定错误码。 */
    public static ErrorCode of(ModelException.Reason reason) {
        if (reason == null) {
            return AiErrorCodeConstants.AI_MODEL_CALL_FAILED;
        }
        return switch (reason) {
            case ENDPOINT_NOT_FOUND -> AiErrorCodeConstants.AI_MODEL_ENDPOINT_NOT_FOUND;
            case ENDPOINT_DISABLED -> AiErrorCodeConstants.AI_MODEL_ENDPOINT_DISABLED;
            case CAPABILITY_UNSUPPORTED -> AiErrorCodeConstants.AI_MODEL_CAPABILITY_UNSUPPORTED;
            case TARGET_NOT_ALLOWED -> AiErrorCodeConstants.AI_MODEL_OUTBOUND_BLOCKED;
            case RATE_LIMITED -> AiErrorCodeConstants.AI_QUOTA_EXCEEDED;
            default -> AiErrorCodeConstants.AI_MODEL_CALL_FAILED;
        };
    }

    /** 把模型失败映射为平台异常；异常正文只用平台文案，不带上游内容。 */
    public static ServiceException toServiceException(ModelException exception) {
        return exception(of(exception.getReason()));
    }
}

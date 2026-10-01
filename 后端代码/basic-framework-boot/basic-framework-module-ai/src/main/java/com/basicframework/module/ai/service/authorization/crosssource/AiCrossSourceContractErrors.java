package com.basicframework.module.ai.service.authorization.crosssource;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_CROSS_SOURCE_CONTRACT_EXECUTION_NOT_EXISTS;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_CROSS_SOURCE_CONTRACT_REQUEST_INVALID;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_CROSS_SOURCE_CONTRACT_RESULT_NOT_ISSUABLE_CONFLICT;

/**
 * 跨源结果契约的错误出口（Y07，{@code 1_003_020_xxx}）：与 {@code AiCrossSourceAuthorizationErrors}
 * 同一形状——把"为什么这份响应发不出去"的每个分支收敛到一个稳定编号。
 *
 * <p><b>消息全静态，不带插参</b>（与 Y04/Y05 一致）：执行键、来源角色、金额一律不进消息。
 * 本域比授权域多一层顾虑——调用方在"组装响应"这一步手里通常正握着执行键与台账，
 * 把它塞进错误消息就等于在错误路径上又开了一个信息出口。
 */
public final class AiCrossSourceContractErrors {

    private AiCrossSourceContractErrors() {}

    /** 执行台账里没有这条执行记录（404）。 */
    public static RuntimeException executionNotExists() {
        return exception(AI_CROSS_SOURCE_CONTRACT_EXECUTION_NOT_EXISTS);
    }

    /** 执行结果不可出具（409）：台账存在，但处于受控结束或口径不完整的状态。 */
    public static RuntimeException resultNotIssuable() {
        return exception(AI_CROSS_SOURCE_CONTRACT_RESULT_NOT_ISSUABLE_CONFLICT);
    }

    /** 跨源响应请求不合法（422）：凑不出一条可判定的跨源响应（fail-closed，不按默认放行）。 */
    public static RuntimeException requestInvalid() {
        return exception(AI_CROSS_SOURCE_CONTRACT_REQUEST_INVALID);
    }
}

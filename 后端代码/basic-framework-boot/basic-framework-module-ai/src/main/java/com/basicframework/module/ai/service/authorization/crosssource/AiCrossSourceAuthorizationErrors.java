package com.basicframework.module.ai.service.authorization.crosssource;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_CROSS_SOURCE_AUTHZ_CAPTURE_NOT_AUTHORIZED;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_CROSS_SOURCE_AUTHZ_EXECUTION_NOT_EXISTS;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_CROSS_SOURCE_AUTHZ_MAPPING_NOT_AUTHORIZED;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_CROSS_SOURCE_AUTHZ_REQUEST_INVALID;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_CROSS_SOURCE_AUTHZ_ROLE_NOT_AUTHORIZED;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_CROSS_SOURCE_AUTHZ_SOURCE_COUNT_LEAKS_FORBIDDEN;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_CROSS_SOURCE_AUTHZ_SOURCE_NOT_AUTHORIZED;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_CROSS_SOURCE_AUTHZ_TOTAL_EXPOSES_FORBIDDEN_DETAIL;

import com.basicframework.framework.common.exception.ErrorCode;

/**
 * 跨源授权的错误出口（Y05，{@code 1_003_018_xxx}）：与 {@code AiCrossSourceExecutionErrors}
 * 同一形状——把"为什么不给你看"的每个分支收敛到一个稳定编号。
 *
 * <p>本域与 Y04 执行域的编号**刻意不同**：执行域回答"这次为什么没跑完"，
 * 本域回答"这次为什么不允许给你看"。两者混用会让调用方在"来源故障"与"你没有权限"
 * 之间误判处置方式——前者该重试，后者该去申请授权。
 *
 * <p><b>消息是静态的，不带任何插参</b>（与 Y04 一致：{@code ServiceExceptionUtil} 只对
 * 含 {@code {}} 占位符的消息做格式化）。这不是省事，而是本域的一条安全约束：
 * 错误消息一旦能携带动态内容，"把哪个参数塞进去"就会变成一条新的信息出口，
 * 而拒绝路径上最不该出现的就是被拒对象的标识。角色名、捕获编号、金额一律不进消息——
 * 消息只说"哪一级无权"，让调用方自己拿计划去对照。
 */
public final class AiCrossSourceAuthorizationErrors {

    private AiCrossSourceAuthorizationErrors() {}

    /** 跨源授权执行记录不存在（404）。 */
    public static RuntimeException executionNotExists() {
        return exception(AI_CROSS_SOURCE_AUTHZ_EXECUTION_NOT_EXISTS);
    }

    /** 来源无权（422）：该来源参与本次合并但主体无权访问其系统或数据集。 */
    public static RuntimeException sourceNotAuthorized() {
        return exception(AI_CROSS_SOURCE_AUTHZ_SOURCE_NOT_AUTHORIZED);
    }

    /** 实体映射无权（422）：计划合法、来源可读也拒绝——专项二的专用编号。 */
    public static RuntimeException mappingNotAuthorized() {
        return exception(AI_CROSS_SOURCE_AUTHZ_MAPPING_NOT_AUTHORIZED);
    }

    /** 合计会暴露被禁明细（422）：差额可解。 */
    public static RuntimeException totalExposesForbiddenDetail() {
        return exception(AI_CROSS_SOURCE_AUTHZ_TOTAL_EXPOSES_FORBIDDEN_DETAIL);
    }

    /** 来源计数会暴露被禁来源（422）：条数可数。 */
    public static RuntimeException sourceCountLeaksForbidden() {
        return exception(AI_CROSS_SOURCE_AUTHZ_SOURCE_COUNT_LEAKS_FORBIDDEN);
    }

    /** 模型输入捕获含失权数据（422）。 */
    public static RuntimeException captureNotAuthorized() {
        return exception(AI_CROSS_SOURCE_AUTHZ_CAPTURE_NOT_AUTHORIZED);
    }

    /** 判定入参不合法（422）。 */
    public static RuntimeException requestInvalid() {
        return exception(AI_CROSS_SOURCE_AUTHZ_REQUEST_INVALID);
    }

    /** 角色无权（422）。 */
    public static RuntimeException roleNotAuthorized() {
        return exception(AI_CROSS_SOURCE_AUTHZ_ROLE_NOT_AUTHORIZED);
    }

    /**
     * 按错误码取可重试结论：本域**一律不可重试**。
     *
     * <p>授权拒绝不是瞬时故障：重试同一个请求只会重复得到同一个拒绝。
     * 把它标成可重试会让调用方在注定失败的路径上反复打数仓——与"预算超限/结果截断"
     * 同属不该重试的一类。调用方要恢复只能去改授权，改完再发新请求。
     */
    public static boolean retryable(ErrorCode code) {
        return false;
    }
}

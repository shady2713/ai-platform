package com.basicframework.module.ai.service.query.crosssource;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_CROSS_SOURCE_ALREADY_COUNTED_CONFLICT;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_CROSS_SOURCE_CANCELLED_CONFLICT;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_CROSS_SOURCE_CAPACITY_EXCEEDED;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_CROSS_SOURCE_CAPACITY_REGISTRATION_CONFLICT;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_CROSS_SOURCE_CONCURRENCY_EXCEEDED_CONFLICT;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_CROSS_SOURCE_CONSISTENCY_SKEW_CONFLICT;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_CROSS_SOURCE_ENTITY_KEY_MISSING_CONFLICT;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_CROSS_SOURCE_ENTITY_KEY_REVISION_CONFLICT;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_CROSS_SOURCE_EXECUTION_KEY_CONFLICT;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_CROSS_SOURCE_EXECUTION_NOT_EXISTS;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_CROSS_SOURCE_PLAN_SOURCE_NOT_DECLARED;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_CROSS_SOURCE_RESULT_TOO_LARGE;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_CROSS_SOURCE_RESULT_TRUNCATED_CONFLICT;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_CROSS_SOURCE_SOURCE_FAILED_CONFLICT;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_CROSS_SOURCE_SOURCE_TIMEOUT_CONFLICT;

import com.basicframework.framework.common.exception.ErrorCode;

/**
 * 跨源执行的错误出口（Y04）：把稳定错误码集中在一个类里，避免同一语义在不同分支被写成不同编号。
 *
 * <p>跨源执行有大量"为什么没算出来"的分支：预算超限、来源截断、必需来源失败、
 * 时间点偏移、重复计入、容量超限。它们的共同要求是**不能静默降级**——每一种都必须
 * 落到一个可被调用方区分的编号上，否则调用方只能看到一个笼统的"失败"，
 * 也无从判断是该缩小范围、该重试还是该联系来源方。
 *
 * <p>重试策略在这里集中判定：只有"来源超时"与"来源失败"可重试。预算超限、结果截断、
 * 键冲突、容量超限重试多少次都是同一个结果——把它们标成可重试只会让调用方
 * 在注定失败的路径上反复打连接器。
 */
public final class AiCrossSourceExecutionErrors {

    private AiCrossSourceExecutionErrors() {}

    /** 跨源执行记录不存在（404）。 */
    public static RuntimeException executionNotExists() {
        return exception(AI_CROSS_SOURCE_EXECUTION_NOT_EXISTS);
    }

    /** 执行键冲突（409）：同一键提交了不同的计划指纹。 */
    public static RuntimeException executionKeyConflict() {
        return exception(AI_CROSS_SOURCE_EXECUTION_KEY_CONFLICT);
    }

    /** 计划来源缺少取数规格（422）。 */
    public static RuntimeException planSourceNotDeclared() {
        return exception(AI_CROSS_SOURCE_PLAN_SOURCE_NOT_DECLARED);
    }

    /** 单源超时（409）。 */
    public static RuntimeException sourceTimeout() {
        return exception(AI_CROSS_SOURCE_SOURCE_TIMEOUT_CONFLICT);
    }

    /** 必需来源失败（409）。 */
    public static RuntimeException sourceFailed() {
        return exception(AI_CROSS_SOURCE_SOURCE_FAILED_CONFLICT);
    }

    /**
     * 调用方取消（409，**不可重试**）。
     *
     * <p>与 {@link #sourceTimeout()} 分开：超时是"来源没在预算内完成"，值得重试；
     * 取消是"调用方自己不要了"，重试等于替它白干一遍。
     */
    public static RuntimeException sourceCancelled() {
        return exception(AI_CROSS_SOURCE_CANCELLED_CONFLICT);
    }

    /**
     * 并发来源数超限（409）。
     *
     * <p>刻意不报成"行数或内存预算超限"：并发超限要查来源扇出与调度，
     * 规模超限要查单次取数形状，证据指向完全不同的东西。
     */
    public static RuntimeException concurrencyExceeded() {
        return exception(AI_CROSS_SOURCE_CONCURRENCY_EXCEEDED_CONFLICT);
    }

    /** 行数或内存预算超限（422）：受控结束，不截断。 */
    public static RuntimeException resultTooLarge() {
        return exception(AI_CROSS_SOURCE_RESULT_TOO_LARGE);
    }

    /** 来源结果被截断（409）：拒绝按不完整数据汇总。 */
    public static RuntimeException resultTruncated() {
        return exception(AI_CROSS_SOURCE_RESULT_TRUNCATED_CONFLICT);
    }

    /** 数据时间点偏移超限（409）。 */
    public static RuntimeException consistencySkew() {
        return exception(AI_CROSS_SOURCE_CONSISTENCY_SKEW_CONFLICT);
    }

    /** 超过数仓容量上限（422）。 */
    public static RuntimeException capacityExceeded() {
        return exception(AI_CROSS_SOURCE_CAPACITY_EXCEEDED);
    }

    /** 容量登记状态冲突（409）。 */
    public static RuntimeException capacityRegistrationConflict() {
        return exception(AI_CROSS_SOURCE_CAPACITY_REGISTRATION_CONFLICT);
    }

    /** 来源重复计入（409）。 */
    public static RuntimeException alreadyCounted() {
        return exception(AI_CROSS_SOURCE_ALREADY_COUNTED_CONFLICT);
    }

    /** 版本化实体键缺失（409）。 */
    public static RuntimeException entityKeyMissing() {
        return exception(AI_CROSS_SOURCE_ENTITY_KEY_MISSING_CONFLICT);
    }

    /** 实体键映射版本不一致（409）。 */
    public static RuntimeException entityKeyRevisionConflict() {
        return exception(AI_CROSS_SOURCE_ENTITY_KEY_REVISION_CONFLICT);
    }

    /**
     * 按错误码取可重试结论：仅来源超时与来源失败可重试，其余一律不可重试。
     *
     * <p>取消（{@code AI_CROSS_SOURCE_CANCELLED_CONFLICT}）刻意不在其中：调用方已经不要了，
     * 重试是替它白干。
     */
    public static boolean retryable(ErrorCode code) {
        return AI_CROSS_SOURCE_SOURCE_TIMEOUT_CONFLICT.equals(code)
                || AI_CROSS_SOURCE_SOURCE_FAILED_CONFLICT.equals(code);
    }
}

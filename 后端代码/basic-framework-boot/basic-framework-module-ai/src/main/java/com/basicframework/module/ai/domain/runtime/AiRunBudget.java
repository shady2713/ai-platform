package com.basicframework.module.ai.domain.runtime;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_REQUEST_INVALID;

import lombok.Getter;

/**
 * 运行预算（O04）：有界执行的唯一来源——步数、总耗时与工具调用次数。
 *
 * <p>预算是**上限**而不是目标：任一项用尽都必须以可解释的稳定原因结束，
 * 不允许"再试一次"式无限重试，也不允许把预算耗尽伪装成成功。
 * token 预算由上下文构造器（S04）在执行前落实，本类只约束执行过程本身。
 */
@Getter
public final class AiRunBudget {

    /** 默认最大步骤数（一次文本运行 = 1 步；工具调用会让步数增长）。 */
    public static final int DEFAULT_MAX_STEPS = 8;

    /** 默认总耗时上限（毫秒）。 */
    public static final long DEFAULT_MAX_DURATION_MILLIS = 120_000L;

    /** 默认最大工具调用次数。 */
    public static final int DEFAULT_MAX_TOOL_CALLS = 4;

    /** 最大步骤数 */
    private final int maxSteps;

    /** 总耗时上限（毫秒） */
    private final long maxDurationMillis;

    /** 最大工具调用次数 */
    private final int maxToolCalls;

    private AiRunBudget(int maxSteps, long maxDurationMillis, int maxToolCalls) {
        this.maxSteps = maxSteps;
        this.maxDurationMillis = maxDurationMillis;
        this.maxToolCalls = maxToolCalls;
    }

    /** 平台默认预算。 */
    public static AiRunBudget defaults() {
        return new AiRunBudget(DEFAULT_MAX_STEPS, DEFAULT_MAX_DURATION_MILLIS, DEFAULT_MAX_TOOL_CALLS);
    }

    /** 按调用方给出的上限构造预算；缺省字段使用平台默认值，非法值直接拒绝。 */
    public static AiRunBudget of(Integer maxSteps, Long maxDurationMillis, Integer maxToolCalls) {
        int steps = maxSteps == null ? DEFAULT_MAX_STEPS : maxSteps;
        long duration = maxDurationMillis == null ? DEFAULT_MAX_DURATION_MILLIS : maxDurationMillis;
        int toolCalls = maxToolCalls == null ? DEFAULT_MAX_TOOL_CALLS : maxToolCalls;
        if (steps < 1 || duration < 1 || toolCalls < 0) {
            throw exception(AI_REQUEST_INVALID);
        }
        return new AiRunBudget(steps, duration, toolCalls);
    }

    /** 是否已超出总耗时上限。 */
    public boolean durationExceeded(long elapsedMillis) {
        return elapsedMillis > maxDurationMillis;
    }

    /** 是否还有可用步骤（已用步数 < 上限）。 */
    public boolean hasStepLeft(int usedSteps) {
        return usedSteps < maxSteps;
    }

    /** 是否还有可用工具调用次数。 */
    public boolean hasToolCallLeft(int usedToolCalls) {
        return usedToolCalls < maxToolCalls;
    }
}

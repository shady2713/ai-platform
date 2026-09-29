package com.basicframework.module.ai.domain.workflow;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_REQUEST_INVALID;

import lombok.Getter;

/**
 * 流程运行预算（X08）：有界执行的唯一来源——步数（节点数）与总耗时。
 *
 * <p>受理时快照进运行行（之后改流程图不影响在途运行的预算）；上限是**平台封顶**，
 * 调用方只能给得更紧，不能超过。步数按"执行的节点数"计：发布期已把节点数限制在
 * {@link AiWorkflowGraph#MAX_NODES} 内且图无环，平台封顶与图规模一致，正常图不会触顶。
 */
@Getter
public final class AiWorkflowBudget {

    /** 默认步数预算（执行的节点数上限）。 */
    public static final int DEFAULT_MAX_STEPS = 16;

    /** 默认总耗时上限（毫秒）。 */
    public static final long DEFAULT_MAX_DURATION_MILLIS = 60_000L;

    /** 平台步数封顶（与图节点数上限一致）。 */
    public static final int MAX_STEPS_CAP = AiWorkflowGraph.MAX_NODES;

    /** 平台耗时封顶（毫秒）。 */
    public static final long MAX_DURATION_CAP = 120_000L;

    /** 步数预算。 */
    private final int maxSteps;

    /** 耗时预算（毫秒）。 */
    private final long maxDurationMillis;

    private AiWorkflowBudget(int maxSteps, long maxDurationMillis) {
        this.maxSteps = maxSteps;
        this.maxDurationMillis = maxDurationMillis;
    }

    /** 平台默认预算。 */
    public static AiWorkflowBudget defaults() {
        return new AiWorkflowBudget(DEFAULT_MAX_STEPS, DEFAULT_MAX_DURATION_MILLIS);
    }

    /** 按受理请求构造预算；缺省用平台默认，超出平台封顶或非法值直接拒绝。 */
    public static AiWorkflowBudget of(Integer maxSteps, Long maxDurationMillis) {
        int steps = maxSteps == null ? DEFAULT_MAX_STEPS : maxSteps;
        long duration = maxDurationMillis == null ? DEFAULT_MAX_DURATION_MILLIS : maxDurationMillis;
        if (steps < 1 || steps > MAX_STEPS_CAP || duration < 1 || duration > MAX_DURATION_CAP) {
            throw exception(AI_REQUEST_INVALID);
        }
        return new AiWorkflowBudget(steps, duration);
    }

    /** 是否还有可用步数（已执行节点数 < 上限）。 */
    public boolean hasStepLeft(int executedNodes) {
        return executedNodes < maxSteps;
    }

    /** 是否已超出总耗时。 */
    public boolean durationExceeded(long elapsedMillis) {
        return elapsedMillis > maxDurationMillis;
    }
}

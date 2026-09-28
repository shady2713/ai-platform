package com.basicframework.module.ai.service.webhook;

/**
 * 一次投递尝试的判定结论（X10）：**分类是唯一的**——可重试与确定失败不能混。
 *
 * <p>分类规则（保守方向：不确定的失败按可重试处理，但重试次数有硬上限）：
 * <ul>
 *   <li>{@link #OUTCOME_DELIVERED}：接收端 2xx；</li>
 *   <li>{@link #OUTCOME_PERMANENT}：确定失败，重试同一请求没有意义——3xx（不跟随重定向）、
 *       4xx（接收端明确拒绝）、目标未授权/私网被拒/目标停用/密钥不可用/请求不合法/响应超限；</li>
 *   <li>{@link #OUTCOME_RETRYABLE}：可能只是暂时不可用——超时、连接失败、5xx、429、投递器内部异常。</li>
 * </ul>
 *
 * <p>结论还携带落库所需的事实：HTTP 状态码（未发请求时为空）、签名时间戳（未发请求时为空）、耗时。
 * 这些事实只写尝试行与投递行，不含目标地址、密钥与响应正文。
 */
public record AiWebhookDeliveryOutcome(
        String outcome, String errorCode, Integer httpStatus, Long signatureTimestamp, long durationMs) {

    /** 结论：已送达（接收端 2xx）。 */
    public static final String OUTCOME_DELIVERED = "DELIVERED";

    /** 结论：可重试（超时、连接失败、5xx、限流、内部异常）。 */
    public static final String OUTCOME_RETRYABLE = "RETRYABLE";

    /** 结论：确定失败（重定向、4xx、目标未授权/停用、密钥不可用）。 */
    public static final String OUTCOME_PERMANENT = "PERMANENT";

    /** 已送达。 */
    public static AiWebhookDeliveryOutcome delivered(Integer httpStatus, Long signatureTimestamp, long durationMs) {
        return new AiWebhookDeliveryOutcome(OUTCOME_DELIVERED, null, httpStatus, signatureTimestamp, durationMs);
    }

    /** 可重试失败。 */
    public static AiWebhookDeliveryOutcome retryable(
            String errorCode, Integer httpStatus, Long signatureTimestamp, long durationMs) {
        return new AiWebhookDeliveryOutcome(OUTCOME_RETRYABLE, errorCode, httpStatus, signatureTimestamp, durationMs);
    }

    /** 确定失败。 */
    public static AiWebhookDeliveryOutcome permanent(
            String errorCode, Integer httpStatus, Long signatureTimestamp, long durationMs) {
        return new AiWebhookDeliveryOutcome(OUTCOME_PERMANENT, errorCode, httpStatus, signatureTimestamp, durationMs);
    }

    /** 是否已送达。 */
    public boolean isDelivered() {
        return OUTCOME_DELIVERED.equals(outcome);
    }

    /** 是否可重试。 */
    public boolean isRetryable() {
        return OUTCOME_RETRYABLE.equals(outcome);
    }

    /** 是否确定失败。 */
    public boolean isPermanent() {
        return OUTCOME_PERMANENT.equals(outcome);
    }

    /** 落库用的尝试结论（与 {@code AiWebhookDeliveryAttemptDO.OUTCOME_*} 同一词表，由单测钉住一致）。 */
    public String attemptOutcome() {
        return outcome;
    }
}

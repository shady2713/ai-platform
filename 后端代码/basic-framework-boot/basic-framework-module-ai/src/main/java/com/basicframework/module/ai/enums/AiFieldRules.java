package com.basicframework.module.ai.enums;

import java.util.regex.Pattern;

/**
 * AI 公用字段规则（F08 冻结）：业务键、长度与状态词汇的唯一来源。
 *
 * <p>本类与前端 {@code apps/web-ele/src/adapter/field-rules.ts} 的常量必须一致，
 * 由 {@code docs/contracts/field-catalog.yaml} 的漂移门禁（{@code check-field-catalog.mjs}）双向校验：
 * 任何一侧改动能被门禁识别，不允许前后端各自猜测格式。
 */
public final class AiFieldRules {

    /** 服务业务键：{@code svc_} 前缀 + 3..35 位字母数字下划线连字符，最长 40。 */
    public static final Pattern PATTERN_SERVICE_KEY = Pattern.compile("^svc_[A-Za-z0-9_-]{3,35}$");

    /** 会话业务键：{@code conv_} 前缀，规则同上。 */
    public static final Pattern PATTERN_CONVERSATION_KEY = Pattern.compile("^conv_[A-Za-z0-9_-]{3,35}$");

    /** 运行业务键：{@code run_} 前缀，规则同上。 */
    public static final Pattern PATTERN_RUN_KEY = Pattern.compile("^run_[A-Za-z0-9_-]{3,35}$");

    /** 业务键最大长度（UTF-16 码元，含前缀）。 */
    public static final int BUSINESS_KEY_MAX_LENGTH = 40;

    /** 幂等键长度区间（开放 API 契约，16..128）。 */
    public static final int IDEMPOTENCY_KEY_MIN_LENGTH = 16;

    /** 幂等键最大长度。 */
    public static final int IDEMPOTENCY_KEY_MAX_LENGTH = 128;

    /** 用户消息最大长度（UTF-16 码元）。 */
    public static final int MESSAGE_MAX_LENGTH = 16_000;

    /** 校验服务业务键。 */
    public static boolean isValidServiceKey(String value) {
        return value != null
                && value.length() <= BUSINESS_KEY_MAX_LENGTH
                && PATTERN_SERVICE_KEY.matcher(value).matches();
    }

    /** 校验会话业务键。 */
    public static boolean isValidConversationKey(String value) {
        return value != null
                && value.length() <= BUSINESS_KEY_MAX_LENGTH
                && PATTERN_CONVERSATION_KEY.matcher(value).matches();
    }

    /** 校验运行业务键。 */
    public static boolean isValidRunKey(String value) {
        return value != null
                && value.length() <= BUSINESS_KEY_MAX_LENGTH
                && PATTERN_RUN_KEY.matcher(value).matches();
    }

    /** 校验幂等键长度；空值视为不合法（幂等键必填）。 */
    public static boolean isValidIdempotencyKey(String value) {
        return value != null
                && value.length() >= IDEMPOTENCY_KEY_MIN_LENGTH
                && value.length() <= IDEMPOTENCY_KEY_MAX_LENGTH;
    }

    /** 校验消息长度。 */
    public static boolean isValidMessage(String value) {
        return value != null && !value.isBlank() && value.length() <= MESSAGE_MAX_LENGTH;
    }

    private AiFieldRules() {}
}

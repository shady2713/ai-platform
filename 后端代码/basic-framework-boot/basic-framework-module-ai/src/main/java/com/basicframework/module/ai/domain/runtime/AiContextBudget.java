package com.basicframework.module.ai.domain.runtime;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_REQUEST_INVALID;

import lombok.Getter;

/**
 * 上下文预算（S04）：消息条数与 token 预算的唯一来源。
 *
 * <p>token 数按字符数估算（{@code charsPerToken}），与计量侧的估算参数保持同一口径：
 * 上游未返回用量时，平台给出的估算值必须能被同一套规则复算，不允许两处各算一套。
 * 预算只约束**输入**；输出预算由调用方在模型请求里单独声明。
 */
@Getter
public final class AiContextBudget {

    /** 默认保留的历史消息条数上限。 */
    public static final int DEFAULT_MAX_MESSAGES = 20;

    /** 默认输入 token 预算（与 {@code AiOutboundPolicyProperties.metering} 的默认估算口径一致）。 */
    public static final int DEFAULT_MAX_TOKENS = 8_000;

    /** 默认每 token 字符数（与计量估算默认值一致）。 */
    public static final int DEFAULT_CHARS_PER_TOKEN = 4;

    /** 历史消息条数上限 */
    private final int maxMessages;

    /** 输入 token 预算 */
    private final int maxTokens;

    /** 每 token 字符数（估算口径） */
    private final int charsPerToken;

    private AiContextBudget(int maxMessages, int maxTokens, int charsPerToken) {
        this.maxMessages = maxMessages;
        this.maxTokens = maxTokens;
        this.charsPerToken = charsPerToken;
    }

    /** 平台默认预算。 */
    public static AiContextBudget defaults() {
        return new AiContextBudget(DEFAULT_MAX_MESSAGES, DEFAULT_MAX_TOKENS, DEFAULT_CHARS_PER_TOKEN);
    }

    /**
     * 按调用方给出的上限构造预算；缺省字段使用平台默认值，非法值直接拒绝。
     *
     * <p>不允许把上限调成 0 或负数：那等于"静默丢弃全部上下文"，必须由调用方显式表达需求。
     */
    public static AiContextBudget of(Integer maxMessages, Integer maxTokens, Integer charsPerToken) {
        int messages = maxMessages == null ? DEFAULT_MAX_MESSAGES : maxMessages;
        int tokens = maxTokens == null ? DEFAULT_MAX_TOKENS : maxTokens;
        int perToken = charsPerToken == null ? DEFAULT_CHARS_PER_TOKEN : charsPerToken;
        if (messages < 1 || tokens < 1 || perToken < 1) {
            throw exception(AI_REQUEST_INVALID);
        }
        return new AiContextBudget(messages, tokens, perToken);
    }

    /** 估算文本占用的 token 数（向上取整；空文本为 0）。 */
    public int estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return (text.length() + charsPerToken - 1) / charsPerToken;
    }
}

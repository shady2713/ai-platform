package com.basicframework.module.ai.api.protocol;

/**
 * ResultBlock v1 的 Java 映射（对应 docs/contracts/ai/result-block.schema.json）。
 *
 * <p>TS 侧是判别联合；Java 侧以显式 {@link #validate()} 实现同等拒绝语义：
 * 未知 kind、缺失配套字段、多余字段组合、超长文本都拒绝，不做"尽力解析"。
 */
public record AiResultBlockDTO(String kind, String text, AiChartSpecDTO spec, String message) {

    private static final int TEXT_MAX_LENGTH = 20_000;

    private static final int MESSAGE_MAX_LENGTH = 1_000;

    /** 协议级校验：kind 与配套字段必须严格一致。 */
    public void validate() {
        if (kind == null) {
            throw new IllegalArgumentException("结果块缺少 kind");
        }
        switch (kind) {
            case "text" -> {
                requireOnly(text != null && spec == null && message == null, "text 块必须且只能包含 text 字段");
                requireOnly(text.length() <= TEXT_MAX_LENGTH, "text 超出长度上限");
            }
            case "chart" -> {
                requireOnly(spec != null && text == null && message == null, "chart 块必须且只能包含 spec 字段");
                spec.validate();
            }
            case "error" -> {
                requireOnly(message != null && text == null && spec == null, "error 块必须且只能包含 message 字段");
                requireOnly(message.length() <= MESSAGE_MAX_LENGTH, "message 超出长度上限");
            }
            default -> throw new IllegalArgumentException("未知结果块 kind：" + kind);
        }
    }

    private static void requireOnly(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }
}

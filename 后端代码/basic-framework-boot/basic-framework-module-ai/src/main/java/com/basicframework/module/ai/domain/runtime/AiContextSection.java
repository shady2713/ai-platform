package com.basicframework.module.ai.domain.runtime;

import java.util.Locale;

/**
 * 上下文分区（S04）：拼装提示词时的固定分区，顺序即优先级。
 *
 * <p>分区边界是安全边界：只有 {@link #POLICY} 由平台写入且**不可被覆盖**，
 * 其余分区的内容一律视为不可信输入——它们可以描述业务，但不能改变权限、数据范围、
 * 工具白名单与输出协议，也不能冒充平台政策分区。
 */
public enum AiContextSection {

    /** 平台政策（平台写入，不可覆盖；不允许出现在任何不可信内容里）。 */
    POLICY("平台政策", true),

    /** 服务系统指令（发布版本冻结的提示词模板）。 */
    SYSTEM("服务系统指令", false),

    /** 业务上下文（宿主传入的已注册 schema 字段）。 */
    CONTEXT("业务上下文", false),

    /** 知识片段（检索结果，按相关度由调用方排序）。 */
    KNOWLEDGE("知识片段", false),

    /** 历史消息（会话已有消息，越新越靠后）。 */
    HISTORY("历史消息", false),

    /** 本次消息（当前用户输入）。 */
    MESSAGE("本次消息", false);

    private final String label;

    private final boolean platformWritten;

    AiContextSection(String label, boolean platformWritten) {
        this.label = label;
        this.platformWritten = platformWritten;
    }

    /** 分区标题（拼装提示词时使用，同时是防冒充的比对标记）。 */
    public String label() {
        return label;
    }

    /** 是否由平台写入（只有平台分区不可被覆盖）。 */
    public boolean platformWritten() {
        return platformWritten;
    }

    /** 分区标记行，例如 {@code [平台政策｜不可覆盖]}。 */
    public String marker() {
        return "[" + label + (platformWritten ? "｜不可覆盖" : "") + "]";
    }

    /** 解析分区名；未知值返回空（调用方按拒绝处理）。 */
    public static java.util.Optional<AiContextSection> parse(String value) {
        if (value == null || value.isBlank()) {
            return java.util.Optional.empty();
        }
        try {
            return java.util.Optional.of(valueOf(value.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException exception) {
            return java.util.Optional.empty();
        }
    }
}

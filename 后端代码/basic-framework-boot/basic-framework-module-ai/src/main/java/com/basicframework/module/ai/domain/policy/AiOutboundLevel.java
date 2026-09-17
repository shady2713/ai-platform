package com.basicframework.module.ai.domain.policy;

import java.util.Optional;

/**
 * 数据外发等级（M05）：与 {@code docs/security/data-classification.md} 的 L1–L4 一一对应。
 *
 * <p>等级既有"数据是什么"的含义（资源分级），也用于声明"端点最多能收到什么"（外发等级）。
 * 序号越大越敏感，比较只能在同一语义内进行：调用方声明资源的等级，策略声明端点的上限。
 */
public enum AiOutboundLevel {

    /** L1 公开：可公开披露。 */
    L1_PUBLIC(1),

    /** L2 内部：仅限组织内部。 */
    L2_INTERNAL(2),

    /** L3 个人信息：可识别自然人。 */
    L3_PERSONAL(3),

    /** L4 敏感数据或秘密：泄露可导致账号、系统或财产损害。 */
    L4_SECRET(4);

    private final int rank;

    AiOutboundLevel(int rank) {
        this.rank = rank;
    }

    /** 敏感度序号，越大越敏感。 */
    public int rank() {
        return rank;
    }

    /** 是否不高于给定上限。 */
    public boolean within(AiOutboundLevel ceiling) {
        return rank <= ceiling.rank;
    }

    /** 解析配置值；未知值返回空（由调用方决定失败方式，避免静默降级）。 */
    public static Optional<AiOutboundLevel> parse(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(valueOf(value.trim().toUpperCase(java.util.Locale.ROOT)));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }
}

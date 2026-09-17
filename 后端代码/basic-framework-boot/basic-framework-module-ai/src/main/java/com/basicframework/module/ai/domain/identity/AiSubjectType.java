package com.basicframework.module.ai.domain.identity;

/**
 * 外部主体类型（A02）。
 *
 * <p>主体类型只有这两种，且**没有**平台角色维度：中台不接受外部主体到 ADMIN 的映射，
 * 也不接受浏览器提交的 roles/deptIds；范围只能由可信解析器在服务端解析。
 */
public enum AiSubjectType {

    /** 应用主体：整个应用作为一个主体（externalUserId 为空串）。 */
    APP,

    /** 用户主体：业务系统里的自然人，按 externalUserId 区分。 */
    USER;

    /** 解析主体类型；未知类型返回空（调用方按拒绝处理）。 */
    public static java.util.Optional<AiSubjectType> parse(String value) {
        if (value == null || value.isBlank()) {
            return java.util.Optional.empty();
        }
        try {
            return java.util.Optional.of(valueOf(value.trim().toUpperCase(java.util.Locale.ROOT)));
        } catch (IllegalArgumentException exception) {
            return java.util.Optional.empty();
        }
    }
}

package com.basicframework.module.ai.adapter.knowledge;

/** 读取知识原文失败（K05）：只带稳定原因码，不带文件内容。 */
public class KnowledgeSourceException extends RuntimeException {

    /** 稳定原因码。 */
    public enum Reason {
        /** 后台线程没有主体上下文（无法按业务 ACL 读取）。 */
        SUBJECT_MISSING,
        /** 文件不存在、引用已解除或当前主体无权限（同语义，防枚举）。 */
        NOT_ACCESSIBLE,
        /** 读取失败（存储或上游错误）。 */
        READ_FAILED
    }

    private final Reason reason;

    public KnowledgeSourceException(Reason reason, String detail) {
        super(reason.name() + (detail == null || detail.isBlank() ? "" : ":" + detail));
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }

    public String reasonCode() {
        return "source-" + reason.name().toLowerCase(java.util.Locale.ROOT);
    }

    public static KnowledgeSourceException of(Reason reason, Throwable cause) {
        return new KnowledgeSourceException(
                reason, cause == null ? null : cause.getClass().getSimpleName());
    }
}

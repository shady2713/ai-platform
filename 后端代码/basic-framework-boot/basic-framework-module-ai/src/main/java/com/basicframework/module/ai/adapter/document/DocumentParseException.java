package com.basicframework.module.ai.adapter.document;

/**
 * 解析失败（K04）：只带**稳定原因码**与异常类型，不带文件内容与上游报文。
 *
 * <p>解析异常里最容易被顺手带出去的就是文件正文（解析器的报错常带原文片段）。
 * 这里刻意只保留原因码与类型名，日志与任务表都只落这两样。
 */
public class DocumentParseException extends RuntimeException {

    /** 稳定原因码。 */
    public enum Reason {
        /** 格式不在白名单内。 */
        UNSUPPORTED_FORMAT,
        /** 超出字符/段落/页数上限。 */
        TOO_LARGE,
        /** 解析超时。 */
        TIMEOUT,
        /** 文件损坏或结构非法。 */
        CORRUPT,
        /** 加密文件（需要密码）。 */
        ENCRYPTED,
        /** 解析器内部错误。 */
        INTERNAL
    }

    private final Reason reason;

    public DocumentParseException(Reason reason, String detail) {
        // 只保留类型与短说明，不含文件内容
        super(reason.name() + (detail == null || detail.isBlank() ? "" : ":" + detail));
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }

    /** 稳定原因码（写入任务与文档的失败原因）。 */
    public String reasonCode() {
        return "parse-" + reason.name().toLowerCase(java.util.Locale.ROOT);
    }

    public static DocumentParseException of(Reason reason, Throwable cause) {
        return new DocumentParseException(
                reason, cause == null ? null : cause.getClass().getSimpleName());
    }
}

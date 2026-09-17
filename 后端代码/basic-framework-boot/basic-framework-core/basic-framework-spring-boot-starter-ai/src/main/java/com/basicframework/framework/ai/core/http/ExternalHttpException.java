package com.basicframework.framework.ai.core.http;

/** 出站 HTTP 边界的稳定错误：原因以枚举给出，消息不含目标凭据与响应正文。 */
public class ExternalHttpException extends RuntimeException {

    /** 拒绝原因（稳定词表，调用方按原因分支而不是解析文案）。 */
    public enum Reason {
        /** 目标不在允许清单内。 */
        TARGET_NOT_ALLOWED,
        /** 目标解析到私网/环回地址但未被显式批准。 */
        PRIVATE_TARGET_DENIED,
        /** URL 或请求本身不合法（方法、scheme、头部数量）。 */
        INVALID_REQUEST,
        /** 连接失败。 */
        CONNECT_FAILED,
        /** 超时（连接或读取）。 */
        TIMEOUT,
        /** 响应体超出上限。 */
        RESPONSE_TOO_LARGE
    }

    private final Reason reason;

    public ExternalHttpException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public ExternalHttpException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }
}

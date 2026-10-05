package com.basicframework.module.ai.adapter.knowledge;

/**
 * 向量索引失败（K01）：只携带**稳定原因码**，不携带上游报文与向量正文。
 *
 * <p>原因码用于 Go/No-Go 结论与运维矩阵（例如维度不一致、认证失败、传输不可用），
 * 因此不允许把上游返回体拼进异常消息。
 */
public class KnowledgeIndexException extends RuntimeException {

    /** 稳定原因码。 */
    public enum Reason {
        /** 集合不存在。 */
        COLLECTION_NOT_FOUND,
        /** 维度与集合不一致（拒绝写入）。 */
        DIMENSION_MISMATCH,
        /** 认证失败（API Key 缺失或无效）。 */
        UNAUTHORIZED,
        /** 传输层失败（网络、TLS、超时）。 */
        TRANSPORT_FAILED,
        /** 上游返回了非预期状态码。 */
        UPSTREAM_REJECTED,
        /** 请求或响应不符合本端口的契约。 */
        INVALID_PAYLOAD,
        /**
         * 调用方主动取消（与 F12 的出站取消归因保持一致）。
         *
         * <p>中断发生在**调用方线程**上，语义是"请求被取消/容器关闭"，
         * 与向量服务无关。报成 {@link #TRANSPORT_FAILED} 会让运维去查
         * 根本没问题的东西——而 {@code TRANSPORT_FAILED} 的对外文案是"向量服务不可达"。
         */
        CANCELLED
    }

    private final Reason reason;

    public KnowledgeIndexException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public KnowledgeIndexException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }
}

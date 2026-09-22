package com.basicframework.module.ai.service.knowledge.indexing;

/** 嵌入失败（K05）：只带稳定原因码，不带文本正文与上游报文。 */
public class AiKnowledgeEmbeddingException extends RuntimeException {

    /** 稳定原因码。 */
    public enum Reason {
        /** 知识库声明的嵌入模型找不到可用端点。 */
        ENDPOINT_UNAVAILABLE,
        /** 维度与既有索引不一致（同维度不同 revision 也不允许混用）。 */
        DIMENSION_MISMATCH,
        /** 上游调用失败。 */
        UPSTREAM_FAILED,
        /** 返回的向量数量或维度与请求不符。 */
        RESPONSE_INVALID
    }

    private final Reason reason;

    public AiKnowledgeEmbeddingException(Reason reason, String detail) {
        super(reason.name() + (detail == null || detail.isBlank() ? "" : ":" + detail));
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }

    /** 稳定原因码（写入任务失败原因与文档提示）。 */
    public String reasonCode() {
        return "embed-" + reason.name().toLowerCase(java.util.Locale.ROOT);
    }

    public static AiKnowledgeEmbeddingException of(Reason reason, Throwable cause) {
        return new AiKnowledgeEmbeddingException(
                reason, cause == null ? null : cause.getClass().getSimpleName());
    }
}

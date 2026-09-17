package com.basicframework.framework.ai.core.model;

/** 模型调用的稳定错误：原因以枚举给出，消息与日志不得包含凭据或上游原始报文。 */
public class ModelException extends RuntimeException {

    /** 失败原因（稳定词表）。 */
    public enum Reason {
        /** AI 能力未启用（basic-framework.ai.enabled=false）。 */
        AI_DISABLED,
        /** 端点未配置或不存在。 */
        ENDPOINT_NOT_FOUND,
        /** 端点已停用。 */
        ENDPOINT_DISABLED,
        /** 端点能力不支持该请求（消息中指明缺失的能力名）。 */
        CAPABILITY_UNSUPPORTED,
        /** 凭据未配置或解密失败。 */
        CREDENTIAL_UNAVAILABLE,
        /** 目标地址不被出站策略允许。 */
        TARGET_NOT_ALLOWED,
        /** 上游超时（含流式空闲超时）；属于可重试失败。 */
        TIMEOUT,
        /** 上游限流或短暂故障；属于可重试失败。 */
        RATE_LIMITED,
        /** 上游调用失败（网络、未知异常）；属可重试失败。 */
        UPSTREAM_FAILED,
        /** 上游明确拒绝该请求（配置、鉴权或模型不匹配）；重试无意义，不再重发。 */
        UPSTREAM_REJECTED,
        /** 模型输出超过平台允许的大小上限；已中断并释放连接。 */
        OUTPUT_LIMIT_EXCEEDED,
        /** 结构化输出请求本身不合法（缺少或不是 JSON 对象的 Schema）。 */
        INVALID_STRUCTURED_INPUT,
        /** 有界修复后模型输出仍不是合法 JSON 对象。 */
        INVALID_STRUCTURED_OUTPUT,
        /** 嵌入批次超过平台上限（批次长度异常，调用方或输入侧错误）。 */
        BATCH_TOO_LARGE,
        /** 嵌入响应内向量维度不一致或为空；上游协议错误。 */
        EMBEDDING_DIMENSION_MISMATCH
    }

    private final Reason reason;

    public ModelException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public ModelException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }
}

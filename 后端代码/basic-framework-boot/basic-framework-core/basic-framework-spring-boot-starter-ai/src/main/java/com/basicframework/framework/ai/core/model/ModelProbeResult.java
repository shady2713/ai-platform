package com.basicframework.framework.ai.core.model;

/**
 * 能力探测结果（M04 冻结）：一次探测的稳定结论，可持久化、可对比、不含秘密。
 *
 * <p>状态语义（"探测失败不被启用状态掩盖"）：
 * <ul>
 *   <li>{@link Status#SUPPORTED}：探测成功，该能力可用；</li>
 *   <li>{@link Status#UNSUPPORTED}：适配器或模型明确不支持（如端点未声明该能力、适配器未实现该探测），
 *       {@link #detailCode()} 给出 {@code CAPABILITY_NOT_DECLARED} / {@code ADAPTER_NOT_IMPLEMENTED}；</li>
 *   <li>{@link Status#FAILED}：探测**尝试过但失败**（超时、限流、上游拒绝、协议错误等），
 *       {@link #detailCode()} 为 {@link ModelException.Reason} 名称。失败不会被"端点已启用"掩盖。</li>
 * </ul>
 *
 * @param kind              探测类型
 * @param status            结论状态
 * @param detailCode        稳定明细码：FAILED 为失败原因名，UNSUPPORTED 为不支持原因，SUPPORTED 为空
 * @param embeddingDimension 仅嵌入探测有值：观测到的向量维度
 * @param latencyMillis     真实调用耗时（毫秒）
 */
public record ModelProbeResult(
        ModelProbeKind kind, Status status, String detailCode, Integer embeddingDimension, long latencyMillis) {

    /** 端点未声明该能力。 */
    public static final String CODE_CAPABILITY_NOT_DECLARED = "CAPABILITY_NOT_DECLARED";

    /** 适配器未实现该探测。 */
    public static final String CODE_ADAPTER_NOT_IMPLEMENTED = "ADAPTER_NOT_IMPLEMENTED";

    /** 文本探测没有得到非空文本。 */
    public static final String CODE_NO_TEXT_RETURNED = "NO_TEXT_RETURNED";

    /** 工具调用探测没有得到工具调用请求。 */
    public static final String CODE_TOOL_CALL_NOT_RETURNED = "TOOL_CALL_NOT_RETURNED";

    /** 探测状态。 */
    public enum Status {
        /** 探测成功。 */
        SUPPORTED,
        /** 明确不支持（配置或适配层面）。 */
        UNSUPPORTED,
        /** 尝试过但失败。 */
        FAILED
    }

    /** 探测成功。 */
    public static ModelProbeResult supported(ModelProbeKind kind, Integer embeddingDimension, long latencyMillis) {
        return new ModelProbeResult(kind, Status.SUPPORTED, null, embeddingDimension, latencyMillis);
    }

    /** 明确不支持。 */
    public static ModelProbeResult unsupported(ModelProbeKind kind, String detailCode) {
        return new ModelProbeResult(kind, Status.UNSUPPORTED, detailCode, null, 0L);
    }

    /** 探测失败；detailCode 用失败原因的稳定名称，不携带上游报文。 */
    public static ModelProbeResult failed(ModelProbeKind kind, ModelException.Reason reason, long latencyMillis) {
        return new ModelProbeResult(kind, Status.FAILED, reason.name(), null, latencyMillis);
    }

    /** 是否确认可用。 */
    public boolean isSupported() {
        return status == Status.SUPPORTED;
    }
}

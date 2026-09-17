package com.basicframework.module.ai.service.usage;

import com.basicframework.framework.ai.core.model.ModelException;
import com.basicframework.framework.ai.core.model.ModelUsage;
import java.util.UUID;

/**
 * 计量记录工厂（M05）：把一次调用的结果收敛为稳定记录。
 *
 * <p>UNKNOWN 与 ESTIMATED 的区别在这里落实：
 * <ul>
 *   <li>上游给了用量 → 原样记录，{@code estimated=false}；</li>
 *   <li>上游没给且未开启估算 → 计数留空（UNKNOWN），**不写 0**；</li>
 *   <li>上游没给且开启估算 → 按字符数估算并标记 {@code estimated=true}（仅用于展示与护栏）。</li>
 * </ul>
 */
public final class AiModelInvocationMeter {

    /** 结果状态：成功。 */
    public static final String STATUS_SUCCEEDED = "SUCCEEDED";

    /** 结果状态：失败。 */
    public static final String STATUS_FAILED = "FAILED";

    private AiModelInvocationMeter() {}

    /** 分配调用标识：每次实际调用一次，便于账本幂等与追踪。 */
    public static String newInvocationId() {
        return UUID.randomUUID().toString();
    }

    /** 成功记录；usage 为空表示上游未提供。 */
    public static AiModelInvocationRecord succeeded(
            String invocationId,
            Long endpointId,
            Integer configRevision,
            Integer credentialRevision,
            String capability,
            ModelUsage usage,
            long latencyMs,
            boolean estimateWhenMissing,
            int charsPerToken,
            String outputText) {
        AiModelInvocationRecord record = base(
                        invocationId, endpointId, configRevision, credentialRevision, capability, latencyMs)
                .setStatus(STATUS_SUCCEEDED);
        ModelUsage effective = usage == null ? ModelUsage.UNKNOWN : usage;
        if (effective.isKnown()) {
            return record.setPromptTokens(effective.promptTokens())
                    .setCompletionTokens(effective.completionTokens())
                    .setEstimated(effective.estimated());
        }
        if (!estimateWhenMissing) {
            return record;
        }
        int estimatedTokens = estimateTokens(outputText, charsPerToken);
        return record.setPromptTokens(estimatedTokens)
                .setCompletionTokens(estimatedTokens)
                .setEstimated(true);
    }

    /** 失败记录：只保留稳定原因名，不含上游报文。 */
    public static AiModelInvocationRecord failed(
            String invocationId,
            Long endpointId,
            Integer configRevision,
            Integer credentialRevision,
            String capability,
            ModelException.Reason reason,
            long latencyMs) {
        return base(invocationId, endpointId, configRevision, credentialRevision, capability, latencyMs)
                .setStatus(STATUS_FAILED)
                .setErrorReason(reason == null ? null : reason.name());
    }

    /** 字符数估算：向上取整，最小 1（有输出就不是 0 token）。 */
    static int estimateTokens(String text, int charsPerToken) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int unit = Math.max(1, charsPerToken);
        return Math.max(1, (text.length() + unit - 1) / unit);
    }

    private static AiModelInvocationRecord base(
            String invocationId,
            Long endpointId,
            Integer configRevision,
            Integer credentialRevision,
            String capability,
            long latencyMs) {
        return new AiModelInvocationRecord()
                .setInvocationId(invocationId)
                .setEndpointId(endpointId)
                .setConfigRevision(configRevision)
                .setCredentialRevision(credentialRevision)
                .setCapability(capability)
                .setLatencyMs(latencyMs);
    }
}

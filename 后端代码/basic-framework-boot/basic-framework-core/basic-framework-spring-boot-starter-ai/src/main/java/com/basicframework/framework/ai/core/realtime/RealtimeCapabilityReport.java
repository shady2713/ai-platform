package com.basicframework.framework.ai.core.realtime;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 实时能力验证结论（X05 冻结，ADR 0052）：一次真实探测的稳定产出，可持久化、可对比、不含秘密。
 *
 * <p>三态语义（"未验证不得当可用"）：
 * <ul>
 *   <li>{@link Status#VERIFIED}：探测成功，声明的能力全部被真实调用确认；</li>
 *   <li>{@link Status#UNSUPPORTED}：适配器/端点明确不支持（未实现探测、协议不匹配、音频格式不支持），
 *       {@link #detailCode()} 给出稳定原因；</li>
 *   <li>{@link Status#FAILED}：探测**尝试过但失败**（超时、限流、协议错误等），
 *       {@link #detailCode()} 是稳定失败原因名。失败不被"端点已启用"掩盖，也不被缓存成可用。</li>
 * </ul>
 *
 * <p>结论与**配置版本**绑定：{@code configRevision} 变化后旧结论不再覆盖当前配置。
 *
 * @param protocol     被验证的协议
 * @param configRevision 被验证的端点配置版本
 * @param declared     适配器声明的能力集合
 * @param confirmed    真实调用确认的能力集合（FAILED/UNSUPPORTED 时为空）
 * @param status       结论状态
 * @param detailCode   稳定明细码（VERIFIED 时为空）
 * @param latencyMillis 真实探测耗时（毫秒）
 */
public record RealtimeCapabilityReport(
        RealtimeProtocol protocol,
        Integer configRevision,
        Set<RealtimeCapability> declared,
        Set<RealtimeCapability> confirmed,
        Status status,
        String detailCode,
        long latencyMillis) {

    /** 适配器未实现该协议的实时探测。 */
    public static final String CODE_ADAPTER_NOT_IMPLEMENTED = "ADAPTER_NOT_IMPLEMENTED";

    /** 协议与适配器声明的协议不一致（不允许跨协议复用结论）。 */
    public static final String CODE_PROTOCOL_MISMATCH = "PROTOCOL_MISMATCH";

    /** 会话固定的音频格式不在该协议验证通过的格式集合内。 */
    public static final String CODE_AUDIO_FORMAT_UNSUPPORTED = "AUDIO_FORMAT_UNSUPPORTED";

    /** 适配器未声明该协议所需的能力。 */
    public static final String CODE_CAPABILITY_NOT_DECLARED = "CAPABILITY_NOT_DECLARED";

    /** 探测未产生任何确认能力（上游未返回可用会话）。 */
    public static final String CODE_NO_SESSION_CONFIRMED = "NO_SESSION_CONFIRMED";

    /** 结论状态。 */
    public enum Status {
        /** 真实探测确认可用。 */
        VERIFIED,
        /** 明确不支持（配置或适配层面）。 */
        UNSUPPORTED,
        /** 尝试过但失败。 */
        FAILED
    }

    public RealtimeCapabilityReport {
        if (protocol == null) {
            throw new IllegalArgumentException("协议不能为空");
        }
        if (status == null) {
            throw new IllegalArgumentException("结论状态不能为空");
        }
        declared = declared == null ? Set.of() : Set.copyOf(declared);
        confirmed = confirmed == null ? Set.of() : Set.copyOf(confirmed);
        if (latencyMillis < 0) {
            throw new IllegalArgumentException("探测耗时不能为负：" + latencyMillis);
        }
    }

    /** 真实探测成功。 */
    public static RealtimeCapabilityReport verified(
            RealtimeProtocol protocol,
            Integer configRevision,
            Set<RealtimeCapability> declared,
            Set<RealtimeCapability> confirmed,
            long latencyMillis) {
        return new RealtimeCapabilityReport(
                protocol, configRevision, declared, confirmed, Status.VERIFIED, null, latencyMillis);
    }

    /** 明确不支持。 */
    public static RealtimeCapabilityReport unsupported(
            RealtimeProtocol protocol, Integer configRevision, Set<RealtimeCapability> declared, String detailCode) {
        return new RealtimeCapabilityReport(
                protocol, configRevision, declared, Set.of(), Status.UNSUPPORTED, detailCode, 0L);
    }

    /** 尝试过但失败。 */
    public static RealtimeCapabilityReport failed(
            RealtimeProtocol protocol,
            Integer configRevision,
            Set<RealtimeCapability> declared,
            String detailCode,
            long latencyMillis) {
        return new RealtimeCapabilityReport(
                protocol, configRevision, declared, Set.of(), Status.FAILED, detailCode, latencyMillis);
    }

    /** 是否确认为可用。 */
    public boolean verified() {
        return status == Status.VERIFIED;
    }

    /** 相对必需能力集合缺少哪些能力（已确认集合之外的部分，保持稳定顺序）。 */
    public Set<RealtimeCapability> missingRequired(Set<RealtimeCapability> required) {
        Set<RealtimeCapability> missing = new LinkedHashSet<>();
        if (required == null) {
            return missing;
        }
        for (RealtimeCapability capability : required) {
            if (!confirmed.contains(capability)) {
                missing.add(capability);
            }
        }
        return missing;
    }
}

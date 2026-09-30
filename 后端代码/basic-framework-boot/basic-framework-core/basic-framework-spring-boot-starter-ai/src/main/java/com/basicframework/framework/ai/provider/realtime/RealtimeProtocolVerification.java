package com.basicframework.framework.ai.provider.realtime;

import com.basicframework.framework.ai.core.realtime.RealtimeAudioFormat;
import com.basicframework.framework.ai.core.realtime.RealtimeCapability;
import com.basicframework.framework.ai.core.realtime.RealtimeCapabilityReport;
import com.basicframework.framework.ai.core.realtime.RealtimeProtocol;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 实时协议验证判据（X05，ADR 0052）：把适配器上报的原始结论收窄成**平台可发布的结论**。
 *
 * <p>为什么判据在 provider 侧而不是每个适配器各写一份：适配器只负责"真实探测并如实上报"，
 * "这个协议至少需要哪些能力、请求的音频格式是否在验证范围内"是平台策略。若由适配器自行判断，
 * 就会出现"某个适配器认为只要连上就算支持"的漏洞；平台在受理前统一收窄，缺一即拒绝。
 *
 * <p>各协议必需能力：
 * <ul>
 *   <li>任何协议：{@link RealtimeCapability#BASELINE}（协商 + 双向音频流）；</li>
 *   <li>WebRTC 额外要求 {@link RealtimeCapability#SERVER_VAD_INTERRUPTION}：媒体面在浏览器与上游之间
 *       直连，平台无法逐帧拦截，"打断"只能由上游保证；没有这项能力的 WebRTC 端点不可发布
 *       （否则界面会表现为"打断无效"）。</li>
 * </ul>
 */
public final class RealtimeProtocolVerification {

    private RealtimeProtocolVerification() {}

    /** 该协议的必需能力集合（不可变，顺序稳定）。 */
    public static Set<RealtimeCapability> requiredCapabilities(RealtimeProtocol protocol) {
        if (protocol == null) {
            throw new IllegalArgumentException("协议不能为空");
        }
        Set<RealtimeCapability> required = new LinkedHashSet<>(RealtimeCapability.BASELINE);
        if (protocol == RealtimeProtocol.WEBRTC) {
            required.add(RealtimeCapability.SERVER_VAD_INTERRUPTION);
        }
        return Set.copyOf(required);
    }

    /**
     * 收窄适配器结论：协议一致性、必需能力与音频格式三重校验。
     *
     * <p>返回的结论是**唯一**允许写入验证台账与用于会话受理的形状；原始结论里"声明了但没确认"
     * 的能力不会出现在确认集合里，因此也无法被会话受理使用。
     *
     * @param expectedProtocol 会话/探测请求的协议
     * @param configRevision   被验证的配置版本（写入结论，供"配置变更即失效"判定）
     * @param raw              适配器上报的原始结论
     * @param supportedFormats 适配器声明的音频格式集合（调用方按探测请求里使用的格式校验）
     * @param requestedFormat 会话请求的音频格式（不在支持集合内即拒绝）
     */
    public static RealtimeCapabilityReport verify(
            RealtimeProtocol expectedProtocol,
            Integer configRevision,
            RealtimeCapabilityReport raw,
            Set<RealtimeAudioFormat> supportedFormats,
            RealtimeAudioFormat requestedFormat) {
        if (expectedProtocol == null || raw == null) {
            throw new IllegalArgumentException("协议与原始结论不能为空");
        }
        if (raw.protocol() != expectedProtocol) {
            // 跨协议复用结论是明确禁止的：结论只对产生它的协议有效
            return RealtimeCapabilityReport.unsupported(
                    expectedProtocol, configRevision, raw.declared(), RealtimeCapabilityReport.CODE_PROTOCOL_MISMATCH);
        }
        if (!raw.verified()) {
            // 未确认（失败或不支持）不因端点启用、其它协议可用而被掩盖
            return new RealtimeCapabilityReport(
                    expectedProtocol,
                    configRevision,
                    raw.declared(),
                    Set.of(),
                    raw.status(),
                    raw.detailCode(),
                    raw.latencyMillis());
        }
        if (requestedFormat == null || supportedFormats == null || !supportedFormats.contains(requestedFormat)) {
            return RealtimeCapabilityReport.unsupported(
                    expectedProtocol,
                    configRevision,
                    raw.declared(),
                    RealtimeCapabilityReport.CODE_AUDIO_FORMAT_UNSUPPORTED);
        }
        Set<RealtimeCapability> missing = raw.missingRequired(requiredCapabilities(expectedProtocol));
        if (!missing.isEmpty()) {
            // 声明了但没被真实调用确认的能力不可发布；缺哪一项由明细码给出（稳定码，不含上游报文）
            return RealtimeCapabilityReport.failed(
                    expectedProtocol, configRevision, raw.declared(), missingReason(missing), raw.latencyMillis());
        }
        return RealtimeCapabilityReport.verified(
                expectedProtocol, configRevision, raw.declared(), raw.confirmed(), raw.latencyMillis());
    }

    /** 缺失能力的稳定明细码：`CAPABILITY_NOT_DECLARED:<能力名>`（能力名来自冻结枚举）。 */
    private static String missingReason(Set<RealtimeCapability> missing) {
        StringBuilder reason = new StringBuilder(RealtimeCapabilityReport.CODE_CAPABILITY_NOT_DECLARED);
        for (RealtimeCapability capability : missing) {
            reason.append(':').append(capability.name());
        }
        return reason.toString();
    }
}

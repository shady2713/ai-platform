package com.basicframework.framework.ai.provider.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.basicframework.framework.ai.core.realtime.RealtimeAudioFormat;
import com.basicframework.framework.ai.core.realtime.RealtimeCapability;
import com.basicframework.framework.ai.core.realtime.RealtimeCapabilityReport;
import com.basicframework.framework.ai.core.realtime.RealtimeProtocol;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 实时协议验证判据（X05）：声明 ∩ 确认 ∩ 必需能力，缺一不可，且不跨协议复用结论。
 */
class RealtimeProtocolVerificationTest {

    private static final RealtimeAudioFormat PCM_16K =
            new RealtimeAudioFormat(RealtimeAudioFormat.MIME_PCM, 16000, 1, 20);

    private static final Set<RealtimeAudioFormat> SUPPORTED = Set.of(PCM_16K);

    @Test
    void requiredCapabilitiesAddServerVadOnlyForWebrtc() {
        assertThat(RealtimeProtocolVerification.requiredCapabilities(RealtimeProtocol.WEBSOCKET))
                .containsExactlyInAnyOrderElementsOf(RealtimeCapability.BASELINE);
        assertThat(RealtimeProtocolVerification.requiredCapabilities(RealtimeProtocol.WEBRTC))
                .contains(
                        RealtimeCapability.SERVER_VAD_INTERRUPTION,
                        RealtimeCapability.SESSION_NEGOTIATION,
                        RealtimeCapability.AUDIO_INPUT_STREAM,
                        RealtimeCapability.AUDIO_OUTPUT_STREAM);
        assertThatThrownBy(() -> RealtimeProtocolVerification.requiredCapabilities(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("协议");
    }

    @Test
    void declaredButUnconfirmedCapabilitiesAreNotPublishable() {
        RealtimeCapabilityReport raw = RealtimeCapabilityReport.verified(
                RealtimeProtocol.WEBSOCKET,
                7,
                Set.of(
                        RealtimeCapability.SESSION_NEGOTIATION,
                        RealtimeCapability.AUDIO_INPUT_STREAM,
                        RealtimeCapability.AUDIO_OUTPUT_STREAM,
                        RealtimeCapability.SERVER_VAD_INTERRUPTION),
                Set.of(RealtimeCapability.SESSION_NEGOTIATION, RealtimeCapability.AUDIO_INPUT_STREAM),
                42L);

        RealtimeCapabilityReport verified =
                RealtimeProtocolVerification.verify(RealtimeProtocol.WEBSOCKET, 7, raw, SUPPORTED, PCM_16K);

        assertThat(verified.verified()).isFalse();
        assertThat(verified.status()).isEqualTo(RealtimeCapabilityReport.Status.FAILED);
        assertThat(verified.detailCode())
                .isEqualTo("CAPABILITY_NOT_DECLARED:" + RealtimeCapability.AUDIO_OUTPUT_STREAM.name());
        assertThat(verified.confirmed()).isEmpty();
    }

    @Test
    void protocolMismatchAndUnsupportedFormatAreRejectedBeforeAnySessionOpens() {
        RealtimeCapabilityReport raw = RealtimeCapabilityReport.verified(
                RealtimeProtocol.WEBRTC, 7, RealtimeCapability.BASELINE, RealtimeCapability.BASELINE, 5L);

        RealtimeCapabilityReport mismatch =
                RealtimeProtocolVerification.verify(RealtimeProtocol.WEBSOCKET, 7, raw, SUPPORTED, PCM_16K);
        assertThat(mismatch.detailCode()).isEqualTo(RealtimeCapabilityReport.CODE_PROTOCOL_MISMATCH);
        assertThat(mismatch.status()).isEqualTo(RealtimeCapabilityReport.Status.UNSUPPORTED);

        RealtimeCapabilityReport webrtcWithoutVad =
                RealtimeProtocolVerification.verify(RealtimeProtocol.WEBRTC, 7, raw, SUPPORTED, PCM_16K);
        assertThat(webrtcWithoutVad.verified()).isFalse();
        assertThat(webrtcWithoutVad.detailCode()).contains(RealtimeCapability.SERVER_VAD_INTERRUPTION.name());

        RealtimeAudioFormat ogg = new RealtimeAudioFormat(RealtimeAudioFormat.MIME_OGG, 16000, 1, 20);
        RealtimeCapabilityReport formatRejected = RealtimeProtocolVerification.verify(
                RealtimeProtocol.WEBRTC,
                7,
                RealtimeCapabilityReport.verified(
                        RealtimeProtocol.WEBRTC,
                        7,
                        RealtimeCapability.BASELINE,
                        Set.of(
                                RealtimeCapability.SESSION_NEGOTIATION,
                                RealtimeCapability.AUDIO_INPUT_STREAM,
                                RealtimeCapability.AUDIO_OUTPUT_STREAM,
                                RealtimeCapability.SERVER_VAD_INTERRUPTION),
                        5L),
                SUPPORTED,
                ogg);
        assertThat(formatRejected.detailCode()).isEqualTo(RealtimeCapabilityReport.CODE_AUDIO_FORMAT_UNSUPPORTED);
    }

    @Test
    void failuresAreNotMaskedAndSuccessfulReportsKeepConfirmedSet() {
        RealtimeCapabilityReport failed = RealtimeCapabilityReport.failed(
                RealtimeProtocol.WEBSOCKET, 7, RealtimeCapability.BASELINE, "UPSTREAM_TIMEOUT", 90L);
        RealtimeCapabilityReport afterVerify =
                RealtimeProtocolVerification.verify(RealtimeProtocol.WEBSOCKET, 7, failed, SUPPORTED, PCM_16K);
        assertThat(afterVerify.status()).isEqualTo(RealtimeCapabilityReport.Status.FAILED);
        assertThat(afterVerify.detailCode()).isEqualTo("UPSTREAM_TIMEOUT");
        assertThat(afterVerify.verified()).isFalse();

        RealtimeCapabilityReport confirmed = RealtimeProtocolVerification.verify(
                RealtimeProtocol.WEBSOCKET,
                7,
                RealtimeCapabilityReport.verified(
                        RealtimeProtocol.WEBSOCKET, 7, RealtimeCapability.BASELINE, RealtimeCapability.BASELINE, 33L),
                SUPPORTED,
                PCM_16K);
        assertThat(confirmed.verified()).isTrue();
        assertThat(confirmed.configRevision()).isEqualTo(7);
        assertThat(confirmed.latencyMillis()).isEqualTo(33L);
        assertThat(confirmed.confirmed()).containsExactlyInAnyOrderElementsOf(RealtimeCapability.BASELINE);

        assertThatThrownBy(() ->
                        RealtimeProtocolVerification.verify(RealtimeProtocol.WEBSOCKET, 7, null, SUPPORTED, PCM_16K))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("原始结论");
    }
}

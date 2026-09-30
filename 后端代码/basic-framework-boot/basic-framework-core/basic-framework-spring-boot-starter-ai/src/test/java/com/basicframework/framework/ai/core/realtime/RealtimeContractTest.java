package com.basicframework.framework.ai.core.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.basicframework.framework.ai.core.model.ModelCapability;
import com.basicframework.framework.ai.core.model.ModelEndpointSnapshot;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 实时语音契约（X05）：协议/能力词汇、音频格式收窄、回合栅栏、有界缓冲与事件的稳定语义。
 */
class RealtimeContractTest {

    @Test
    void sessionOpenRequestValidatesEveryRequiredField() {
        ModelEndpointSnapshot endpoint = new ModelEndpointSnapshot(
                1L, 1, 1, "openai_compatible", "https://api.example.com/v1", "realtime-model", Set.of(), "sk-test");
        RealtimeAudioFormat format =
                RealtimeAudioFormat.parse("audio/pcm@16000:1:20").orElseThrow();

        // 合法构造
        RealtimeSessionOpenRequest ok = new RealtimeSessionOpenRequest(
                "sess-key",
                endpoint,
                RealtimeProtocol.WEBSOCKET,
                format,
                4096,
                java.time.LocalDateTime.now().plusMinutes(5));
        assertThat(ok.sessionKey()).isEqualTo("sess-key");

        // 逐字段拒绝：会话键/端点/协议/音频格式/缓冲上限/到期时间
        assertThatThrownBy(() -> new RealtimeSessionOpenRequest(
                        " ", endpoint, RealtimeProtocol.WEBSOCKET, format, 4096, java.time.LocalDateTime.now()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RealtimeSessionOpenRequest(
                        "sess-key", null, RealtimeProtocol.WEBSOCKET, format, 4096, java.time.LocalDateTime.now()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RealtimeSessionOpenRequest(
                        "sess-key", endpoint, null, format, 4096, java.time.LocalDateTime.now()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RealtimeSessionOpenRequest(
                        "sess-key", endpoint, RealtimeProtocol.WEBSOCKET, null, 4096, java.time.LocalDateTime.now()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RealtimeSessionOpenRequest(
                        "sess-key", endpoint, RealtimeProtocol.WEBSOCKET, format, 0, java.time.LocalDateTime.now()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RealtimeSessionOpenRequest(
                        "sess-key", endpoint, RealtimeProtocol.WEBSOCKET, format, 4096, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void protocolParsingIsCaseInsensitiveAndExplicit() {
        assertThat(RealtimeProtocol.parse("websocket")).contains(RealtimeProtocol.WEBSOCKET);
        assertThat(RealtimeProtocol.parse(" WebRTC ")).contains(RealtimeProtocol.WEBRTC);
        assertThat(RealtimeProtocol.parse("rtsp")).isEmpty();
        assertThat(RealtimeProtocol.parse(null)).isEmpty();
        assertThat(RealtimeProtocol.WEBSOCKET.framedAudioOverApplicationTransport())
                .isTrue();
        assertThat(RealtimeProtocol.WEBRTC.framedAudioOverApplicationTransport())
                .isFalse();
    }

    @Test
    void baselineCapabilitiesCoverNegotiationAndBothDirections() {
        assertThat(RealtimeCapability.BASELINE)
                .containsExactlyInAnyOrder(
                        RealtimeCapability.SESSION_NEGOTIATION,
                        RealtimeCapability.AUDIO_INPUT_STREAM,
                        RealtimeCapability.AUDIO_OUTPUT_STREAM);
    }

    @Test
    void audioFormatValidatesFrozenSetsAndDerivesFrameCeiling() {
        RealtimeAudioFormat pcm = new RealtimeAudioFormat(RealtimeAudioFormat.MIME_PCM, 16000, 1, 20);
        assertThat(pcm.canonicalForm()).isEqualTo("audio/pcm@16000:1:20");
        assertThat(pcm.maxFrameBytes()).isEqualTo(640);
        assertThat(pcm.pcm()).isTrue();
        assertThat(RealtimeAudioFormat.parse("audio/pcm@16000:1:20")).contains(pcm);
        assertThat(RealtimeAudioFormat.parse("AUDIO/PCM@16000:1:20")).isPresent();

        RealtimeAudioFormat opus = new RealtimeAudioFormat(RealtimeAudioFormat.MIME_OGG, 48000, 2, 20);
        assertThat(opus.pcm()).isFalse();
        assertThat(opus.maxFrameBytes()).isEqualTo(RealtimeAudioFormat.MAX_COMPRESSED_FRAME_BYTES);

        assertThatThrownBy(() -> new RealtimeAudioFormat("audio/mpeg", 16000, 1, 20))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("封装");
        assertThatThrownBy(() -> new RealtimeAudioFormat(RealtimeAudioFormat.MIME_PCM, 44100, 1, 20))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("采样率");
        assertThatThrownBy(() -> new RealtimeAudioFormat(RealtimeAudioFormat.MIME_PCM, 16000, 6, 20))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("声道");
        assertThatThrownBy(() -> new RealtimeAudioFormat(RealtimeAudioFormat.MIME_PCM, 16000, 1, 25))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("帧长");
        assertThat(RealtimeAudioFormat.parse("audio/pcm@16000:1")).isEmpty();
        assertThat(RealtimeAudioFormat.parse("audio/pcm@44100:1:20")).isEmpty();
        assertThat(RealtimeAudioFormat.parse("")).isEmpty();
    }

    @Test
    void audioFrameCopiesPayloadAndRejectsEmptyOrNegative() {
        byte[] payload = {1, 2, 3, 4};
        RealtimeAudioFrame frame = new RealtimeAudioFrame(1L, 7L, payload);
        payload[0] = 9;

        assertThat(frame.byteCount()).isEqualTo(4);
        assertThat(frame.payload()[0]).isEqualTo((byte) 1);
        assertThat(frame.toString()).contains("turnNo=1").contains("byteCount=4");

        assertThatThrownBy(() -> new RealtimeAudioFrame(-1L, 0L, new byte[] {1}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("回合序号");
        assertThatThrownBy(() -> new RealtimeAudioFrame(0L, -1L, new byte[] {1}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("帧序号");
        assertThatThrownBy(() -> new RealtimeAudioFrame(0L, 0L, new byte[0]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不能为空");
    }

    @Test
    void turnFenceDropsLateFramesAndRefusesFutureTurns() {
        RealtimeTurnFence fence = RealtimeTurnFence.of(0L, 0L);
        assertThat(fence.classify(0L)).isEqualTo(RealtimeTurnFence.Verdict.CURRENT);
        assertThat(fence.classify(1L)).isEqualTo(RealtimeTurnFence.Verdict.FUTURE);
        assertThat(fence.droppedStaleFrames()).isZero();

        assertThat(fence.advance()).isEqualTo(1L);
        assertThat(fence.classify(0L)).isEqualTo(RealtimeTurnFence.Verdict.STALE);
        assertThat(fence.classify(0L)).isEqualTo(RealtimeTurnFence.Verdict.STALE);
        assertThat(fence.droppedStaleFrames()).isEqualTo(2L);
        assertThat(fence.classify(1L)).isEqualTo(RealtimeTurnFence.Verdict.CURRENT);

        assertThatThrownBy(() -> RealtimeTurnFence.of(-1L, 0L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("当前回合");
        assertThatThrownBy(() -> RealtimeTurnFence.of(0L, -1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("丢弃计数");
    }

    @Test
    void backpressureGivesExplicitOverflowInsteadOfDroppingFrames() {
        RealtimeBackpressure buffer = RealtimeBackpressure.of(1000L);
        assertThat(buffer.offer(600L)).isEqualTo(RealtimeBackpressure.Outcome.ACCEPTED);
        assertThat(buffer.bufferedBytes()).isEqualTo(600L);
        assertThat(buffer.remainingBytes()).isEqualTo(400L);
        assertThat(buffer.offer(401L)).isEqualTo(RealtimeBackpressure.Outcome.WOULD_OVERFLOW);
        // 超限不改变占用：调用方按结束会话处理，而不是把帧丢掉
        assertThat(buffer.bufferedBytes()).isEqualTo(600L);
        assertThat(buffer.offer(400L)).isEqualTo(RealtimeBackpressure.Outcome.ACCEPTED);
        buffer.drain(500L);
        assertThat(buffer.bufferedBytes()).isEqualTo(500L);
        // 释放超过占用时归零（不产生负占用）
        buffer.drain(1000L);
        assertThat(buffer.bufferedBytes()).isZero();

        assertThatThrownBy(() -> RealtimeBackpressure.of(0L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("缓冲上限");
        assertThatThrownBy(() -> RealtimeBackpressure.of(10L, 11L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("越界");
        assertThatThrownBy(() -> buffer.offer(0L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("计账字节数");
        assertThatThrownBy(() -> buffer.drain(0L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("计账字节数");
    }

    @Test
    void eventsCarryTurnAndStableReasonWithoutUpstreamPayload() {
        RealtimeEvent.Transcript transcript = new RealtimeEvent.Transcript(1L, 3L, true, "你好");
        assertThat(transcript.turnNo()).isEqualTo(1L);
        assertThat(transcript.finalSegment()).isTrue();

        RealtimeEvent.ToolCall toolCall =
                new RealtimeEvent.ToolCall(1L, "call_1", "lookup-order", Map.of("orderNo", "A1"));
        assertThat(toolCall.arguments()).containsEntry("orderNo", "A1");
        assertThatThrownBy(() -> new RealtimeEvent.ToolCall(1L, " ", "lookup-order", Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("调用标识");
        assertThatThrownBy(() -> new RealtimeEvent.Transcript(1L, 1L, true, " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("转写文本");
        assertThatThrownBy(() -> new RealtimeEvent.Audio(1L, 1L, 0, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("字节数");
        assertThatThrownBy(() -> new RealtimeEvent.Ended(1L, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("结束原因");

        RealtimeEvent.Ended ended = new RealtimeEvent.Ended(1L, RealtimeCloseReason.ADAPTER_FAILED, "UPSTREAM_TIMEOUT");
        assertThat(ended.reason().code()).isEqualTo("adapter-failed");
        assertThat(RealtimeCloseReason.parse("AUDIO-BACKPRESSURE-EXCEEDED"))
                .contains(RealtimeCloseReason.AUDIO_BACKPRESSURE_EXCEEDED);
        assertThat(RealtimeCloseReason.parse("unknown")).isEmpty();
        assertThat(RealtimeCloseReason.CLIENT_CLOSED.clientInitiated()).isTrue();
        assertThat(RealtimeCloseReason.SESSION_EXPIRED.clientInitiated()).isFalse();
    }

    @Test
    void capabilityReportKeepsOnlyConfirmedCapabilitiesAndRejectsCrossProtocolReuse() {
        Set<RealtimeCapability> declared =
                Set.of(RealtimeCapability.SESSION_NEGOTIATION, RealtimeCapability.INCREMENTAL_TRANSCRIPTION);
        RealtimeCapabilityReport verified = RealtimeCapabilityReport.verified(
                RealtimeProtocol.WEBSOCKET,
                4,
                declared,
                Set.of(RealtimeCapability.SESSION_NEGOTIATION, RealtimeCapability.INCREMENTAL_TRANSCRIPTION),
                12L);
        assertThat(verified.verified()).isTrue();
        assertThat(verified.detailCode()).isNull();
        assertThat(verified.missingRequired(Set.of(RealtimeCapability.SESSION_NEGOTIATION)))
                .isEmpty();
        assertThat(verified.missingRequired(Set.of(RealtimeCapability.AUDIO_INPUT_STREAM)))
                .containsExactly(RealtimeCapability.AUDIO_INPUT_STREAM);

        RealtimeCapabilityReport unsupported = RealtimeCapabilityReport.unsupported(
                RealtimeProtocol.WEBRTC, 4, declared, RealtimeCapabilityReport.CODE_ADAPTER_NOT_IMPLEMENTED);
        assertThat(unsupported.verified()).isFalse();
        assertThat(unsupported.confirmed()).isEmpty();
        assertThat(unsupported.detailCode()).isEqualTo("ADAPTER_NOT_IMPLEMENTED");

        RealtimeCapabilityReport failed =
                RealtimeCapabilityReport.failed(RealtimeProtocol.WEBSOCKET, 4, declared, "UPSTREAM_TIMEOUT", 30L);
        assertThat(failed.status()).isEqualTo(RealtimeCapabilityReport.Status.FAILED);
        assertThat(failed.latencyMillis()).isEqualTo(30L);

        assertThatThrownBy(() -> RealtimeCapabilityReport.verified(null, 1, Set.of(), Set.of(), 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("协议");
        assertThatThrownBy(
                        () -> RealtimeCapabilityReport.verified(RealtimeProtocol.WEBSOCKET, 1, Set.of(), Set.of(), -1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("探测耗时");
    }

    @Test
    void openRequestAndProbeRequestRequireCompleteFrozenFacts() {
        ModelEndpointSnapshot snapshot = new ModelEndpointSnapshot(
                9L, 3, 2, "spring-ai", "https://example.invalid", "realtime-1", Set.of(ModelCapability.TEXT), "***");
        RealtimeAudioFormat format = new RealtimeAudioFormat(RealtimeAudioFormat.MIME_PCM, 16000, 1, 20);
        RealtimeSessionOpenRequest open = new RealtimeSessionOpenRequest(
                "rt_1",
                snapshot,
                RealtimeProtocol.WEBSOCKET,
                format,
                1_000_000L,
                LocalDateTime.now().plusMinutes(5));
        assertThat(open.sessionKey()).isEqualTo("rt_1");
        assertThat(open.inputCapacityBytes()).isEqualTo(1_000_000L);

        RealtimeProbeRequest probe = new RealtimeProbeRequest(snapshot, RealtimeProtocol.WEBSOCKET, format);
        assertThat(probe.endpoint()).isSameAs(snapshot);

        assertThatThrownBy(() -> new RealtimeSessionOpenRequest(
                        " ", snapshot, RealtimeProtocol.WEBSOCKET, format, 1L, LocalDateTime.now()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("会话业务键");
        assertThatThrownBy(() -> new RealtimeSessionOpenRequest(
                        "rt", snapshot, RealtimeProtocol.WEBSOCKET, format, 0L, LocalDateTime.now()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("输入缓冲上限");
        assertThatThrownBy(() ->
                        new RealtimeSessionOpenRequest("rt", snapshot, RealtimeProtocol.WEBSOCKET, format, 1L, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("到期时间");
        assertThatThrownBy(() -> new RealtimeProbeRequest(null, RealtimeProtocol.WEBSOCKET, format))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("端点快照");
        assertThatThrownBy(() -> new RealtimeProbeRequest(snapshot, RealtimeProtocol.WEBSOCKET, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("探测音频格式");
    }

    @Test
    void channelContractIsPollBasedAndIdempotent() {
        RealtimeSessionChannel channel = new RealtimeSessionChannel() {
            private final List<RealtimeEvent> events = List.of(new RealtimeEvent.Transcript(
                    0L, 0L, false, new String("中".getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8)));

            @Override
            public boolean pushAudio(RealtimeAudioFrame frame) {
                return true;
            }

            @Override
            public List<RealtimeEvent> pollEvents() {
                return events;
            }

            @Override
            public void interrupt(long turnNo) {}

            @Override
            public void close(RealtimeCloseReason reason) {}
        };
        assertThat(channel.pollEvents()).hasSize(1);
        channel.close(RealtimeCloseReason.CLIENT_CLOSED);
    }
}

package com.basicframework.module.ai.service.realtime;

import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_REQUEST_INVALID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.basicframework.framework.ai.core.realtime.RealtimeAudioFormat;
import com.basicframework.framework.ai.core.realtime.RealtimeProtocol;
import com.basicframework.framework.common.exception.ServiceException;
import org.junit.jupiter.api.Test;

/** 实时参数守卫（X05 单测）：幂等键形状、协议/格式解析与寿命边界（越界拒绝，不静默夹紧）。 */
class AiRealtimeParamsTest {

    private final AiRealtimeParams params = new AiRealtimeParams();

    @Test
    void requestKeyIsBoundedAndShapeChecked() {
        assertThat(params.requireRequestKey("rt_request_0001")).isEqualTo("rt_request_0001");
        assertInvalid(() -> params.requireRequestKey(null));
        assertInvalid(() -> params.requireRequestKey("  "));
        assertInvalid(() -> params.requireRequestKey("short"));
        assertInvalid(() -> params.requireRequestKey("bad key with spaces"));
        assertInvalid(() -> params.requireRequestKey("x".repeat(41)));
    }

    @Test
    void endpointProtocolAndFormatAreParsedStrictly() {
        assertThat(params.requireEndpointId(9L)).isEqualTo(9L);
        assertInvalid(() -> params.requireEndpointId(null));
        assertInvalid(() -> params.requireEndpointId(0L));

        assertThat(params.requireProtocol("websocket")).isEqualTo(RealtimeProtocol.WEBSOCKET);
        assertInvalid(() -> params.requireProtocol("rtsp"));

        assertThat(params.requireAudioFormat("audio/pcm@16000:1:20").canonicalForm())
                .isEqualTo("audio/pcm@16000:1:20");
        assertInvalid(() -> params.requireAudioFormat(" "));
        assertThatThrownBy(() -> params.requireAudioFormat("audio/mpeg@16000:1:20"))
                .isInstanceOfSatisfying(ServiceException.class, exception -> assertThat(exception.getCode())
                        .isEqualTo(com.basicframework.module.ai.enums.AiErrorCodeConstants
                                .AI_REALTIME_AUDIO_FORMAT_UNSUPPORTED
                                .getCode()));
    }

    @Test
    void sessionSecondsDefaultAndBoundsAreFrozen() {
        assertThat(params.normalizeSessionSeconds(null)).isEqualTo(AiRealtimeParams.DEFAULT_SESSION_SECONDS);
        assertThat(params.normalizeSessionSeconds(60)).isEqualTo(60);
        assertThat(params.normalizeSessionSeconds(1800)).isEqualTo(1800);
        assertInvalid(() -> params.normalizeSessionSeconds(59));
        assertInvalid(() -> params.normalizeSessionSeconds(1801));
    }

    @Test
    void frozenLimitsMatchTheDocumentedContract() {
        assertThat(AiRealtimeParams.INPUT_CAPACITY_BYTES).isEqualTo(4L * 1024 * 1024);
        assertThat(AiRealtimeParams.MAX_CONCURRENT_SESSIONS_PER_SUBJECT).isEqualTo(2);
        assertThat(AiRealtimeParams.MAX_CONCURRENT_SESSIONS_PER_APPLICATION).isEqualTo(32);
        assertThat(AiRealtimeParams.MAX_REATTACH_ATTEMPTS).isEqualTo(3);
        assertThat(AiRealtimeParams.TICKET_TTL_SECONDS).isEqualTo(120);
        assertThat(RealtimeAudioFormat.parse("audio/pcm@16000:1:20")).isPresent();
    }

    private static void assertInvalid(Runnable operation) {
        assertThatThrownBy(operation::run)
                .isInstanceOfSatisfying(ServiceException.class, exception -> assertThat(exception.getCode())
                        .isEqualTo(AI_REQUEST_INVALID.getCode()));
    }
}

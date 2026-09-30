package com.basicframework.module.ai.service.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.basicframework.framework.ai.core.realtime.RealtimeAudioFrame;
import com.basicframework.framework.ai.core.realtime.RealtimeCloseReason;
import com.basicframework.framework.ai.core.realtime.RealtimeEvent;
import com.basicframework.framework.ai.core.realtime.RealtimeProtocol;
import com.basicframework.framework.ai.core.realtime.RealtimeSessionChannel;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 适配器注册表与会话通道注册表（X05 单测）：一个协议只允许一个适配器、空注册表合法、
 * 通道登记/替换/关闭幂等且关闭失败不掩盖已落库的终态。
 */
class AiRealtimeRegistryTest {

    @Test
    void adapterRegistryAcceptsEmptyAndSingleAdapterPerProtocol() {
        AiRealtimeAdapterRegistry empty = new AiRealtimeAdapterRegistry(List.of());
        assertThat(empty.find(RealtimeProtocol.WEBSOCKET)).isEmpty();
        assertThat(empty.registeredProtocols()).isEmpty();
        assertThat(empty.find(null)).isEmpty();

        StubAdapter adapter = new StubAdapter();
        AiRealtimeAdapterRegistry registry = new AiRealtimeAdapterRegistry(List.of(adapter));
        assertThat(registry.find(RealtimeProtocol.WEBSOCKET)).contains(adapter);
        assertThat(registry.find(RealtimeProtocol.WEBRTC)).isEmpty();
        assertThat(registry.registeredProtocols()).containsExactly(RealtimeProtocol.WEBSOCKET);

        AiRealtimeAdapterRegistry nullBeans = new AiRealtimeAdapterRegistry(null);
        assertThat(nullBeans.registeredProtocols()).isEmpty();
    }

    @Test
    void duplicateAdapterForSameProtocolFailsFast() {
        assertThatThrownBy(() -> new AiRealtimeAdapterRegistry(List.of(new StubAdapter(), new StubAdapter())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("同一协议注册了多个实时适配器");
    }

    @Test
    void channelRegistryReplacesPreviousChannelAndClosesIdempotently() {
        AiRealtimeChannels channels = new AiRealtimeChannels();
        CountingChannel first = new CountingChannel();
        CountingChannel second = new CountingChannel();

        channels.register(42L, first);
        assertThat(channels.find(42L)).contains(first);

        channels.register(42L, second);
        assertThat(channels.find(42L)).contains(second);
        assertThat(first.closedReasons).containsExactly(RealtimeCloseReason.ADAPTER_ENDED);

        channels.register(42L, second);
        channels.close(42L, RealtimeCloseReason.CLIENT_CLOSED);
        assertThat(channels.find(42L)).isEmpty();
        assertThat(second.closedReasons).containsExactly(RealtimeCloseReason.CLIENT_CLOSED);

        channels.close(42L, RealtimeCloseReason.CLIENT_CLOSED);
        assertThat(second.closedReasons).containsExactly(RealtimeCloseReason.CLIENT_CLOSED);
    }

    @Test
    void channelRegistryIgnoresIncompleteRegistrationsAndFailingClose() {
        AiRealtimeChannels channels = new AiRealtimeChannels();
        channels.register(null, new CountingChannel());
        channels.register(42L, null);
        assertThat(channels.find(null)).isEmpty();

        CountingChannel failing = new CountingChannel(true);
        channels.register(42L, failing);
        assertThatCode(() -> channels.close(42L, RealtimeCloseReason.SESSION_EXPIRED))
                .doesNotThrowAnyException();
        assertThat(channels.find(42L)).isEmpty();
    }

    @Test
    void channelRegistryKeepsLocalHandlesBounded() {
        AiRealtimeChannels channels = new AiRealtimeChannels();
        for (long index = 1; index <= AiRealtimeChannels.MAX_LOCAL_CHANNELS + 5; index++) {
            channels.register(index, new CountingChannel());
        }

        assertThat(channels.size()).isEqualTo(AiRealtimeChannels.MAX_LOCAL_CHANNELS);
        assertThat(channels.find(1L)).as("最旧通道被关闭并移除").isEmpty();
        assertThat(channels.find((long) AiRealtimeChannels.MAX_LOCAL_CHANNELS + 5))
                .as("最新通道保留")
                .isPresent();
    }

    private static final class StubAdapter implements com.basicframework.framework.ai.core.realtime.RealtimeAdapter {

        @Override
        public RealtimeProtocol protocol() {
            return RealtimeProtocol.WEBSOCKET;
        }

        @Override
        public java.util.Set<com.basicframework.framework.ai.core.realtime.RealtimeAudioFormat>
                supportedAudioFormats() {
            return java.util.Set.of();
        }

        @Override
        public com.basicframework.framework.ai.core.realtime.RealtimeCapabilityReport probe(
                com.basicframework.framework.ai.core.realtime.RealtimeProbeRequest request) {
            throw new UnsupportedOperationException("单测替身不探测");
        }

        @Override
        public RealtimeSessionChannel open(
                com.basicframework.framework.ai.core.realtime.RealtimeSessionOpenRequest request) {
            throw new UnsupportedOperationException("单测替身不打开通道");
        }
    }

    private static final class CountingChannel implements RealtimeSessionChannel {

        private final java.util.List<RealtimeCloseReason> closedReasons = new java.util.ArrayList<>();

        private final boolean failOnClose;

        CountingChannel() {
            this(false);
        }

        CountingChannel(boolean failOnClose) {
            this.failOnClose = failOnClose;
        }

        @Override
        public boolean pushAudio(RealtimeAudioFrame frame) {
            return true;
        }

        @Override
        public List<RealtimeEvent> pollEvents() {
            return List.of();
        }

        @Override
        public void interrupt(long turnNo) {
            // 单测替身不打断
        }

        @Override
        public void close(RealtimeCloseReason reason) {
            closedReasons.add(reason);
            if (failOnClose) {
                throw new IllegalStateException("transport already gone");
            }
        }
    }
}

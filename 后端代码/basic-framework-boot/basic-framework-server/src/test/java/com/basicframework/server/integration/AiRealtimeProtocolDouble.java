package com.basicframework.server.integration;

import com.basicframework.framework.ai.core.realtime.RealtimeAdapter;
import com.basicframework.framework.ai.core.realtime.RealtimeAudioFormat;
import com.basicframework.framework.ai.core.realtime.RealtimeAudioFrame;
import com.basicframework.framework.ai.core.realtime.RealtimeCapability;
import com.basicframework.framework.ai.core.realtime.RealtimeCapabilityReport;
import com.basicframework.framework.ai.core.realtime.RealtimeCloseReason;
import com.basicframework.framework.ai.core.realtime.RealtimeEvent;
import com.basicframework.framework.ai.core.realtime.RealtimeProbeRequest;
import com.basicframework.framework.ai.core.realtime.RealtimeProtocol;
import com.basicframework.framework.ai.core.realtime.RealtimeSessionChannel;
import com.basicframework.framework.ai.core.realtime.RealtimeSessionOpenRequest;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 本地实时协议替身（X05 集成测试夹具）：**不是**真实供应商适配器，只用来证明平台侧垂直流程。
 *
 * <p>为什么必须有替身而不是 mock：集成测试要走通"受理 → 推流 → 打断 → 重连 → 关闭"的完整链路，
 * 其中包含适配器与平台之间的往返（推帧、拉事件、打断、关闭）。替身实现真实的
 * {@link RealtimeAdapter} 契约（真实探测结论 + 真实通道语义），让被测代码**没有任何测试开关**；
 * 真实供应商链路在本环境未验证（ADR 0052），因此这里明确不是供应商实现。
 *
 * <p>可编排的能力（夹具用）：
 * <ul>
 *   <li>脚本事件：下一次 {@code pushAudio} 时抛出预置事件（转写/下行音频/工具调用）；</li>
 *   <li>是否消费帧：{@code queueFrames=true} 时帧停留在适配器队列（平台侧有界缓冲持续占用，
 *       用于验证背压触顶结束会话）；</li>
 *   <li>调用计数：探测次数、打开次数、推帧次数、打断次数、关闭原因。</li>
 * </ul>
 */
class AiRealtimeProtocolDouble implements RealtimeAdapter {

    /** 替身支持的音频格式：PCM 20ms 帧（640 字节）与压缩封装（单帧上限 64 KiB，便于触发背压）。 */
    static final Set<RealtimeAudioFormat> SUPPORTED_FORMATS = Set.of(
            new RealtimeAudioFormat(RealtimeAudioFormat.MIME_PCM, 16000, 1, 20),
            new RealtimeAudioFormat(RealtimeAudioFormat.MIME_OGG, 16000, 1, 20));

    private final AtomicInteger probeCalls = new AtomicInteger();

    private final AtomicInteger openCalls = new AtomicInteger();

    private final List<LocalChannel> channels = new CopyOnWriteArrayList<>();

    private volatile List<RealtimeEvent> scriptedEvents = List.of();

    private volatile boolean queueFrames;

    private volatile boolean probeFails;

    @Override
    public RealtimeProtocol protocol() {
        return RealtimeProtocol.WEBSOCKET;
    }

    @Override
    public Set<RealtimeAudioFormat> supportedAudioFormats() {
        return SUPPORTED_FORMATS;
    }

    @Override
    public RealtimeCapabilityReport probe(RealtimeProbeRequest request) {
        probeCalls.incrementAndGet();
        if (probeFails) {
            return RealtimeCapabilityReport.failed(
                    request.protocol(), request.endpoint().configRevision(), Set.of(), "UPSTREAM_TIMEOUT", 12L);
        }
        Set<RealtimeCapability> declared = new LinkedHashSet<>(RealtimeCapability.BASELINE);
        declared.add(RealtimeCapability.INCREMENTAL_TRANSCRIPTION);
        declared.add(RealtimeCapability.TOOL_CALL_BRIDGE);
        return RealtimeCapabilityReport.verified(
                request.protocol(), request.endpoint().configRevision(), declared, declared, 9L);
    }

    @Override
    public RealtimeSessionChannel open(RealtimeSessionOpenRequest request) {
        openCalls.incrementAndGet();
        LocalChannel channel = new LocalChannel(request.sessionKey());
        channels.add(channel);
        return channel;
    }

    /** 预置下一次推帧时要抛出的事件。 */
    void scriptEvents(List<RealtimeEvent> events) {
        this.scriptedEvents = List.copyOf(events);
    }

    /** 让帧停留在适配器队列（平台侧有界缓冲持续占用）。 */
    void queueFrames(boolean queue) {
        this.queueFrames = queue;
    }

    /** 让探测失败（用于验证"未确认不可用"的拒绝路径）。 */
    void probeFails(boolean fails) {
        this.probeFails = fails;
    }

    int probeCalls() {
        return probeCalls.get();
    }

    int openCalls() {
        return openCalls.get();
    }

    List<LocalChannel> channels() {
        return List.copyOf(channels);
    }

    LocalChannel channelOf(String sessionKey) {
        return channels.stream()
                .filter(channel -> sessionKey.equals(channel.sessionKey()))
                .reduce((first, second) -> second)
                .orElse(null);
    }

    void reset() {
        probeCalls.set(0);
        openCalls.set(0);
        channels.clear();
        scriptedEvents = List.of();
        queueFrames = false;
        probeFails = false;
    }

    /** 会话通道替身：真实回答"是否已消费帧"，并支持打断后继续抛出旧回合事件（验证栅栏）。 */
    static final class LocalChannel implements RealtimeSessionChannel {

        private final String sessionKey;

        private final AtomicInteger pushedFrames = new AtomicInteger();

        private final List<Long> interrupts = new CopyOnWriteArrayList<>();

        private final List<RealtimeCloseReason> closeReasons = new CopyOnWriteArrayList<>();

        private final List<RealtimeEvent> pending = new CopyOnWriteArrayList<>();

        private volatile boolean queueFrames;

        private volatile List<RealtimeEvent> nextEvents = List.of();

        LocalChannel(String sessionKey) {
            this.sessionKey = sessionKey;
        }

        String sessionKey() {
            return sessionKey;
        }

        @Override
        public boolean pushAudio(RealtimeAudioFrame frame) {
            pushedFrames.incrementAndGet();
            List<RealtimeEvent> events = nextEvents;
            if (!events.isEmpty()) {
                pending.addAll(events);
                nextEvents = List.of();
            }
            return !queueFrames;
        }

        @Override
        public List<RealtimeEvent> pollEvents() {
            List<RealtimeEvent> copy = new ArrayList<>(pending);
            pending.clear();
            return copy;
        }

        @Override
        public void interrupt(long turnNo) {
            interrupts.add(turnNo);
        }

        @Override
        public void close(RealtimeCloseReason reason) {
            closeReasons.add(reason);
        }

        void queueFrames(boolean queue) {
            this.queueFrames = queue;
        }

        void emitOnNextPush(RealtimeEvent event) {
            this.nextEvents = List.of(event);
        }

        int pushedFrames() {
            return pushedFrames.get();
        }

        List<Long> interrupts() {
            return List.copyOf(interrupts);
        }

        List<RealtimeCloseReason> closeReasons() {
            return List.copyOf(closeReasons);
        }

        /** 便利构造：一条下行音频事件（用于打断后旧回合的"晚到帧"）。 */
        static RealtimeEvent audio(long turnNo, long seq, int byteCount) {
            return new RealtimeEvent.Audio(turnNo, seq, byteCount, false);
        }

        /** 便利构造：一条工具调用事件。 */
        static RealtimeEvent toolCall(long turnNo, String callId, String toolCode, Map<String, Object> arguments) {
            return new RealtimeEvent.ToolCall(turnNo, callId, toolCode, arguments);
        }
    }
}

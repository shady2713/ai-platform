package com.basicframework.module.ai.service.realtime;

import com.basicframework.framework.ai.core.realtime.RealtimeCloseReason;
import com.basicframework.framework.ai.core.realtime.RealtimeEvent;
import com.basicframework.framework.ai.core.realtime.RealtimeTurnFence;
import com.basicframework.framework.common.util.json.JsonUtils;
import com.basicframework.module.ai.dal.dataobject.realtime.AiRealtimeEventDO;
import com.basicframework.module.ai.dal.dataobject.realtime.AiRealtimeSessionDO;
import com.basicframework.module.ai.dal.dataobject.realtime.AiRealtimeToolCallDO;
import com.basicframework.module.ai.dal.mysql.realtime.AiRealtimeEventMapper;
import com.basicframework.module.ai.dal.mysql.realtime.AiRealtimeSessionMapper;
import com.basicframework.module.ai.dal.mysql.realtime.AiRealtimeToolCallMapper;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 适配器事件落库（X05）：把上游事件按**回合栅栏**消费成平台事实。
 *
 * <p>三条不变量（AT-069 的核心）：
 * <ol>
 *   <li><b>晚到的旧回合事件一律丢弃并计数</b>：打断后已在飞的音频/转写不再进入会话状态，
 *       丢弃行为记 STALE_DROPPED 事件——"不得静默丢弃"指的是必须可审计；</li>
 *   <li><b>凭空出现的回合号是适配器违约</b>：回合号大于当前回合说明适配器发明了回合，
 *       按 {@code adapter-failed} 结束会话，而不是猜测或夹紧；</li>
 *   <li><b>工具调用只是数据</b>：登记为 PROPOSED 等客户端经受控入口执行，事件处理器不执行任何工具。</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiRealtimeEventApplier {

    private final AiRealtimeSessionMapper sessionMapper;

    private final AiRealtimeEventMapper eventMapper;

    private final AiRealtimeToolCallMapper toolCallMapper;

    /**
     * 消费一批事件。
     *
     * @return 需要结束会话时的原因与稳定明细；不需要结束时返回 {@link Outcome#none()}
     */
    public Outcome apply(AiRealtimeSessionDO session, List<RealtimeEvent> events) {
        if (events == null || events.isEmpty()) {
            return Outcome.none();
        }
        long currentTurn = session.getTurnNo() == null ? 0L : session.getTurnNo();
        int droppedBefore = session.getDroppedStaleFrames() == null ? 0 : session.getDroppedStaleFrames();
        RealtimeTurnFence fence = RealtimeTurnFence.of(currentTurn, droppedBefore);
        for (RealtimeEvent event : events) {
            switch (fence.classify(event.turnNo())) {
                case STALE -> recordStale(session.getId(), event);
                case FUTURE -> {
                    record(
                            session.getId(),
                            AiRealtimeEventDO.TYPE_STALE_DROPPED,
                            event.turnNo(),
                            seqOf(event),
                            "",
                            0,
                            "TURN_FUTURE:" + typeOf(event),
                            "stale:future:" + typeOf(event) + ":" + event.turnNo() + ":" + seqOf(event));
                    return new Outcome(RealtimeCloseReason.ADAPTER_FAILED, "TURN_FUTURE");
                }
                case CURRENT -> {
                    Outcome outcome = applyCurrent(session, event);
                    if (outcome.shouldClose()) {
                        return outcome;
                    }
                }
            }
        }
        int droppedNow = (int) Math.min(Integer.MAX_VALUE, fence.droppedStaleFrames());
        if (droppedNow > droppedBefore) {
            sessionMapper.addDroppedStaleFrames(session.getId(), droppedNow - droppedBefore);
        }
        return Outcome.none();
    }

    /** 当前回合事件：转写 / 下行音频 / 工具调用请求 / 上游结束。 */
    private Outcome applyCurrent(AiRealtimeSessionDO session, RealtimeEvent event) {
        if (event instanceof RealtimeEvent.Transcript transcript) {
            if (transcript.text().length() > AiRealtimeParams.MAX_TRANSCRIPT_LENGTH) {
                // 超长转写不是"可以截断展示"的内容：按适配器违约结束，不静默截断
                return new Outcome(RealtimeCloseReason.ADAPTER_FAILED, "TRANSCRIPT_TOO_LONG");
            }
            record(
                    session.getId(),
                    AiRealtimeEventDO.TYPE_TRANSCRIPT,
                    transcript.turnNo(),
                    transcript.seq(),
                    transcript.text(),
                    0,
                    transcript.finalSegment() ? "FINAL" : "PARTIAL",
                    "transcript:" + transcript.turnNo() + ":" + transcript.seq() + ":"
                            + (transcript.finalSegment() ? "F" : "P"));
            return Outcome.none();
        }
        if (event instanceof RealtimeEvent.Audio audio) {
            record(
                    session.getId(),
                    AiRealtimeEventDO.TYPE_AUDIO,
                    audio.turnNo(),
                    audio.seq(),
                    "",
                    audio.byteCount(),
                    audio.last() ? "LAST" : "DELIVERED",
                    "audio:" + audio.turnNo() + ":" + audio.seq());
            return Outcome.none();
        }
        if (event instanceof RealtimeEvent.ToolCall toolCall) {
            return registerToolCall(session, toolCall);
        }
        if (event instanceof RealtimeEvent.Ended ended) {
            return new Outcome(ended.reason(), ended.detailCode());
        }
        return Outcome.none();
    }

    /** 登记工具调用请求（唯一键去重；参数非法/超限即拒绝，不截断）。 */
    private Outcome registerToolCall(AiRealtimeSessionDO session, RealtimeEvent.ToolCall toolCall) {
        String argumentsJson;
        try {
            argumentsJson = JsonUtils.toJsonString(toolCall.arguments());
        } catch (RuntimeException serializationFailure) {
            argumentsJson = null;
        }
        boolean invalid = argumentsJson == null;
        boolean tooLarge = !invalid && argumentsJson.length() > AiRealtimeParams.MAX_ARGUMENTS_JSON_LENGTH;
        String storedJson = invalid || tooLarge ? "{}" : argumentsJson;
        String resultCode = invalid ? "ARGUMENTS_INVALID" : tooLarge ? "ARGUMENTS_TOO_LARGE" : null;
        toolCallMapper.insertDeduped(
                session.getId(),
                toolCall.turnNo(),
                toolCall.callId(),
                toolCall.toolCode(),
                null,
                "",
                storedJson,
                sha256Hex(storedJson),
                resultCode == null ? AiRealtimeToolCallDO.STATUS_PROPOSED : AiRealtimeToolCallDO.STATUS_REJECTED,
                resultCode,
                "ai-realtime");
        record(
                session.getId(),
                AiRealtimeEventDO.TYPE_TOOL_CALL,
                toolCall.turnNo(),
                0,
                toolCall.toolCode(),
                0,
                toolCall.callId() + ":" + (resultCode == null ? "PROPOSED" : "REJECTED"),
                "tool:" + toolCall.callId() + ":" + (resultCode == null ? "PROPOSED" : "REJECTED"));
        return Outcome.none();
    }

    /** 丢弃过期事件：计数并留痕（打断后旧回合的帧必须被丢弃，且必须可审计）。 */
    private void recordStale(Long sessionId, RealtimeEvent event) {
        log.debug("实时会话丢弃过期事件：sessionId={}，type={}，turnNo={}", sessionId, typeOf(event), event.turnNo());
        record(
                sessionId,
                AiRealtimeEventDO.TYPE_STALE_DROPPED,
                event.turnNo(),
                seqOf(event),
                "",
                byteCountOf(event),
                "STALE:" + typeOf(event),
                "stale:" + typeOf(event) + ":" + event.turnNo() + ":" + seqOf(event));
    }

    /** 追加一条事件（去重键保证重放不产生重复行）。 */
    public void record(
            Long sessionId,
            String type,
            long turnNo,
            long eventSeq,
            String text,
            int byteCount,
            String detailCode,
            String dedupKey) {
        eventMapper.insertDeduped(
                sessionId,
                type,
                turnNo,
                eventSeq,
                text == null ? "" : text,
                byteCount,
                detailCode == null ? "" : detailCode,
                dedupKey,
                "ai-realtime");
    }

    private static String typeOf(RealtimeEvent event) {
        return event.getClass().getSimpleName().toUpperCase(Locale.ROOT);
    }

    private static long seqOf(RealtimeEvent event) {
        if (event instanceof RealtimeEvent.Transcript transcript) {
            return transcript.seq();
        }
        if (event instanceof RealtimeEvent.Audio audio) {
            return audio.seq();
        }
        return 0L;
    }

    private static int byteCountOf(RealtimeEvent event) {
        return event instanceof RealtimeEvent.Audio audio ? audio.byteCount() : 0;
    }

    /** 参数摘要（与票据摘要同一口径：UTF-8 + SHA-256 小写十六进制）。 */
    private static String sha256Hex(String value) {
        try {
            return java.util.HexFormat.of()
                    .formatHex(java.security.MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 不可用", impossible);
        }
    }

    /** 事件消费结论：需要结束会话时给出稳定原因与明细。 */
    public record Outcome(RealtimeCloseReason closeReason, String detailCode) {

        /** 不需要结束会话。 */
        public static Outcome none() {
            return new Outcome(null, null);
        }

        /** 是否需要结束会话。 */
        public boolean shouldClose() {
            return closeReason != null;
        }
    }
}

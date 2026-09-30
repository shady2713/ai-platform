package com.basicframework.module.ai.service.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.basicframework.framework.ai.core.realtime.RealtimeCloseReason;
import com.basicframework.framework.ai.core.realtime.RealtimeEvent;
import com.basicframework.module.ai.dal.dataobject.realtime.AiRealtimeEventDO;
import com.basicframework.module.ai.dal.dataobject.realtime.AiRealtimeSessionDO;
import com.basicframework.module.ai.dal.dataobject.realtime.AiRealtimeToolCallDO;
import com.basicframework.module.ai.dal.mysql.realtime.AiRealtimeEventMapper;
import com.basicframework.module.ai.dal.mysql.realtime.AiRealtimeSessionMapper;
import com.basicframework.module.ai.dal.mysql.realtime.AiRealtimeToolCallMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 适配器事件落库（X05 单测）：旧回合事件丢弃并计数、凭空回合按适配器违约结束、工具调用只登记不执行。
 */
@ExtendWith(MockitoExtension.class)
class AiRealtimeEventApplierTest {

    private static final Long SESSION_ID = 42L;

    @Mock
    private AiRealtimeSessionMapper sessionMapper;

    @Mock
    private AiRealtimeEventMapper eventMapper;

    @Mock
    private AiRealtimeToolCallMapper toolCallMapper;

    @InjectMocks
    private AiRealtimeEventApplier applier;

    @Test
    void emptyEventBatchChangesNothing() {
        assertThat(applier.apply(session(0L), List.of()).shouldClose()).isFalse();
        verify(eventMapper, never())
                .insertDeduped(any(), any(), anyLong(), anyLong(), any(), anyInt(), any(), any(), any());
    }

    @Test
    void transcriptAndAudioEventsAreRecordedWithStableDedupKeys() {
        AiRealtimeEventApplier.Outcome outcome = applier.apply(
                session(0L),
                List.of(
                        new RealtimeEvent.Transcript(0L, 1L, false, "你好"),
                        new RealtimeEvent.Transcript(0L, 2L, true, "你好，世界"),
                        new RealtimeEvent.Audio(0L, 3L, 320, true)));

        assertThat(outcome.shouldClose()).isFalse();
        verify(eventMapper)
                .insertDeduped(
                        eq(SESSION_ID),
                        eq(AiRealtimeEventDO.TYPE_TRANSCRIPT),
                        eq(0L),
                        eq(1L),
                        eq("你好"),
                        eq(0),
                        eq("PARTIAL"),
                        eq("transcript:0:1:P"),
                        any());
        verify(eventMapper)
                .insertDeduped(
                        eq(SESSION_ID),
                        eq(AiRealtimeEventDO.TYPE_TRANSCRIPT),
                        eq(0L),
                        eq(2L),
                        eq("你好，世界"),
                        eq(0),
                        eq("FINAL"),
                        eq("transcript:0:2:F"),
                        any());
        verify(eventMapper)
                .insertDeduped(
                        eq(SESSION_ID),
                        eq(AiRealtimeEventDO.TYPE_AUDIO),
                        eq(0L),
                        eq(3L),
                        eq(""),
                        eq(320),
                        eq("LAST"),
                        eq("audio:0:3"),
                        any());
    }

    @Test
    void staleEventsAreDroppedAndCountedInsteadOfApplied() {
        AiRealtimeEventApplier.Outcome outcome = applier.apply(
                session(2L),
                List.of(
                        new RealtimeEvent.Audio(1L, 5L, 160, false),
                        new RealtimeEvent.Transcript(1L, 6L, true, "旧回合")));

        assertThat(outcome.shouldClose()).isFalse();
        verify(sessionMapper).addDroppedStaleFrames(SESSION_ID, 2);
        verify(eventMapper)
                .insertDeduped(
                        eq(SESSION_ID),
                        eq(AiRealtimeEventDO.TYPE_STALE_DROPPED),
                        eq(1L),
                        eq(5L),
                        eq(""),
                        eq(160),
                        eq("STALE:AUDIO"),
                        eq("stale:AUDIO:1:5"),
                        any());
        verify(eventMapper, never())
                .insertDeduped(
                        eq(SESSION_ID),
                        eq(AiRealtimeEventDO.TYPE_TRANSCRIPT),
                        anyLong(),
                        anyLong(),
                        any(),
                        anyInt(),
                        any(),
                        any(),
                        any());
    }

    @Test
    void futureTurnIsAnAdapterContractViolation() {
        AiRealtimeEventApplier.Outcome outcome =
                applier.apply(session(1L), List.of(new RealtimeEvent.Audio(5L, 1L, 160, false)));

        assertThat(outcome.shouldClose()).isTrue();
        assertThat(outcome.closeReason()).isEqualTo(RealtimeCloseReason.ADAPTER_FAILED);
        assertThat(outcome.detailCode()).isEqualTo("TURN_FUTURE");
        verify(sessionMapper, never()).addDroppedStaleFrames(any(), anyInt());
    }

    @Test
    void oversizedTranscriptIsRejectedInsteadOfTruncated() {
        AiRealtimeEventApplier.Outcome outcome =
                applier.apply(session(0L), List.of(new RealtimeEvent.Transcript(0L, 1L, true, "x".repeat(4001))));

        assertThat(outcome.shouldClose()).isTrue();
        assertThat(outcome.detailCode()).isEqualTo("TRANSCRIPT_TOO_LONG");
        verify(eventMapper, never())
                .insertDeduped(any(), any(), anyLong(), anyLong(), any(), anyInt(), any(), any(), any());
    }

    @Test
    void endedEventCarriesStableReason() {
        AiRealtimeEventApplier.Outcome outcome = applier.apply(
                session(0L),
                List.of(new RealtimeEvent.Ended(0L, RealtimeCloseReason.ADAPTER_FAILED, "UPSTREAM_ERROR")));

        assertThat(outcome.closeReason()).isEqualTo(RealtimeCloseReason.ADAPTER_FAILED);
        assertThat(outcome.detailCode()).isEqualTo("UPSTREAM_ERROR");
    }

    @Test
    void toolCallIsRegisteredAsDataOnly() {
        applier.apply(
                session(0L),
                List.of(new RealtimeEvent.ToolCall(0L, "call_1", "lookup-order", Map.of("orderNo", "A1"))));

        verify(toolCallMapper)
                .insertDeduped(
                        eq(SESSION_ID),
                        eq(0L),
                        eq("call_1"),
                        eq("lookup-order"),
                        eq(null),
                        eq(""),
                        any(),
                        any(),
                        eq(AiRealtimeToolCallDO.STATUS_PROPOSED),
                        eq(null),
                        any());
        verify(eventMapper)
                .insertDeduped(
                        eq(SESSION_ID),
                        eq(AiRealtimeEventDO.TYPE_TOOL_CALL),
                        eq(0L),
                        eq(0L),
                        eq("lookup-order"),
                        eq(0),
                        eq("call_1:PROPOSED"),
                        eq("tool:call_1:PROPOSED"),
                        any());
    }

    @Test
    void toolArgumentDigestIsStableAcrossCalls() {
        applier.apply(
                session(0L),
                List.of(new RealtimeEvent.ToolCall(0L, "call_d1", "lookup-order", Map.of("orderNo", "A1"))));
        applier.apply(
                session(0L),
                List.of(new RealtimeEvent.ToolCall(0L, "call_d2", "lookup-order", Map.of("orderNo", "A1"))));

        org.mockito.ArgumentCaptor<String> hashes = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(toolCallMapper, org.mockito.Mockito.times(2))
                .insertDeduped(
                        eq(SESSION_ID),
                        eq(0L),
                        org.mockito.ArgumentMatchers.startsWith("call_d"),
                        eq("lookup-order"),
                        eq(null),
                        eq(""),
                        any(),
                        hashes.capture(),
                        eq(AiRealtimeToolCallDO.STATUS_PROPOSED),
                        eq(null),
                        any());
        assertThat(hashes.getAllValues().get(0))
                .hasSize(64)
                .isEqualTo(hashes.getAllValues().get(1));
    }

    @Test
    void oversizedToolArgumentsAreRejectedWithoutTruncation() {
        applier.apply(
                session(0L),
                List.of(new RealtimeEvent.ToolCall(0L, "call_2", "lookup-order", Map.of("orderNo", "x".repeat(4200)))));

        verify(toolCallMapper)
                .insertDeduped(
                        eq(SESSION_ID),
                        eq(0L),
                        eq("call_2"),
                        eq("lookup-order"),
                        eq(null),
                        eq(""),
                        eq("{}"),
                        any(),
                        eq(AiRealtimeToolCallDO.STATUS_REJECTED),
                        eq("ARGUMENTS_TOO_LARGE"),
                        any());
    }

    @Test
    void staleAndFutureDetailCodesAreStable() {
        applier.apply(session(2L), List.of(new RealtimeEvent.ToolCall(1L, "call_3", "lookup-order", Map.of())));
        verify(eventMapper)
                .insertDeduped(
                        eq(SESSION_ID),
                        eq(AiRealtimeEventDO.TYPE_STALE_DROPPED),
                        eq(1L),
                        eq(0L),
                        eq(""),
                        eq(0),
                        eq("STALE:TOOLCALL"),
                        eq("stale:TOOLCALL:1:0"),
                        any());

        AiRealtimeEventApplier.Outcome future = applier.apply(
                session(0L), List.of(new RealtimeEvent.Ended(3L, RealtimeCloseReason.ADAPTER_ENDED, "bye")));
        assertThat(future.detailCode()).isEqualTo("TURN_FUTURE");
    }

    private static AiRealtimeSessionDO session(long turnNo) {
        return new AiRealtimeSessionDO()
                .setId(SESSION_ID)
                .setTurnNo(turnNo)
                .setDroppedStaleFrames(0)
                .setStatus(AiRealtimeSessionDO.STATUS_OPEN);
    }
}

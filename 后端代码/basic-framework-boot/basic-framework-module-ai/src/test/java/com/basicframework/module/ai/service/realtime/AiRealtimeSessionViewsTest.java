package com.basicframework.module.ai.service.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.basicframework.module.ai.dal.dataobject.realtime.AiRealtimeEventDO;
import com.basicframework.module.ai.dal.dataobject.realtime.AiRealtimeSessionDO;
import com.basicframework.module.ai.dal.dataobject.realtime.AiRealtimeToolCallDO;
import com.basicframework.module.ai.dal.mysql.realtime.AiRealtimeEventMapper;
import com.basicframework.module.ai.dal.mysql.realtime.AiRealtimeToolCallMapper;
import com.basicframework.module.ai.service.realtime.dto.AiRealtimeSessionViewDTO;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 会话视图组装（X05 单测）：倒序取最近事件/工具调用后反转成发生顺序，字段与稳定码原样透出。
 */
@ExtendWith(MockitoExtension.class)
class AiRealtimeSessionViewsTest {

    private static final Long SESSION_ID = 42L;

    @Mock
    private AiRealtimeEventMapper eventMapper;

    @Mock
    private AiRealtimeToolCallMapper toolCallMapper;

    @InjectMocks
    private AiRealtimeSessionViews views;

    @Test
    void eventsAndToolCallsAreReturnedInOccurrenceOrder() {
        when(eventMapper.selectRecentBySession(SESSION_ID, AiRealtimeSessionViews.MAX_EVENTS))
                .thenReturn(List.of(event(3L, "CLOSED"), event(2L, "AUDIO"), event(1L, "TRANSCRIPT")));
        when(toolCallMapper.selectRecentBySession(SESSION_ID, AiRealtimeSessionViews.MAX_TOOL_CALLS))
                .thenReturn(List.of(call(9L, "EXECUTED"), call(8L, "PROPOSED")));

        AiRealtimeSessionViewDTO view = views.toView(session(), "ticket-plain");

        assertThat(view.getTicket()).isEqualTo("ticket-plain");
        assertThat(view.getEvents()).extracting("type").containsExactly("TRANSCRIPT", "AUDIO", "CLOSED");
        assertThat(view.getEvents().get(0).getText()).isEqualTo("文本-1");
        assertThat(view.getToolCalls()).extracting("status").containsExactly("PROPOSED", "EXECUTED");
        assertThat(view.getToolCalls().get(0).getCallId()).isEqualTo("call-8");
    }

    @Test
    void nullSessionYieldsNullViewAndTicketStaysNullByDefault() {
        assertThat(views.toView(null)).isNull();

        when(eventMapper.selectRecentBySession(SESSION_ID, AiRealtimeSessionViews.MAX_EVENTS))
                .thenReturn(List.of());
        when(toolCallMapper.selectRecentBySession(SESSION_ID, AiRealtimeSessionViews.MAX_TOOL_CALLS))
                .thenReturn(List.of());

        AiRealtimeSessionViewDTO view = views.toView(session());

        assertThat(view.getTicket()).isNull();
        assertThat(view.getEvents()).isEmpty();
        assertThat(view.getToolCalls()).isEmpty();
        assertThat(view.getInputBufferedBytes()).isEqualTo(1280L);
        assertThat(view.getDroppedStaleFrames()).isEqualTo(2);
    }

    private static AiRealtimeSessionDO session() {
        return new AiRealtimeSessionDO()
                .setId(SESSION_ID)
                .setSessionKey("rts_test")
                .setStatus(AiRealtimeSessionDO.STATUS_OPEN)
                .setEndpointId(9L)
                .setEndpointConfigRevision(3)
                .setProtocol("WEBSOCKET")
                .setAudioFormat("audio/pcm@16000:1:20")
                .setTurnNo(1L)
                .setDroppedStaleFrames(2)
                .setMuted(false)
                .setInputCapacityBytes(4096L)
                .setBufferedBytes(1280L)
                .setInputBytesTotal(2560L)
                .setResumeAttempts(0)
                .setTicketRevision(1)
                .setExpiresTime(LocalDateTime.now().plusMinutes(5));
    }

    private static AiRealtimeEventDO event(long id, String type) {
        AiRealtimeEventDO event = new AiRealtimeEventDO()
                .setId(id)
                .setSessionId(SESSION_ID)
                .setEventType(type)
                .setTurnNo(0L)
                .setEventSeq(id)
                .setTextContent("文本-" + id)
                .setByteCount(0)
                .setDetailCode("detail");
        event.setCreateTime(LocalDateTime.now());
        return event;
    }

    private static AiRealtimeToolCallDO call(long id, String status) {
        return new AiRealtimeToolCallDO()
                .setId(id)
                .setSessionId(SESSION_ID)
                .setTurnNo(0L)
                .setCallId("call-" + id)
                .setToolCode("lookup-order")
                .setStatus(status)
                .setResultCode("COMPLETE");
    }
}

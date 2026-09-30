package com.basicframework.module.ai.service.realtime;

import com.basicframework.module.ai.dal.dataobject.realtime.AiRealtimeEventDO;
import com.basicframework.module.ai.dal.dataobject.realtime.AiRealtimeSessionDO;
import com.basicframework.module.ai.dal.dataobject.realtime.AiRealtimeToolCallDO;
import com.basicframework.module.ai.dal.mysql.realtime.AiRealtimeEventMapper;
import com.basicframework.module.ai.dal.mysql.realtime.AiRealtimeToolCallMapper;
import com.basicframework.module.ai.service.realtime.dto.AiRealtimeEventViewDTO;
import com.basicframework.module.ai.service.realtime.dto.AiRealtimeSessionViewDTO;
import com.basicframework.module.ai.service.realtime.dto.AiRealtimeToolCallViewDTO;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 会话视图组装（X05）：把会话行 + 最近事件 + 工具状态组装成**统一响应形状**。
 *
 * <p>有界：事件只返回最近 {@value #MAX_EVENTS} 条、工具调用只返回最近 {@value #MAX_TOOL_CALLS} 条
 * （倒序取再反转，保持发生顺序）。会话很长时响应不会被事件量拖垮；重连需要的是"最近状态"，
 * 历史全文由审计链路按保留策略处理。
 *
 * <p>票据明文只由调用方显式传入（受理与续票各一次），其余路径一律为 null。
 */
@Component
@RequiredArgsConstructor
public class AiRealtimeSessionViews {

    /** 单次响应返回的最近事件条数上限。 */
    public static final int MAX_EVENTS = 200;

    /** 单次响应返回的最近工具调用条数上限。 */
    public static final int MAX_TOOL_CALLS = 50;

    private final AiRealtimeEventMapper eventMapper;

    private final AiRealtimeToolCallMapper toolCallMapper;

    /** 组装视图（不含票据明文）。 */
    public AiRealtimeSessionViewDTO toView(AiRealtimeSessionDO session) {
        return toView(session, null);
    }

    /** 组装视图；{@code ticket} 只在受理/续票路径非空（明文只出现一次）。 */
    public AiRealtimeSessionViewDTO toView(AiRealtimeSessionDO session, String ticket) {
        if (session == null) {
            return null;
        }
        return new AiRealtimeSessionViewDTO()
                .setId(session.getId())
                .setSessionKey(session.getSessionKey())
                .setStatus(session.getStatus())
                .setCloseReason(session.getCloseReason())
                .setEndpointId(session.getEndpointId())
                .setEndpointConfigRevision(session.getEndpointConfigRevision())
                .setProtocol(session.getProtocol())
                .setAudioFormat(session.getAudioFormat())
                .setTurnNo(session.getTurnNo())
                .setDroppedStaleFrames(session.getDroppedStaleFrames())
                .setMuted(session.getMuted())
                .setInputCapacityBytes(session.getInputCapacityBytes())
                .setInputBufferedBytes(session.getBufferedBytes())
                .setInputBytesTotal(session.getInputBytesTotal())
                .setResumeAttempts(session.getResumeAttempts())
                .setResumeDeadline(session.getResumeDeadline())
                .setExpiresTime(session.getExpiresTime())
                .setTicketRevision(session.getTicketRevision())
                .setTicketExpiresTime(session.getTicketExpiresTime())
                .setTicket(ticket)
                .setEvents(recentEvents(session.getId()))
                .setToolCalls(recentToolCalls(session.getId()));
    }

    /** 最近事件（升序；有界）。 */
    private List<AiRealtimeEventViewDTO> recentEvents(Long sessionId) {
        List<AiRealtimeEventDO> recent = eventMapper.selectRecentBySession(sessionId, MAX_EVENTS);
        List<AiRealtimeEventViewDTO> views = new ArrayList<>(recent.size());
        for (int index = recent.size() - 1; index >= 0; index--) {
            AiRealtimeEventDO event = recent.get(index);
            views.add(new AiRealtimeEventViewDTO()
                    .setType(event.getEventType())
                    .setTurnNo(event.getTurnNo())
                    .setSeq(event.getEventSeq())
                    .setText(event.getTextContent())
                    .setByteCount(event.getByteCount())
                    .setDetailCode(event.getDetailCode())
                    .setCreateTime(event.getCreateTime()));
        }
        return views;
    }

    /** 最近工具调用（升序；有界）。 */
    private List<AiRealtimeToolCallViewDTO> recentToolCalls(Long sessionId) {
        List<AiRealtimeToolCallDO> recent = toolCallMapper.selectRecentBySession(sessionId, MAX_TOOL_CALLS);
        List<AiRealtimeToolCallViewDTO> views = new ArrayList<>(recent.size());
        for (int index = recent.size() - 1; index >= 0; index--) {
            AiRealtimeToolCallDO call = recent.get(index);
            views.add(new AiRealtimeToolCallViewDTO()
                    .setId(call.getId())
                    .setTurnNo(call.getTurnNo())
                    .setCallId(call.getCallId())
                    .setToolCode(call.getToolCode())
                    .setStatus(call.getStatus())
                    .setResultCode(call.getResultCode())
                    .setExecutedTime(call.getExecutedTime()));
        }
        return views;
    }
}

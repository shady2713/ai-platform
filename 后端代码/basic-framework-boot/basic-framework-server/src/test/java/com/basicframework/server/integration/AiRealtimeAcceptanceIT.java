package com.basicframework.server.integration;

import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_REALTIME_BACKPRESSURE_CONFLICT;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_REALTIME_PROTOCOL_UNVERIFIED_CONFLICT;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_REALTIME_SESSION_LIMIT_EXCEEDED;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_REALTIME_SESSION_NOT_EXISTS;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_REALTIME_TICKET_INVALID;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_REALTIME_TOOL_POLICY_DENIED;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_REALTIME_TURN_STALE_CONFLICT;
import static org.assertj.core.api.Assertions.assertThat;

import com.basicframework.framework.ai.core.realtime.RealtimeCloseReason;
import com.basicframework.framework.ai.core.realtime.RealtimeEvent;
import com.basicframework.module.ai.dal.dataobject.realtime.AiRealtimeSessionDO;
import com.basicframework.module.ai.service.realtime.dto.AiRealtimeSessionViewDTO;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;

/**
 * 实时语音会话验收（X05 集成测试，真实 MySQL/Redis + 本地协议替身）：
 * 走通"受理 → 推流 → 打断 → 重连 → 关闭"，并断言背压、旧回合丢弃、重连不重复执行工具、
 * 到期/切用户关闭与并发上限。夹具见 {@link AiRealtimeAcceptanceSupport}。
 */
@Import(AiRealtimeAcceptanceSupport.RealtimeFixtureConfiguration.class)
class AiRealtimeAcceptanceIT extends AiRealtimeAcceptanceSupport {

    @Test
    void fullSessionFlowKeepsBargeInFenceAndNeverReExecutesTools() {
        AiRealtimeSessionViewDTO accepted = sessionService.accept(acceptRequest("rt_accept_0001"));
        assertThat(accepted.getStatus()).isEqualTo(AiRealtimeSessionDO.STATUS_OPEN);
        assertThat(accepted.getTicket()).isNotBlank();
        assertThat(accepted.getProtocol()).isEqualTo("WEBSOCKET");
        assertThat(accepted.getAudioFormat()).isEqualTo(AUDIO_FORMAT);
        assertThat(accepted.getEndpointId()).isEqualTo(endpointId);
        assertThat(accepted.getEndpointConfigRevision()).isPositive();
        assertThat(accepted.getInputCapacityBytes()).isEqualTo(4L * 1024 * 1024);
        assertThat(accepted.getEvents()).isEmpty();
        assertThat(protocolDouble.probeCalls()).isEqualTo(1);
        assertThat(protocolDouble.openCalls()).isEqualTo(1);
        // 验证台账落库：结论与配置版本一一对应（重探只发生在结论过期或版本变化时）
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT status FROM ai_realtime_endpoint_capability WHERE endpoint_id = ?"
                                + " AND config_revision = ? AND protocol = 'WEBSOCKET'",
                        String.class,
                        endpointId,
                        accepted.getEndpointConfigRevision()))
                .isEqualTo("VERIFIED");
        assertThat(sessionService.accept(acceptRequest("rt_accept_0001")).getTicket())
                .as("幂等重放不重发票据，也不重新开通道")
                .isNull();
        assertThat(protocolDouble.openCalls()).isEqualTo(1);

        AiRealtimeProtocolDouble.LocalChannel channel = protocolDouble.channelOf(accepted.getSessionKey());
        assertThat(channel).isNotNull();
        channel.emitOnNextPush(new RealtimeEvent.Transcript(0L, 1L, false, "帮我查一下订单"));
        channel.emitOnNextPush(new RealtimeEvent.Transcript(0L, 2L, true, "帮我查一下订单 A1"));

        AiRealtimeSessionViewDTO afterFirstTurn =
                sessionService.pushAudio(accepted.getId(), frame(0L, 0L, AUDIO_FORMAT));
        assertThat(afterFirstTurn.getEvents()).extracting("type").contains("TRANSCRIPT");
        assertThat(afterFirstTurn.getInputBytesTotal()).isEqualTo(640L);
        assertThat(afterFirstTurn.getInputBufferedBytes())
                .as("适配器已消费帧：有界缓冲不残留占用")
                .isZero();

        // 工具调用：模型提出 → 平台登记为数据（PROPOSED），执行走受控入口
        channel.emitOnNextPush(
                AiRealtimeProtocolDouble.LocalChannel.toolCall(0L, "call_1", TOOL_CODE, Map.of("payment_no", "RT-1")));
        AiRealtimeSessionViewDTO withToolCall = sessionService.pushAudio(accepted.getId(), frame(0L, 1L, AUDIO_FORMAT));
        assertThat(withToolCall.getToolCalls()).hasSize(1);
        Long toolCallId = withToolCall.getToolCalls().get(0).getId();
        assertThat(withToolCall.getToolCalls().get(0).getStatus()).isEqualTo("PROPOSED");

        AiRealtimeSessionViewDTO executed = sessionService.executeToolCall(accepted.getId(), toolCallId);
        assertThat(executed.getToolCalls().get(0).getStatus()).isEqualTo("EXECUTED");
        assertThat(simulator.reconcileCalls()).as("工具真的走了受控出站链路").isEqualTo(1);

        // 需要确认的工具在会话内必须被拒绝（写/需确认工具只能走运行与动作流程）
        channel.emitOnNextPush(AiRealtimeProtocolDouble.LocalChannel.toolCall(
                0L, "call_2", CONFIRM_TOOL_CODE, Map.of("payment_no", "RT-2")));
        AiRealtimeSessionViewDTO withConfirmCall =
                sessionService.pushAudio(accepted.getId(), frame(0L, 2L, AUDIO_FORMAT));
        Long confirmCallId = withConfirmCall.getToolCalls().stream()
                .filter(call -> CONFIRM_TOOL_CODE.equals(call.getToolCode()))
                .findFirst()
                .orElseThrow()
                .getId();
        assertServiceException(
                AI_REALTIME_TOOL_POLICY_DENIED.getCode(),
                () -> sessionService.executeToolCall(accepted.getId(), confirmCallId));
        assertThat(jdbcTemplate.queryForMap(
                        "SELECT status, result_code FROM ai_realtime_tool_call WHERE id = ?", confirmCallId))
                .containsEntry("status", "REJECTED")
                .containsEntry("result_code", "AI_TOOL_CONFIRMATION_REQUIRED");
        assertThat(simulator.reconcileCalls()).as("被拒绝的调用没有出站").isEqualTo(1);

        // 打断：回合 +1，旧回合仍在飞的音频被丢弃并计数
        AiRealtimeSessionViewDTO interrupted = sessionService.interrupt(accepted.getId(), 0L);
        assertThat(interrupted.getTurnNo()).isEqualTo(1L);
        assertThat(channel.interrupts()).containsExactly(0L);
        channel.emitOnNextPush(AiRealtimeProtocolDouble.LocalChannel.audio(0L, 9L, 320));
        AiRealtimeSessionViewDTO afterLateFrame =
                sessionService.pushAudio(accepted.getId(), frame(1L, 0L, AUDIO_FORMAT));
        assertThat(afterLateFrame.getDroppedStaleFrames()).isEqualTo(1);
        assertThat(afterLateFrame.getEvents())
                .as("晚到的旧回合音频不进事件面")
                .noneMatch(event ->
                        "AUDIO".equals(event.getType()) && Long.valueOf(0L).equals(event.getTurnNo()));
        assertThat(afterLateFrame.getEvents())
                .anyMatch(event ->
                        "STALE_DROPPED".equals(event.getType()) && "STALE:AUDIO".equals(event.getDetailCode()));
        assertServiceException(
                AI_REALTIME_TURN_STALE_CONFLICT.getCode(),
                () -> sessionService.pushAudio(accepted.getId(), frame(0L, 2L, AUDIO_FORMAT)));

        // 续票：新票据替换旧票据（代次 +1），会话寿命不延长，旧票据立即失效
        AiRealtimeSessionViewDTO renewed = sessionService.renewTicket(accepted.getId(), accepted.getTicket());
        assertThat(renewed.getTicket()).isNotBlank().isNotEqualTo(accepted.getTicket());
        assertThat(renewed.getTicketRevision()).isEqualTo(2);
        assertThat(renewed.getTicketExpiresTime()).isBeforeOrEqualTo(renewed.getExpiresTime());
        assertServiceException(
                AI_REALTIME_TICKET_INVALID.getCode(),
                () -> sessionService.resume(accepted.getId(), accepted.getTicket()));

        // 断线重连：转写与工具状态恢复，工具不重复执行
        AiRealtimeSessionViewDTO detached = sessionService.detach(accepted.getId());
        assertThat(detached.getStatus()).isEqualTo(AiRealtimeSessionDO.STATUS_DETACHED);
        AiRealtimeSessionViewDTO resumed = sessionService.resume(accepted.getId(), renewed.getTicket());
        assertThat(resumed.getStatus()).isEqualTo(AiRealtimeSessionDO.STATUS_OPEN);
        assertThat(resumed.getResumeAttempts()).isEqualTo(1);
        assertThat(resumed.getEvents()).extracting("type").contains("REATTACHED", "TRANSCRIPT");
        AiRealtimeSessionViewDTO replayed = sessionService.executeToolCall(accepted.getId(), toolCallId);
        assertThat(replayed.getToolCalls().get(0).getStatus()).isEqualTo("EXECUTED");
        assertThat(simulator.reconcileCalls()).as("重连后重发执行请求不产生第二次副作用").isEqualTo(1);

        // 关闭与销毁：幂等，且通道收到关闭原因
        AiRealtimeSessionViewDTO closed = sessionService.close(accepted.getId());
        assertThat(closed.getStatus()).isEqualTo(AiRealtimeSessionDO.STATUS_CLOSED);
        assertThat(closed.getCloseReason()).isEqualTo("client-closed");
        assertThat(sessionService.close(accepted.getId()).getStatus()).isEqualTo(AiRealtimeSessionDO.STATUS_CLOSED);
        assertThat(channel.closeReasons()).contains(RealtimeCloseReason.CLIENT_CLOSED);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM ai_realtime_event WHERE session_id = ? AND event_type = 'CLOSED'",
                        Integer.class,
                        accepted.getId()))
                .as("关闭只留一条留痕（幂等）")
                .isEqualTo(1);
    }

    @Test
    void backpressureEndsSessionWithStableReasonAndKeepsTranscript() {
        String format = "audio/ogg@16000:1:20";
        AiRealtimeSessionViewDTO accepted =
                sessionService.accept(acceptRequest("rt_accept_0002").setAudioFormat(format));
        AiRealtimeProtocolDouble.LocalChannel channel = protocolDouble.channelOf(accepted.getSessionKey());
        assertThat(channel).isNotNull();
        channel.emitOnNextPush(new RealtimeEvent.Transcript(0L, 1L, true, "背压前已经说过的内容"));
        sessionService.pushAudio(accepted.getId(), frame(0L, 0L, format));
        channel.queueFrames(true);

        int capacity = (int) com.basicframework.module.ai.service.realtime.AiRealtimeParams.INPUT_CAPACITY_BYTES;
        int frameBytes = 64 * 1024;
        int acceptedFrames = 0;
        for (int index = 1; index <= capacity / frameBytes; index++) {
            sessionService.pushAudio(accepted.getId(), frame(0L, index, format));
            acceptedFrames++;
        }
        assertThat(acceptedFrames).isEqualTo(64);
        assertServiceException(
                AI_REALTIME_BACKPRESSURE_CONFLICT.getCode(),
                () -> sessionService.pushAudio(accepted.getId(), frame(0L, 999L, format)));

        AiRealtimeSessionViewDTO after = sessionService.getSession(accepted.getId());
        assertThat(after.getStatus()).isEqualTo(AiRealtimeSessionDO.STATUS_CLOSED);
        assertThat(after.getCloseReason()).isEqualTo("audio-backpressure-exceeded");
        assertThat(after.getEvents())
                .as("失败降级不丢文字：转写仍在事件面")
                .anyMatch(event -> "TRANSCRIPT".equals(event.getType()) && "背压前已经说过的内容".equals(event.getText()));
        assertThat(after.getEvents())
                .anyMatch(event -> "CLOSED".equals(event.getType())
                        && event.getDetailCode() != null
                        && event.getDetailCode().startsWith("audio-backpressure-exceeded"));
    }

    @Test
    void expiryAndIdentitySwitchCloseTheSession() {
        AiRealtimeSessionViewDTO accepted = sessionService.accept(acceptRequest("rt_accept_0003"));
        jdbcTemplate.update(
                "UPDATE ai_realtime_session SET expires_time = ? WHERE id = ?",
                java.time.LocalDateTime.now().minusSeconds(5),
                accepted.getId());

        AiRealtimeSessionViewDTO expired = sessionService.getSession(accepted.getId());
        assertThat(expired.getStatus()).isEqualTo(AiRealtimeSessionDO.STATUS_CLOSED);
        assertThat(expired.getCloseReason()).isEqualTo("session-expired");

        // 切用户：另一个主体出示同一票据（唯一可观测的"切用户"信号）→ 关闭会话且对外与不存在同语义
        AiRealtimeSessionViewDTO second = sessionService.accept(acceptRequest("rt_accept_0004"));
        loginAs(USER_B);
        assertServiceException(
                AI_REALTIME_SESSION_NOT_EXISTS.getCode(),
                () -> sessionService.resume(second.getId(), second.getTicket()));
        Map<String, Object> closedRow = jdbcTemplate.queryForMap(
                "SELECT status, close_reason FROM ai_realtime_session WHERE id = ?", second.getId());
        assertThat(closedRow.get("status")).isEqualTo("CLOSED");
        assertThat(closedRow.get("close_reason")).isEqualTo("identity-switched");

        // 只带登录会话的越权访问按不存在拒绝，且**不**关闭会话（不能靠猜编号终止他人会话）
        loginAs(USER_A);
        AiRealtimeSessionViewDTO third = sessionService.accept(acceptRequest("rt_accept_0005"));
        loginAs(USER_B);
        assertServiceException(
                AI_REALTIME_SESSION_NOT_EXISTS.getCode(), () -> sessionService.getSession(third.getId()));
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT status FROM ai_realtime_session WHERE id = ?", String.class, third.getId()))
                .isEqualTo("OPEN");
    }

    @Test
    void unverifiedProtocolIsRejectedAndConcurrentSessionsAreBounded() {
        protocolDouble.probeFails(true);
        assertServiceException(
                AI_REALTIME_PROTOCOL_UNVERIFIED_CONFLICT.getCode(),
                () -> sessionService.accept(acceptRequest("rt_accept_0006")));
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM ai_realtime_session WHERE request_key = 'rt_accept_0006'", Integer.class))
                .as("未通过验证就没有会话行（也不发生任何通道打开）")
                .isZero();
        protocolDouble.probeFails(false);
        // 失败结论在冷却窗口内不会重复外发（设计语义）：这里清除失败结论以模拟冷却窗口已过
        capabilityMapper.delete(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<
                        com.basicframework.module.ai.dal.dataobject.realtime.AiRealtimeEndpointCapabilityDO>()
                .eq(
                        com.basicframework.module.ai.dal.dataobject.realtime.AiRealtimeEndpointCapabilityDO
                                ::getEndpointId,
                        endpointId));

        AiRealtimeSessionViewDTO first = sessionService.accept(acceptRequest("rt_accept_0007"));
        AiRealtimeSessionViewDTO second = sessionService.accept(acceptRequest("rt_accept_0008"));
        assertServiceException(
                AI_REALTIME_SESSION_LIMIT_EXCEEDED.getCode(),
                () -> sessionService.accept(acceptRequest("rt_accept_0009")));
        sessionService.close(first.getId());
        AiRealtimeSessionViewDTO afterClose = sessionService.accept(acceptRequest("rt_accept_0010"));
        assertThat(afterClose.getStatus()).isEqualTo(AiRealtimeSessionDO.STATUS_OPEN);
        sessionService.close(second.getId());
        sessionService.close(afterClose.getId());
    }
}

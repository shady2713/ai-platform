package com.basicframework.module.ai.service.realtime;

import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_REQUEST_INVALID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basicframework.framework.ai.core.realtime.RealtimeProtocol;
import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.module.ai.controller.app.v1.realtime.AiRealtimeController;
import com.basicframework.module.ai.controller.app.v1.realtime.vo.AiRealtimeAudioPushReqVO;
import com.basicframework.module.ai.controller.app.v1.realtime.vo.AiRealtimeInterruptReqVO;
import com.basicframework.module.ai.controller.app.v1.realtime.vo.AiRealtimeMuteReqVO;
import com.basicframework.module.ai.controller.app.v1.realtime.vo.AiRealtimeSessionAcceptReqVO;
import com.basicframework.module.ai.controller.app.v1.realtime.vo.AiRealtimeSessionIdReqVO;
import com.basicframework.module.ai.controller.app.v1.realtime.vo.AiRealtimeSessionRespVO;
import com.basicframework.module.ai.controller.app.v1.realtime.vo.AiRealtimeTicketReqVO;
import com.basicframework.module.ai.controller.app.v1.realtime.vo.AiRealtimeToolExecuteReqVO;
import com.basicframework.module.ai.service.realtime.dto.AiRealtimeAcceptDTO;
import com.basicframework.module.ai.service.realtime.dto.AiRealtimeAudioPushDTO;
import com.basicframework.module.ai.service.realtime.dto.AiRealtimeEventViewDTO;
import com.basicframework.module.ai.service.realtime.dto.AiRealtimeSessionViewDTO;
import com.basicframework.module.ai.service.realtime.dto.AiRealtimeToolCallViewDTO;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 实时语音控制器（X05 单测）：协议层只做形状映射（VO ↔ DTO）与 Base64 解码，
 * 语义判定全部在服务层（由服务单测与集成测试覆盖）。
 */
@ExtendWith(MockitoExtension.class)
class AiRealtimeControllerTest {

    @Mock
    private AiRealtimeSessionService sessionService;

    @InjectMocks
    private AiRealtimeController controller;

    @Test
    void acceptMapsRequestShape() {
        when(sessionService.accept(any())).thenReturn(view());

        AiRealtimeSessionRespVO response = controller
                .accept(new AiRealtimeSessionAcceptReqVO()
                        .setRequestKey("rt_request_0001")
                        .setEndpointId(9L)
                        .setProtocol("WEBSOCKET")
                        .setAudioFormat("audio/pcm@16000:1:20")
                        .setSessionSeconds(120))
                .getData();

        ArgumentCaptor<AiRealtimeAcceptDTO> captor = ArgumentCaptor.forClass(AiRealtimeAcceptDTO.class);
        verify(sessionService).accept(captor.capture());
        assertThat(captor.getValue().getRequestKey()).isEqualTo("rt_request_0001");
        assertThat(captor.getValue().getEndpointId()).isEqualTo(9L);
        assertThat(captor.getValue().getSessionSeconds()).isEqualTo(120);
        assertThat(response.getId()).isEqualTo(42L);
        assertThat(response.getEvents()).hasSize(1);
        assertThat(response.getEvents().get(0).getText()).isEqualTo("你好");
        assertThat(response.getToolCalls()).hasSize(1);
        assertThat(response.getToolCalls().get(0).getStatus()).isEqualTo("PROPOSED");
        assertThat(response.getTicket()).isEqualTo("ticket-plain");
    }

    @Test
    void acceptReqVoDefaultsAreForwardedAsNull() {
        when(sessionService.accept(any())).thenReturn(view());

        controller.accept(new AiRealtimeSessionAcceptReqVO()
                .setRequestKey("rt_request_0002")
                .setEndpointId(9L)
                .setProtocol("WEBSOCKET")
                .setAudioFormat("audio/pcm@16000:1:20"));

        ArgumentCaptor<AiRealtimeAcceptDTO> captor = ArgumentCaptor.forClass(AiRealtimeAcceptDTO.class);
        verify(sessionService).accept(captor.capture());
        assertThat(captor.getValue().getSessionSeconds()).isNull();
        assertThat(RealtimeProtocol.parse(captor.getValue().getProtocol())).contains(RealtimeProtocol.WEBSOCKET);
    }

    @Test
    void audioPushDecodesBase64Payload() {
        when(sessionService.pushAudio(eq(42L), any())).thenReturn(view());

        controller.pushAudio(new AiRealtimeAudioPushReqVO()
                .setSessionId(42L)
                .setTurnNo(0L)
                .setFrameSeq(3L)
                .setPayload(Base64.getEncoder().encodeToString(new byte[640])));

        ArgumentCaptor<AiRealtimeAudioPushDTO> captor = ArgumentCaptor.forClass(AiRealtimeAudioPushDTO.class);
        verify(sessionService).pushAudio(eq(42L), captor.capture());
        assertThat(captor.getValue().getTurnNo()).isZero();
        assertThat(captor.getValue().getFrameSeq()).isEqualTo(3L);
        assertThat(captor.getValue().getPayload()).hasSize(640);
    }

    @Test
    void audioPushRejectsInvalidBase64() {
        assertThatThrownBy(() -> controller.pushAudio(new AiRealtimeAudioPushReqVO()
                        .setSessionId(42L)
                        .setTurnNo(0L)
                        .setFrameSeq(0L)
                        .setPayload("not-base64!!")))
                .isInstanceOfSatisfying(ServiceException.class, exception -> assertThat(exception.getCode())
                        .isEqualTo(AI_REQUEST_INVALID.getCode()));
    }

    @Test
    void sessionOperationsDelegateWithSessionId() {
        when(sessionService.getSession(42L)).thenReturn(view());
        when(sessionService.interrupt(42L, 1L)).thenReturn(view());
        when(sessionService.updateMuted(42L, true)).thenReturn(view());
        when(sessionService.detach(42L)).thenReturn(view());
        when(sessionService.resume(42L, "ticket-plain")).thenReturn(view());
        when(sessionService.renewTicket(42L, "ticket-plain")).thenReturn(view());
        when(sessionService.close(42L)).thenReturn(view());
        when(sessionService.executeToolCall(42L, 9L)).thenReturn(view());

        assertThat(controller.getSession(42L).getData().getId()).isEqualTo(42L);
        assertThat(controller
                        .interrupt(
                                new AiRealtimeInterruptReqVO().setSessionId(42L).setTurnNo(1L))
                        .getData()
                        .getId())
                .isEqualTo(42L);
        assertThat(controller
                        .mute(new AiRealtimeMuteReqVO().setSessionId(42L).setMuted(true))
                        .getData()
                        .getMuted())
                .isTrue();
        assertThat(controller.detach(AiRealtimeSessionIdReqVO.of(42L)).getData().getId())
                .isEqualTo(42L);
        assertThat(controller
                        .resume(new AiRealtimeTicketReqVO().setSessionId(42L).setTicket("ticket-plain"))
                        .getData()
                        .getId())
                .isEqualTo(42L);
        assertThat(controller
                        .renewTicket(
                                new AiRealtimeTicketReqVO().setSessionId(42L).setTicket("ticket-plain"))
                        .getData()
                        .getId())
                .isEqualTo(42L);
        assertThat(controller.close(AiRealtimeSessionIdReqVO.of(42L)).getData().getId())
                .isEqualTo(42L);
        assertThat(controller
                        .executeToolCall(new AiRealtimeToolExecuteReqVO()
                                .setSessionId(42L)
                                .setToolCallId(9L))
                        .getData()
                        .getId())
                .isEqualTo(42L);
    }

    @Test
    void emptyEventAndToolCallListsAreNormalised() {
        when(sessionService.getSession(42L)).thenReturn(new AiRealtimeSessionViewDTO().setId(42L));

        AiRealtimeSessionRespVO response = controller.getSession(42L).getData();

        assertThat(response.getEvents()).isEmpty();
        assertThat(response.getToolCalls()).isEmpty();
        assertThat(response.getTicket()).isNull();
    }

    private static AiRealtimeSessionViewDTO view() {
        return new AiRealtimeSessionViewDTO()
                .setId(42L)
                .setStatus("OPEN")
                .setMuted(true)
                .setTicket("ticket-plain")
                .setEvents(List.of(new AiRealtimeEventViewDTO()
                        .setType("TRANSCRIPT")
                        .setTurnNo(0L)
                        .setSeq(1L)
                        .setText("你好")
                        .setCreateTime(LocalDateTime.now())))
                .setToolCalls(List.of(new AiRealtimeToolCallViewDTO()
                        .setId(9L)
                        .setCallId("call_1")
                        .setToolCode("lookup-order")
                        .setStatus("PROPOSED")));
    }
}

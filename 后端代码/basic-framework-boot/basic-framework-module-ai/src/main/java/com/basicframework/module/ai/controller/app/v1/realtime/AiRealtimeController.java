package com.basicframework.module.ai.controller.app.v1.realtime;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.framework.common.pojo.CommonResult.success;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_REQUEST_INVALID;

import com.basicframework.framework.common.pojo.CommonResult;
import com.basicframework.framework.security.core.annotation.AuthenticatedOnly;
import com.basicframework.module.ai.controller.app.v1.realtime.vo.AiRealtimeAudioPushReqVO;
import com.basicframework.module.ai.controller.app.v1.realtime.vo.AiRealtimeEventRespVO;
import com.basicframework.module.ai.controller.app.v1.realtime.vo.AiRealtimeInterruptReqVO;
import com.basicframework.module.ai.controller.app.v1.realtime.vo.AiRealtimeMuteReqVO;
import com.basicframework.module.ai.controller.app.v1.realtime.vo.AiRealtimeSessionAcceptReqVO;
import com.basicframework.module.ai.controller.app.v1.realtime.vo.AiRealtimeSessionIdReqVO;
import com.basicframework.module.ai.controller.app.v1.realtime.vo.AiRealtimeSessionRespVO;
import com.basicframework.module.ai.controller.app.v1.realtime.vo.AiRealtimeTicketReqVO;
import com.basicframework.module.ai.controller.app.v1.realtime.vo.AiRealtimeToolCallRespVO;
import com.basicframework.module.ai.controller.app.v1.realtime.vo.AiRealtimeToolExecuteReqVO;
import com.basicframework.module.ai.service.realtime.AiRealtimeSessionService;
import com.basicframework.module.ai.service.realtime.dto.AiRealtimeAcceptDTO;
import com.basicframework.module.ai.service.realtime.dto.AiRealtimeAudioPushDTO;
import com.basicframework.module.ai.service.realtime.dto.AiRealtimeEventViewDTO;
import com.basicframework.module.ai.service.realtime.dto.AiRealtimeSessionViewDTO;
import com.basicframework.module.ai.service.realtime.dto.AiRealtimeToolCallViewDTO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.Base64;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI 应用端实时语音会话（X05，FR-37）：受理、推流、打断、关麦、断线重连、续票、关闭与工具执行。
 *
 * <p>全部端点只要求登录（{@code @AuthenticatedOnly}）：归属（应用 + 主体类型 + 外部用户标识）、
 * 协议与端点能力验证、背压、回合栅栏、重连预算与工具政策全部由服务端判定；
 * 越权与不存在同语义（404）。协议里没有上游地址、音频内容与任何凭据：
 * 票据明文只在受理/续票响应出现一次，事件里只有字节数与稳定码。
 *
 * <p>本卡不提供真实供应商适配器（ADR 0052 的未验证项）：没有注册适配器的协议，
 * 受理按 {@code AI_REALTIME_ADAPTER_UNAVAILABLE_CONFLICT} 拒绝，不做任何"猜测可用"的回退。
 */
@Tag(name = "AI 应用端 - 实时语音会话")
@RestController
@RequestMapping("/ai/realtime/session")
@Validated
@RequiredArgsConstructor
public class AiRealtimeController {

    private final AiRealtimeSessionService sessionService;

    @PostMapping
    @Operation(summary = "受理实时会话（固定端点/配置版本/协议/音频格式与短期票据；幂等键去重）")
    @AuthenticatedOnly
    public CommonResult<AiRealtimeSessionRespVO> accept(@Valid @RequestBody AiRealtimeSessionAcceptReqVO reqVO) {
        return success(toRespVO(sessionService.accept(new AiRealtimeAcceptDTO()
                .setRequestKey(reqVO.getRequestKey())
                .setEndpointId(reqVO.getEndpointId())
                .setProtocol(reqVO.getProtocol())
                .setAudioFormat(reqVO.getAudioFormat())
                .setSessionSeconds(reqVO.getSessionSeconds()))));
    }

    @GetMapping
    @Operation(summary = "查询会话（惰性物化到期/断线超时；返回转写、工具状态与背压占用）")
    @AuthenticatedOnly
    public CommonResult<AiRealtimeSessionRespVO> getSession(@RequestParam("sessionId") Long sessionId) {
        return success(toRespVO(sessionService.getSession(sessionId)));
    }

    @PostMapping("/audio")
    @Operation(summary = "上送一帧音频（回合栅栏 + 有界缓冲；超限按稳定原因结束会话，不静默丢帧）")
    @AuthenticatedOnly
    public CommonResult<AiRealtimeSessionRespVO> pushAudio(@Valid @RequestBody AiRealtimeAudioPushReqVO reqVO) {
        return success(toRespVO(sessionService.pushAudio(
                reqVO.getSessionId(),
                new AiRealtimeAudioPushDTO()
                        .setTurnNo(reqVO.getTurnNo())
                        .setFrameSeq(reqVO.getFrameSeq())
                        .setPayload(decodePayload(reqVO.getPayload())))));
    }

    @PostMapping("/interrupt")
    @Operation(summary = "打断（回合 +1；晚到的旧回合帧与事件被丢弃并计数）")
    @AuthenticatedOnly
    public CommonResult<AiRealtimeSessionRespVO> interrupt(@Valid @RequestBody AiRealtimeInterruptReqVO reqVO) {
        return success(toRespVO(sessionService.interrupt(reqVO.getSessionId(), reqVO.getTurnNo())));
    }

    @PostMapping("/mute")
    @Operation(summary = "关麦/开麦（关麦期间上行音频明确拒绝）")
    @AuthenticatedOnly
    public CommonResult<AiRealtimeSessionRespVO> mute(@Valid @RequestBody AiRealtimeMuteReqVO reqVO) {
        return success(toRespVO(sessionService.updateMuted(reqVO.getSessionId(), reqVO.getMuted())));
    }

    @PostMapping("/detach")
    @Operation(summary = "标记断线（开始有界重连窗口；重复调用不延长窗口）")
    @AuthenticatedOnly
    public CommonResult<AiRealtimeSessionRespVO> detach(@Valid @RequestBody AiRealtimeSessionIdReqVO reqVO) {
        return success(toRespVO(sessionService.detach(reqVO.getSessionId())));
    }

    @PostMapping("/resume")
    @Operation(summary = "重连（必须出示票据；有界次数与时限；返回既有转写与工具状态，不重复执行工具）")
    @AuthenticatedOnly
    public CommonResult<AiRealtimeSessionRespVO> resume(@Valid @RequestBody AiRealtimeTicketReqVO reqVO) {
        return success(toRespVO(sessionService.resume(reqVO.getSessionId(), reqVO.getTicket())));
    }

    @PostMapping("/ticket/renew")
    @Operation(summary = "续票（出示未过期的当前票据；新票据替换旧票据，会话寿命不延长）")
    @AuthenticatedOnly
    public CommonResult<AiRealtimeSessionRespVO> renewTicket(@Valid @RequestBody AiRealtimeTicketReqVO reqVO) {
        return success(toRespVO(sessionService.renewTicket(reqVO.getSessionId(), reqVO.getTicket())));
    }

    @PostMapping("/close")
    @Operation(summary = "关闭会话（幂等；销毁媒体面与通道）")
    @AuthenticatedOnly
    public CommonResult<AiRealtimeSessionRespVO> close(@Valid @RequestBody AiRealtimeSessionIdReqVO reqVO) {
        return success(toRespVO(sessionService.close(reqVO.getSessionId())));
    }

    @PostMapping("/tool/execute")
    @Operation(summary = "执行会话内工具调用（只执行免确认读工具；幂等：已终态返回既有结论）")
    @AuthenticatedOnly
    public CommonResult<AiRealtimeSessionRespVO> executeToolCall(@Valid @RequestBody AiRealtimeToolExecuteReqVO reqVO) {
        return success(toRespVO(sessionService.executeToolCall(reqVO.getSessionId(), reqVO.getToolCallId())));
    }

    /** Base64 音频负载解码；非法编码按入参不合规拒绝（不猜测、不截断）。 */
    private static byte[] decodePayload(String payload) {
        try {
            return Base64.getDecoder().decode(payload);
        } catch (IllegalArgumentException invalid) {
            throw exception(AI_REQUEST_INVALID);
        }
    }

    /** 服务层 DTO → 协议层 VO：只暴露平台事实（状态/计数/稳定码），不做字段改名式改写。 */
    private static AiRealtimeSessionRespVO toRespVO(AiRealtimeSessionViewDTO dto) {
        if (dto == null) {
            return null;
        }
        return new AiRealtimeSessionRespVO()
                .setId(dto.getId())
                .setSessionKey(dto.getSessionKey())
                .setStatus(dto.getStatus())
                .setCloseReason(dto.getCloseReason())
                .setEndpointId(dto.getEndpointId())
                .setEndpointConfigRevision(dto.getEndpointConfigRevision())
                .setProtocol(dto.getProtocol())
                .setAudioFormat(dto.getAudioFormat())
                .setTurnNo(dto.getTurnNo())
                .setDroppedStaleFrames(dto.getDroppedStaleFrames())
                .setMuted(dto.getMuted())
                .setInputCapacityBytes(dto.getInputCapacityBytes())
                .setInputBufferedBytes(dto.getInputBufferedBytes())
                .setInputBytesTotal(dto.getInputBytesTotal())
                .setResumeAttempts(dto.getResumeAttempts())
                .setResumeDeadline(dto.getResumeDeadline())
                .setExpiresTime(dto.getExpiresTime())
                .setTicketRevision(dto.getTicketRevision())
                .setTicketExpiresTime(dto.getTicketExpiresTime())
                .setTicket(dto.getTicket())
                .setEvents(toEventVOs(dto.getEvents()))
                .setToolCalls(toToolCallVOs(dto.getToolCalls()));
    }

    private static List<AiRealtimeEventRespVO> toEventVOs(List<AiRealtimeEventViewDTO> events) {
        if (events == null) {
            return List.of();
        }
        return events.stream()
                .map(event -> new AiRealtimeEventRespVO()
                        .setType(event.getType())
                        .setTurnNo(event.getTurnNo())
                        .setSeq(event.getSeq())
                        .setText(event.getText())
                        .setByteCount(event.getByteCount())
                        .setDetailCode(event.getDetailCode())
                        .setCreateTime(event.getCreateTime()))
                .toList();
    }

    private static List<AiRealtimeToolCallRespVO> toToolCallVOs(List<AiRealtimeToolCallViewDTO> calls) {
        if (calls == null) {
            return List.of();
        }
        return calls.stream()
                .map(call -> new AiRealtimeToolCallRespVO()
                        .setId(call.getId())
                        .setTurnNo(call.getTurnNo())
                        .setCallId(call.getCallId())
                        .setToolCode(call.getToolCode())
                        .setStatus(call.getStatus())
                        .setResultCode(call.getResultCode())
                        .setExecutedTime(call.getExecutedTime()))
                .toList();
    }
}

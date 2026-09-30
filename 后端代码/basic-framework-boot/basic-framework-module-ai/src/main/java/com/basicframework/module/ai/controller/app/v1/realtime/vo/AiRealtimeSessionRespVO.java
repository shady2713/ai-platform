package com.basicframework.module.ai.controller.app.v1.realtime.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.List;
import lombok.Data;
import lombok.ToString;
import lombok.experimental.Accessors;

/**
 * 会话视图（协议层 VO，X05）：受理、查询、推流、打断、关麦、断线、重连、续票、关闭的**统一响应形状**。
 *
 * <p>{@code ticket} 明文只在受理与续票响应出现一次（其余为 null），因此 {@code @ToString.Exclude}
 * 覆盖它；{@code events} 与 {@code toolCalls} 有界（最近若干条），供重连后重建界面与状态同步。
 */
@Schema(description = "实时会话视图")
@Data
@Accessors(chain = true)
public class AiRealtimeSessionRespVO {

    @Schema(description = "会话编号")
    private Long id;

    @Schema(description = "会话业务键（不是凭据）")
    private String sessionKey;

    @Schema(description = "状态（OPEN/DETACHED/CLOSED）")
    private String status;

    @Schema(description = "结束原因稳定码（未关闭为空）")
    private String closeReason;

    @Schema(description = "受理时固定的端点编号")
    private Long endpointId;

    @Schema(description = "受理时固定的端点配置版本")
    private Integer endpointConfigRevision;

    @Schema(description = "协议")
    private String protocol;

    @Schema(description = "音频格式规范形式")
    private String audioFormat;

    @Schema(description = "当前回合号")
    private Long turnNo;

    @Schema(description = "因回合过期被丢弃的帧/事件累计数")
    private Integer droppedStaleFrames;

    @Schema(description = "是否已关麦")
    private Boolean muted;

    @Schema(description = "输入缓冲上限（字节）")
    private Long inputCapacityBytes;

    @Schema(description = "输入缓冲当前占用（字节）")
    private Long inputBufferedBytes;

    @Schema(description = "累计接受输入字节")
    private Long inputBytesTotal;

    @Schema(description = "已用重连次数")
    private Integer resumeAttempts;

    @Schema(description = "重连时限")
    private LocalDateTime resumeDeadline;

    @Schema(description = "会话绝对到期时间")
    private LocalDateTime expiresTime;

    @Schema(description = "票据代次")
    private Integer ticketRevision;

    @Schema(description = "票据到期时间")
    private LocalDateTime ticketExpiresTime;

    @Schema(description = "票据明文（只在受理与续票响应出现一次；其余为 null）")
    @ToString.Exclude
    private String ticket;

    @Schema(description = "最近事件（升序，有界）")
    private List<AiRealtimeEventRespVO> events;

    @Schema(description = "工具调用状态（升序，有界）")
    private List<AiRealtimeToolCallRespVO> toolCalls;
}

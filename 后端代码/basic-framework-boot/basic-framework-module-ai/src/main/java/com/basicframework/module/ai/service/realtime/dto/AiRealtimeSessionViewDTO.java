package com.basicframework.module.ai.service.realtime.dto;

import java.time.LocalDateTime;
import java.util.List;
import lombok.Data;
import lombok.ToString;
import lombok.experimental.Accessors;

/**
 * 会话视图（服务层 DTO）：受理、查询、推流、打断、关麦、断线、重连、续票、关闭的**统一响应形状**。
 *
 * <p>{@code ticket} 明文只在受理与续票响应出现一次（其余调用为 null），因此
 * {@code @ToString.Exclude} 覆盖它；{@code events} 是重连后重建界面所需的最小事实
 * （有界：只返回最近的事件），{@code toolCalls} 是工具状态同步的唯一来源。
 */
@Data
@Accessors(chain = true)
public class AiRealtimeSessionViewDTO {

    /** 会话编号 */
    private Long id;

    /** 会话业务键（不是凭据） */
    private String sessionKey;

    /** 状态（OPEN/DETACHED/CLOSED） */
    private String status;

    /** 结束原因稳定码（未关闭为空） */
    private String closeReason;

    /** 受理时固定的端点编号 */
    private Long endpointId;

    /** 受理时固定的端点配置版本 */
    private Integer endpointConfigRevision;

    /** 协议 */
    private String protocol;

    /** 音频格式规范形式 */
    private String audioFormat;

    /** 当前回合号 */
    private Long turnNo;

    /** 因回合过期被丢弃的帧/事件累计数 */
    private Integer droppedStaleFrames;

    /** 是否已关麦 */
    private Boolean muted;

    /** 输入缓冲上限（字节） */
    private Long inputCapacityBytes;

    /** 输入缓冲当前占用（字节） */
    private Long inputBufferedBytes;

    /** 累计接受输入字节 */
    private Long inputBytesTotal;

    /** 已用重连次数 */
    private Integer resumeAttempts;

    /** 重连时限 */
    private LocalDateTime resumeDeadline;

    /** 会话绝对到期时间 */
    private LocalDateTime expiresTime;

    /** 票据代次 */
    private Integer ticketRevision;

    /** 票据到期时间 */
    private LocalDateTime ticketExpiresTime;

    /** 票据明文（只在受理与续票响应出现一次；其余为 null） */
    @ToString.Exclude
    private String ticket;

    /** 最近事件（升序；有界） */
    private List<AiRealtimeEventViewDTO> events;

    /** 工具调用状态（升序；有界） */
    private List<AiRealtimeToolCallViewDTO> toolCalls;
}

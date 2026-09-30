package com.basicframework.module.ai.service.realtime.dto;

import java.time.LocalDateTime;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 会话事件视图（服务层 DTO）：转写、下行音频、工具调用请求、过期丢弃、重连与关闭的统一形状。
 *
 * <p>不含音频字节与上游报文：{@code byteCount} 是计账口径，{@code detailCode} 只允许稳定码。
 */
@Data
@Accessors(chain = true)
public class AiRealtimeEventViewDTO {

    /** 事件类型（TRANSCRIPT/AUDIO/TOOL_CALL/STALE_DROPPED/REATTACHED/CLOSED） */
    private String type;

    /** 事件所属回合 */
    private Long turnNo;

    /** 回合内事件序号 */
    private Long seq;

    /** 转写文本（其他类型为空） */
    private String text;

    /** 音频字节数（仅 AUDIO 事件） */
    private Integer byteCount;

    /** 稳定明细（结束原因码/工具调用标识/丢弃原因） */
    private String detailCode;

    /** 事件时间 */
    private LocalDateTime createTime;
}

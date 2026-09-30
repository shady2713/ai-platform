package com.basicframework.module.ai.service.realtime.dto;

import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 上行音频帧（服务层 DTO）：回合号 + 帧序号 + 字节。
 *
 * <p>字节只用于当次投递与计账，**不落库**（会话事件只记字节数与序号）：平台的持久事实是
 * "谁在哪个回合送了多少字节、有没有超限"，不是音频内容本身。
 */
@Data
@Accessors(chain = true)
public class AiRealtimeAudioPushDTO {

    /** 回合号（必须等于会话当前回合：更小=已过期的旧回合音频，更大=凭空发明回合） */
    private Long turnNo;

    /** 帧序号（同一回合内递增） */
    private Long frameSeq;

    /** 音频字节 */
    private byte[] payload;
}

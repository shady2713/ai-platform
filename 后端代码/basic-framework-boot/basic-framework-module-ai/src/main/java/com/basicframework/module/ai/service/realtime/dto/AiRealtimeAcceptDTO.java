package com.basicframework.module.ai.service.realtime.dto;

import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 实时会话受理请求（服务层 DTO）：调用方能决定的只有"要哪个端点的哪种协议、用哪种音频格式"。
 *
 * <p>归属、票据、缓冲上限、到期时间与并发上限全部由服务端决定；{@code audioFormat} 是规范形式
 * （如 {@code audio/pcm@16000:1:20}），必须命中端点已验证的格式集合。
 */
@Data
@Accessors(chain = true)
public class AiRealtimeAcceptDTO {

    /** 受理幂等键（同一主体内唯一） */
    private String requestKey;

    /** 模型端点编号 */
    private Long endpointId;

    /** 协议（WEBSOCKET/WEBRTC；显式请求，平台不做隐式降级） */
    private String protocol;

    /** 音频格式规范形式 */
    private String audioFormat;

    /** 期望会话寿命（秒；为空取平台默认值，越界拒绝） */
    private Integer sessionSeconds;
}

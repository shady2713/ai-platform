package com.basicframework.module.ai.controller.app.v1.realtime.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 受理实时会话请求（X05）：客户端只能选择端点、协议与音频格式；其余事实由服务端决定。 */
@Schema(description = "受理实时会话请求")
@Data
public class AiRealtimeSessionAcceptReqVO {

    @Schema(description = "受理幂等键（同一主体内唯一；重复提交同键同形状返回同一会话）", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank
    @Size(min = 8, max = 40)
    private String requestKey;

    @Schema(description = "模型端点编号（必须通过该协议的实时能力验证）", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull
    @Positive
    private Long endpointId;

    @Schema(description = "协议（WEBSOCKET/WEBRTC；显式请求，平台不做隐式降级）", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank
    @Size(max = 16)
    private String protocol;

    @Schema(description = "音频格式规范形式（如 audio/pcm@16000:1:20）", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank
    @Size(max = 48)
    private String audioFormat;

    @Schema(description = "期望会话寿命（秒；60–1800，默认 600；到期即关闭且不续期）")
    @Min(60)
    @Max(1800)
    private Integer sessionSeconds;
}

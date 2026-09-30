package com.basicframework.module.ai.controller.app.v1.realtime.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 上送一帧音频请求（X05）。
 *
 * <p>音频字节以 Base64 承载：本端点只做**回合栅栏 + 有界缓冲计账**，字节本身不落库
 * （会话事件只记字节数与序号）。回合号必须等于会话当前回合：更小是打断前的旧回合（拒绝并计数），
 * 更大是凭空发明回合（拒绝）。
 */
@Schema(description = "上送一帧音频请求")
@Data
public class AiRealtimeAudioPushReqVO {

    @Schema(description = "会话编号", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull
    @Positive
    private Long sessionId;

    @Schema(description = "回合号（必须等于会话当前回合）", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull
    @Min(0)
    private Long turnNo;

    @Schema(description = "帧序号（同一回合内递增）", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull
    @Min(0)
    private Long frameSeq;

    @Schema(description = "音频字节（Base64；单帧上限由会话音频格式推导，压缩封装 64 KiB）", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank
    @Size(max = 90000)
    private String payload;
}

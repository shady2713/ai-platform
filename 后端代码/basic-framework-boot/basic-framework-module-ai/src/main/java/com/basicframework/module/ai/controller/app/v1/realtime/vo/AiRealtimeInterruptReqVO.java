package com.basicframework.module.ai.controller.app.v1.realtime.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

/** 打断请求（X05）：推进会话回合，旧回合仍在飞的帧与事件随后被丢弃并计数。 */
@Schema(description = "打断请求")
@Data
public class AiRealtimeInterruptReqVO {

    @Schema(description = "会话编号", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull
    @Positive
    private Long sessionId;

    @Schema(description = "当前回合号（必须等于会话当前回合；更小=已打断，更大=不合规）", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull
    @Min(0)
    private Long turnNo;
}

package com.basicframework.module.ai.controller.app.v1.realtime.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

/** 关麦/开麦请求（X05）：关麦期间上行音频被明确拒绝（不静默丢弃）。 */
@Schema(description = "关麦/开麦请求")
@Data
public class AiRealtimeMuteReqVO {

    @Schema(description = "会话编号", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull
    @Positive
    private Long sessionId;

    @Schema(description = "true=关麦（不接受上行音频），false=开麦", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull
    private Boolean muted;
}

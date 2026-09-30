package com.basicframework.module.ai.controller.app.v1.realtime.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 票据类请求（X05）：重连与续票都必须出示当前票据（明文）。
 *
 * <p>票据是媒体面凭据：只在受理/续票响应出现一次；续票成功后旧票据立即失效（代次 +1）。
 */
@Schema(description = "票据类请求（重连/续票）")
@Data
public class AiRealtimeTicketReqVO {

    @Schema(description = "会话编号", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull
    @Positive
    private Long sessionId;

    @Schema(description = "当前票据明文（Base64URL，43 字符）", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank
    @Size(min = 32, max = 64)
    private String ticket;
}

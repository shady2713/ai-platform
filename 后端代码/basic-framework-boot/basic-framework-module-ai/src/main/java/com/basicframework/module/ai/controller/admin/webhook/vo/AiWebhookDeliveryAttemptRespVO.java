package com.basicframework.module.ai.controller.admin.webhook.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.experimental.Accessors;

/** Webhook 投递尝试响应（协议层 VO）：每一次完成尝试的结论与耗时，不含目标地址与响应正文。 */
@Schema(description = "管理后台 - Webhook 投递尝试")
@Data
@Accessors(chain = true)
public class AiWebhookDeliveryAttemptRespVO {

    @Schema(description = "第几次尝试（从 1 开始）")
    private Integer attemptNo;

    @Schema(description = "结论（DELIVERED/RETRYABLE/PERMANENT）")
    private String outcome;

    @Schema(description = "稳定原因码（送达为空）")
    private String errorCode;

    @Schema(description = "HTTP 状态码（未发出请求时为空）")
    private Integer httpStatus;

    @Schema(description = "签名时间戳（epoch 秒）")
    private Long signatureTimestamp;

    @Schema(description = "本次尝试耗时（毫秒）")
    private Long durationMs;

    @Schema(description = "本次尝试开始时间")
    private LocalDateTime startedTime;

    @Schema(description = "本次尝试结束时间")
    private LocalDateTime finishedTime;
}

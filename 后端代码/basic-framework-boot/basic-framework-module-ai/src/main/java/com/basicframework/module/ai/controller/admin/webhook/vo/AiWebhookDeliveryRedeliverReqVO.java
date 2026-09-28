package com.basicframework.module.ai.controller.admin.webhook.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;
import lombok.experimental.Accessors;

/** Webhook 死信人工重投（协议层 VO）。 */
@Schema(description = "管理后台 - Webhook 投递人工重投")
@Data
@Accessors(chain = true)
public class AiWebhookDeliveryRedeliverReqVO {

    @Schema(description = "投递编号（行主键）", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull
    @Positive
    private Long id;
}

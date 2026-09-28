package com.basicframework.module.ai.controller.admin.webhook.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.Data;
import lombok.experimental.Accessors;

/** Webhook 目标启用/停用（协议层 VO）。 */
@Schema(description = "管理后台 - Webhook 目标启用/停用")
@Data
@Accessors(chain = true)
public class AiWebhookTargetStatusReqVO {

    @Schema(description = "目标编号", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull
    @Positive
    private Long id;

    @Schema(description = "是否启用（停用即停发）", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull
    private Boolean enabled;

    @Schema(description = "乐观锁版本", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull
    @PositiveOrZero
    private Integer version;
}

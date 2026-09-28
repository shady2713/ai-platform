package com.basicframework.module.ai.controller.admin.webhook.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.ToString;
import lombok.experimental.Accessors;

/** Webhook 目标密钥轮换（协议层 VO）：新密钥只提交不回显。 */
@Schema(description = "管理后台 - Webhook 目标密钥轮换")
@Data
@Accessors(chain = true)
@ToString(exclude = {"secret"})
public class AiWebhookTargetRotateSecretReqVO {

    @Schema(description = "目标编号", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull
    @Positive
    private Long id;

    @Schema(description = "新签名密钥明文（16-128 位；平台只存密文）", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank
    @Size(max = 128)
    private String secret;

    @Schema(description = "乐观锁版本", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull
    @PositiveOrZero
    private Integer version;
}

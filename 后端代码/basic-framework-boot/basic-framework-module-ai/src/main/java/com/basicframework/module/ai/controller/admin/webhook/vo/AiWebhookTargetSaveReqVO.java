package com.basicframework.module.ai.controller.admin.webhook.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.Data;
import lombok.ToString;
import lombok.experimental.Accessors;

/** Webhook 目标新增/修改（协议层 VO）：签名密钥只提交不回显。 */
@Schema(description = "管理后台 - Webhook 目标新增/修改")
@Data
@Accessors(chain = true)
@ToString(exclude = {"secret"})
public class AiWebhookTargetSaveReqVO {

    @Schema(description = "目标编号（修改时必填）")
    private Long id;

    @Schema(description = "应用编号（创建时必填；修改时不可改）")
    private Long applicationId;

    @Schema(
            description = "目标标识（小写字母数字与连字符，3-64 位；创建后不可修改）",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "erp-run-callback")
    @Size(max = 64)
    private String code;

    @Schema(description = "目标名称", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank
    @Size(max = 128)
    private String name;

    @Schema(description = "投递地址（http/https；实际可否出站由受控出站边界的允许清单与私网策略决定）", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank
    @Size(max = 1024)
    private String targetUrl;

    @Schema(description = "事件白名单（RUN.SUCCEEDED/RUN.FAILED/RUN.CANCELLED）", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull
    private List<String> eventTypes;

    @Schema(description = "HMAC 签名密钥明文（创建时必填，16-128 位；修改时留空表示保留）")
    @Size(max = 128)
    private String secret;

    @Schema(description = "单次投递的最大尝试次数（1-10，默认 3）")
    @Positive
    private Integer maxAttempts;

    @Schema(description = "乐观锁版本（修改时必填）")
    @PositiveOrZero
    private Integer version;
}

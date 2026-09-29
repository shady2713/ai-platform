package com.basicframework.module.ai.controller.admin.application.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 多系统授权发现请求（协议层 VO，Y01）。 */
@Schema(description = "管理后台 - 多系统授权发现（当前主体在哪些系统有哪些范围）")
@Data
public class AiSystemCatalogReqVO {

    @Schema(description = "当前应用（当前系统）编号", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull
    @Positive
    private Long applicationId;

    @Schema(description = "主体类型（USER/APP）", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank
    @Size(max = 16)
    private String subjectType;

    @Schema(description = "外部用户标识（USER 必填，APP 忽略）")
    @Size(max = 128)
    private String externalUserId;
}

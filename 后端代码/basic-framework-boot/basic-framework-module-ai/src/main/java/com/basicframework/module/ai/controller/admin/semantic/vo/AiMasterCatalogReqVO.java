package com.basicframework.module.ai.controller.admin.semantic.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;
import lombok.Data;

/** 主数据映射目录发现请求（协议层 VO，Y02）：与 Y01 授权发现同构，不接受"想看哪些系统"。 */
@Schema(description = "管理后台 - 主数据映射目录发现")
@Data
public class AiMasterCatalogReqVO {

    @Schema(description = "统一对象标识", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank
    @Size(max = 64)
    private String objectCode;

    @Schema(description = "映射版本号（显式指定）", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull
    @Positive
    private Long revisionNo;

    @Schema(description = "当前应用（当前系统）编号", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull
    @Positive
    private Long applicationId;

    @Schema(description = "当前主体类型（USER/APP）", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank
    @Size(max = 16)
    private String subjectType;

    @Schema(description = "当前主体外部用户标识（USER 必填，APP 忽略）")
    @Size(max = 128)
    private String externalUserId;

    @Schema(description = "判定时刻", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull
    private LocalDateTime asOf;
}

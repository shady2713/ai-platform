package com.basicframework.module.ai.controller.admin.application.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 跨系统主体联邦映射登记请求（协议层 VO，Y01）：六段身份事实，提交后等待独立审批。 */
@Schema(description = "管理后台 - 跨系统主体联邦映射登记")
@Data
public class AiSubjectFederationSubmitReqVO {

    @Schema(description = "来源应用（当前系统）编号", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull
    @Positive
    private Long sourceApplicationId;

    @Schema(description = "来源主体类型（USER/APP）", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank
    @Size(max = 16)
    private String sourceSubjectType;

    @Schema(description = "来源主体外部用户标识（USER 必填）")
    @Size(max = 128)
    private String sourceExternalUserId;

    @Schema(description = "目标应用（目标系统）编号", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull
    @Positive
    private Long targetApplicationId;

    @Schema(description = "目标主体类型（USER/APP）", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank
    @Size(max = 16)
    private String targetSubjectType;

    @Schema(description = "目标主体外部用户标识（USER 必填；不接受按同名推断）")
    @Size(max = 128)
    private String targetExternalUserId;
}

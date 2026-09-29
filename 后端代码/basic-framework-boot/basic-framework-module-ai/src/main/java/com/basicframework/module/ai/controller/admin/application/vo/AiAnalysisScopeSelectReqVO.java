package com.basicframework.module.ai.controller.admin.application.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.Data;

/** 范围选择请求（协议层 VO，Y01）：模式 + 目标系统清单 + 看到过的目录指纹，三者缺一不可。 */
@Schema(description = "管理后台 - 跨系统分析范围选择")
@Data
public class AiAnalysisScopeSelectReqVO {

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

    @Schema(description = "选择模式（CURRENT_SYSTEM/CROSS_SYSTEM）", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank
    @Size(max = 32)
    private String mode;

    @Schema(description = "目标系统标识（CROSS_SYSTEM 必填且必须包含当前系统；CURRENT_SYSTEM 必须为空）")
    private List<@Size(max = 64) String> targetSystemCodes;

    @Schema(description = "发现接口返回的目录指纹", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank
    @Size(max = 128)
    private String catalogFingerprint;
}

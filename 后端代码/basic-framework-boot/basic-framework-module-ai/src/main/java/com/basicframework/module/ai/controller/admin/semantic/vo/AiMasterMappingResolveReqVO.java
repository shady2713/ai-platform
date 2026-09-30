package com.basicframework.module.ai.controller.admin.semantic.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;
import lombok.Data;

/** 源键判定请求（协议层 VO，Y02）：版本与判定时刻都必须显式给出，没有"取最新"的省略写法。 */
@Schema(description = "管理后台 - 主数据映射判定")
@Data
public class AiMasterMappingResolveReqVO {

    @Schema(description = "统一对象标识", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank
    @Size(max = 64)
    private String objectCode;

    @Schema(description = "映射版本号（显式指定）", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull
    @Positive
    private Long revisionNo;

    @Schema(description = "来源系统（接入应用）编号", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull
    @Positive
    private Long applicationId;

    @Schema(description = "实体类型", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank
    @Size(max = 32)
    private String entityType;

    @Schema(description = "判定时刻（报表按受理时刻解释）", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull
    private LocalDateTime asOf;
}

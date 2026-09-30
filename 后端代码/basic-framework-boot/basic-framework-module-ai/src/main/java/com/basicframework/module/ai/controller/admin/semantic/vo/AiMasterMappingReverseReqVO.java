package com.basicframework.module.ai.controller.admin.semantic.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;
import lombok.Data;

/** 源键反查请求（协议层 VO，Y02）：只按来源系统给出的源键反查，同名不会命中。 */
@Schema(description = "管理后台 - 主数据源键反查")
@Data
public class AiMasterMappingReverseReqVO {

    @Schema(description = "来源系统（接入应用）编号", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull
    @Positive
    private Long applicationId;

    @Schema(description = "实体类型", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank
    @Size(max = 32)
    private String entityType;

    @Schema(description = "来源系统里的业务主键", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank
    @Size(max = 128)
    private String sourceKey;

    @Schema(description = "判定时刻", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull
    private LocalDateTime asOf;
}

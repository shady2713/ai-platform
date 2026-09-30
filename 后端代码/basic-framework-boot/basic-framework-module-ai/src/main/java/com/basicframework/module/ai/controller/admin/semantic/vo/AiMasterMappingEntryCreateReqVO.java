package com.basicframework.module.ai.controller.admin.semantic.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;
import lombok.Data;

/** 登记源键映射请求（协议层 VO，Y02）：源键与匹配方式必填，展示名不参与判定。 */
@Schema(description = "管理后台 - 登记源键映射")
@Data
public class AiMasterMappingEntryCreateReqVO {

    @Schema(description = "统一对象编号", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull
    @Positive
    private Long masterObjectId;

    @Schema(description = "目标草稿版本号", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull
    @Positive
    private Long revisionNo;

    @Schema(description = "来源系统（接入应用）编号", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull
    @Positive
    private Long applicationId;

    @Schema(description = "实体类型（小写标识符，如 customer）", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank
    @Size(max = 32)
    private String entityType;

    @Schema(description = "来源系统里的业务主键（显式登记事实，不接受按名称推断）", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank
    @Size(max = 128)
    private String sourceKey;

    @Schema(description = "展示名（仅展示）")
    @Size(max = 128)
    private String sourceName;

    @Schema(description = "匹配方式（MANUAL/TRUSTED_FEED）", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank
    @Size(max = 16)
    private String matchMethod;

    @Schema(description = "源键有效期起点（含）", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull
    private LocalDateTime validFrom;

    @Schema(description = "源键有效期终点（不含；为空=长期有效）")
    private LocalDateTime validTo;
}

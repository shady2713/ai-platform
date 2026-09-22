package com.basicframework.module.ai.controller.admin.knowledge.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 知识库新增/修改（协议层 VO）。 */
@Schema(description = "管理后台 - AI 知识库新增/修改")
@Data
public class AiKnowledgeBaseSaveReqVO {

    @Schema(description = "知识库编号（修改时必填）")
    private Long id;

    @Schema(description = "知识库标识（创建后不可修改）")
    @Pattern(regexp = "^[a-z][a-z0-9_-]{2,63}$", message = "标识必须是小写字母开头的 3-64 位小写标识")
    private String code;

    @Schema(description = "知识库名称", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotEmpty
    @Size(max = 128)
    private String name;

    @Schema(description = "说明")
    @Size(max = 512)
    private String description;

    @Schema(description = "可见性（SHARED 共享/APPLICATION 应用专用）")
    @Pattern(regexp = "^(SHARED|APPLICATION)$", message = "可见性只能是 SHARED 或 APPLICATION")
    private String visibility;

    @Schema(description = "所属应用编号（应用专用必填）")
    @Positive
    private Long ownerApplicationId;

    @Schema(description = "管理者用户编号（仅展示）")
    @Positive
    private Long managerUserId;

    @Schema(description = "嵌入模型标识（创建后不可修改）")
    @Size(max = 64)
    private String embeddingModel;

    @Schema(description = "嵌入维度（创建后不可修改）")
    @Min(1)
    @Max(8192)
    private Integer embeddingDimension;

    @Schema(description = "保留策略（天）")
    @Min(1)
    @Max(3650)
    private Integer retentionDays;

    @Schema(description = "乐观锁版本（修改时必填）")
    @PositiveOrZero
    private Integer version;
}

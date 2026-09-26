package com.basicframework.module.ai.controller.admin.evaluation.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.experimental.Accessors;

/** 人工复核评测结果（Q04）。 */
@Schema(description = "管理后台 - 人工复核评测结果")
@Data
@Accessors(chain = true)
public class AiEvalReviewReqVO {

    @Schema(description = "结果编号", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull
    private Long resultId;

    @Schema(description = "复核结论：true 通过 / false 否决", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull
    private Boolean approve;

    @Schema(description = "复核备注")
    @Size(max = 512)
    private String note;
}

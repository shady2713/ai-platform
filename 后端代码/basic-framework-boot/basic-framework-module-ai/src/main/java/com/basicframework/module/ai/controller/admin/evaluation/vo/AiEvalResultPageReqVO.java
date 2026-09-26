package com.basicframework.module.ai.controller.admin.evaluation.vo;

import com.basicframework.framework.common.pojo.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import lombok.experimental.Accessors;

/** 评测结果分页（Q04）。 */
@Schema(description = "管理后台 - 评测结果分页")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Accessors(chain = true)
public class AiEvalResultPageReqVO extends PageParam {

    @Schema(description = "评测运行编号", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull
    @Positive
    private Long runId;

    @Schema(description = "判定（PASSED/FAILED/ERROR/REVIEW_REQUIRED）")
    private String status;
}

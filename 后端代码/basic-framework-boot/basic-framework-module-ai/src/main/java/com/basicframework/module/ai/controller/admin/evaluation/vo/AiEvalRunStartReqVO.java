package com.basicframework.module.ai.controller.admin.evaluation.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;
import lombok.experimental.Accessors;

/** 开始评测运行（Q04）。 */
@Schema(description = "管理后台 - 开始 AI 评测运行")
@Data
@Accessors(chain = true)
public class AiEvalRunStartReqVO {

    @Schema(description = "套件编号", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull
    @Positive
    private Long suiteId;
}

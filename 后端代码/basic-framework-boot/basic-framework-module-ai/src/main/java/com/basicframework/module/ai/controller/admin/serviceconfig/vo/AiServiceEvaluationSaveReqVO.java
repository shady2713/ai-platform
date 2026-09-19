package com.basicframework.module.ai.controller.admin.serviceconfig.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.experimental.Accessors;

/** 记录评测结论（协议层 VO）：调用方只提交得分与用例数，通过与否由平台判定。 */
@Schema(description = "管理后台 - 记录 AI 服务发布评测结论")
@Data
@Accessors(chain = true)
public class AiServiceEvaluationSaveReqVO {

    @Schema(description = "发布版本编号", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull
    private Long releaseId;

    @Schema(description = "评测得分（0-100）", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull
    @Min(0)
    @Max(100)
    private Integer score;

    @Schema(description = "评测用例数（至少 1）", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull
    @Min(1)
    private Integer caseCount;

    @Schema(description = "备注（不得包含提示词、响应正文或凭据）")
    @Size(max = 512)
    private String notes;
}

package com.basicframework.module.ai.controller.admin.query.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.Data;

/** 查询规划请求（协议层 VO）。 */
@Schema(description = "管理后台 - AI 查询规划请求")
@Data
public class AiQueryPlanReqVO {

    @Schema(description = "数据集编号", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull
    @Positive
    private Long datasetId;

    @Schema(description = "数据集版本编号（缺省取最新已发布版本）")
    @Positive
    private Long datasetVersionId;

    @Schema(description = "模型端点编号", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull
    @Positive
    private Long endpointId;

    @Schema(description = "用户问题", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotEmpty
    @Size(max = 2000)
    private String question;

    @Schema(description = "本次允许的字段/指标码（缺省为数据集定义全部字段）")
    private List<String> allowedFieldCodes;

    @Schema(description = "允许的修复次数（上限 2）")
    private Integer maxRepairs;
}

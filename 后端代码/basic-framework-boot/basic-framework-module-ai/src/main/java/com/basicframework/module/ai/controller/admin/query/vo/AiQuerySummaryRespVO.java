package com.basicframework.module.ai.controller.admin.query.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

/** 数据集摘要（协议层 VO）：模型能看到的全部信息，用于自检信息边界。 */
@Schema(description = "管理后台 - AI 数据集摘要（模型可见范围）")
@Data
@Accessors(chain = true)
public class AiQuerySummaryRespVO {

    @Schema(description = "数据集编号")
    private Long datasetId;

    @Schema(description = "摘要 JSON（模型可见的字段/指标/维度/时间/当前时间）")
    private String summaryJson;
}

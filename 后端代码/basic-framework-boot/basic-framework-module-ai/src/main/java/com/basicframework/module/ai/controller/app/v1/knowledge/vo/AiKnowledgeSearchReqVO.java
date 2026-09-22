package com.basicframework.module.ai.controller.app.v1.knowledge.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 知识检索请求（协议层 VO）：问题文本只用于向量化，不参与过滤条件构造。 */
@Schema(description = "AI 应用端 - 知识检索请求")
@Data
public class AiKnowledgeSearchReqVO {

    @Schema(description = "问题文本", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotEmpty
    @Size(max = 1_000)
    private String query;

    @Schema(description = "返回条数（1-20，默认 5）")
    @Min(1)
    @Max(20)
    private Integer topK;
}

package com.basicframework.module.ai.controller.admin.knowledge.vo;

import com.basicframework.framework.common.pojo.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 文档版本分页查询（协议层 VO）。 */
@Schema(description = "管理后台 - AI 知识文档版本分页查询")
@Data
@EqualsAndHashCode(callSuper = true)
public class AiKnowledgeDocumentVersionPageReqVO extends PageParam {

    @Schema(description = "文档编号", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull
    @Positive
    private Long documentId;
}

package com.basicframework.module.ai.controller.admin.knowledge.vo;

import com.basicframework.framework.common.pojo.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 知识文档分页查询（协议层 VO）。 */
@Schema(description = "管理后台 - AI 知识文档分页查询")
@Data
@EqualsAndHashCode(callSuper = true)
public class AiKnowledgeDocumentPageReqVO extends PageParam {

    @Schema(description = "知识库编号")
    private Long knowledgeBaseId;

    @Schema(description = "状态（PENDING/PARSING/INDEXING/READY/FAILED/DELETING）")
    private String status;
}

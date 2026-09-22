package com.basicframework.module.ai.controller.admin.knowledge.vo;

import com.basicframework.framework.common.pojo.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 入库任务分页查询（协议层 VO）。 */
@Schema(description = "管理后台 - AI 知识入库任务分页查询")
@Data
@EqualsAndHashCode(callSuper = true)
public class AiKnowledgeIngestionTaskPageReqVO extends PageParam {

    @Schema(description = "知识库编号")
    private Long knowledgeBaseId;

    @Schema(description = "状态（QUEUED/RUNNING/SUCCEEDED/FAILED/UNKNOWN）")
    private String status;
}

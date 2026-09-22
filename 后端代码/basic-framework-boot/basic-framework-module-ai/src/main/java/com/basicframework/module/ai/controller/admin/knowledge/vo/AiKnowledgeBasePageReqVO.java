package com.basicframework.module.ai.controller.admin.knowledge.vo;

import com.basicframework.framework.common.pojo.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 知识库分页查询（协议层 VO）。 */
@Schema(description = "管理后台 - AI 知识库分页查询")
@Data
@EqualsAndHashCode(callSuper = true)
public class AiKnowledgeBasePageReqVO extends PageParam {

    @Schema(description = "可见性（SHARED/APPLICATION）")
    private String visibility;

    @Schema(description = "所属应用编号")
    private Long ownerApplicationId;

    @Schema(description = "状态（ENABLED/DISABLED）")
    private String status;
}

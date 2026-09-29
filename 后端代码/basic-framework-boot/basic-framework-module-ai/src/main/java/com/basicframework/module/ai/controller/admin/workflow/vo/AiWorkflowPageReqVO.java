package com.basicframework.module.ai.controller.admin.workflow.vo;

import com.basicframework.framework.common.pojo.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** AI 流程定义分页查询（协议层 VO）。 */
@Schema(description = "管理后台 - AI 流程定义分页查询")
@Data
@EqualsAndHashCode(callSuper = true)
public class AiWorkflowPageReqVO extends PageParam {

    @Schema(description = "应用编号（精确）", example = "1")
    private Long applicationId;

    @Schema(description = "流程标识/名称关键词（模糊）", example = "order")
    @Size(max = 64)
    private String code;

    @Schema(description = "状态（ENABLED/DISABLED）", example = "ENABLED")
    @Size(max = 16)
    private String status;
}

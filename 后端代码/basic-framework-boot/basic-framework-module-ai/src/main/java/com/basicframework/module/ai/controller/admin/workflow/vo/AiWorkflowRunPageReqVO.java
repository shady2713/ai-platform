package com.basicframework.module.ai.controller.admin.workflow.vo;

import com.basicframework.framework.common.pojo.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** AI 流程运行分页查询（协议层 VO）。 */
@Schema(description = "管理后台 - AI 流程运行分页查询")
@Data
@EqualsAndHashCode(callSuper = true)
public class AiWorkflowRunPageReqVO extends PageParam {

    @Schema(description = "流程编号（精确）", example = "1")
    @Positive
    private Long workflowId;

    @Schema(description = "状态（RUNNING/SUCCEEDED/FAILED）", example = "FAILED")
    @Size(max = 16)
    private String status;
}

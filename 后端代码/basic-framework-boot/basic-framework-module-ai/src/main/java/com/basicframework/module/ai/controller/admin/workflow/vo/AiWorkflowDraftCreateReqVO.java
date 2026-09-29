package com.basicframework.module.ai.controller.admin.workflow.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.experimental.Accessors;

/** AI 流程草稿新建（协议层 VO）：图 JSON 是受控契约，形状与规模在服务层解析判定。 */
@Schema(description = "管理后台 - AI 流程草稿新建")
@Data
@Accessors(chain = true)
public class AiWorkflowDraftCreateReqVO {

    @Schema(description = "流程编号", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull
    @Positive
    private Long workflowId;

    @Schema(description = "流程图 JSON（nodes/edges 受控契约）", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank
    @Size(max = 16000, message = "流程图 JSON 最长 16000 字符")
    private String graphJson;
}

package com.basicframework.module.ai.controller.admin.workflow.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.Data;
import lombok.experimental.Accessors;

/** AI 流程定义启用/停用（协议层 VO）。 */
@Schema(description = "管理后台 - AI 流程定义启用/停用")
@Data
@Accessors(chain = true)
public class AiWorkflowStatusReqVO {

    @Schema(description = "流程编号", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull
    @Positive
    private Long id;

    @Schema(description = "是否启用（false 即停用，停用后不受理新运行）", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull
    private Boolean enabled;

    @Schema(description = "乐观锁版本", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull
    @PositiveOrZero
    private Integer version;
}

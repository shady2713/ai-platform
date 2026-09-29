package com.basicframework.module.ai.controller.admin.workflow.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.experimental.Accessors;

/** AI 流程定义新增/修改（协议层 VO）：标识创建后不可修改，语义校验在服务层。 */
@Schema(description = "管理后台 - AI 流程定义新增/修改")
@Data
@Accessors(chain = true)
public class AiWorkflowSaveReqVO {

    @Schema(description = "流程编号（修改时必填）")
    private Long id;

    @Schema(description = "应用编号（创建时必填；修改时不可改）")
    @Positive
    private Long applicationId;

    @Schema(
            description = "流程标识（字母开头，字母数字连字符下划线，3-64 位；创建后不可修改）",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "order-summary-flow")
    @Size(max = 64)
    private String code;

    @Schema(description = "流程名称", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank
    @Size(max = 128)
    private String name;

    @Schema(description = "流程说明", example = "订单摘要生成流程")
    @Size(max = 512)
    private String description;

    @Schema(description = "乐观锁版本（修改时必填）")
    @PositiveOrZero
    private Integer version;
}

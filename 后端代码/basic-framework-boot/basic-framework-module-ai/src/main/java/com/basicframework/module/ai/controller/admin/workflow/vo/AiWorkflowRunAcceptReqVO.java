package com.basicframework.module.ai.controller.admin.workflow.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.experimental.Accessors;

/** AI 流程运行受理（协议层 VO）：受理即固定最新已发布版本并同步执行。 */
@Schema(description = "管理后台 - AI 流程运行受理")
@Data
@Accessors(chain = true)
public class AiWorkflowRunAcceptReqVO {

    @Schema(description = "流程编号", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull
    @Positive
    private Long workflowId;

    @Schema(description = "受理幂等键（16-128 位；同一流程内唯一，重复受理返回首次运行）", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank
    @Size(max = 128)
    private String idempotencyKey;

    @Schema(
            description = "数据等级（L1_PUBLIC/L2_INTERNAL；模型节点外发等级）",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "L2_INTERNAL")
    @NotBlank
    @Size(max = 16)
    private String dataLevel;

    @Schema(description = "运行输入（开始节点的透传文本）", example = "帮我总结上周的订单数据")
    @Size(max = 4000, message = "运行输入最长 4000 字")
    private String inputText;

    @Schema(description = "步数预算（可选；只能比平台默认更紧，上限为图节点数上限）")
    @PositiveOrZero
    private Integer maxSteps;

    @Schema(description = "耗时预算毫秒（可选；平台封顶 120000）")
    @PositiveOrZero
    private Long maxDurationMillis;
}

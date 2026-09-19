package com.basicframework.module.ai.controller.app.v1.task.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.Data;
import lombok.experimental.Accessors;

/** 人工重试（应用端协议层 VO）：显式动作，重试前按当前权限重新判定。 */
@Schema(description = "应用端 - 人工重试 AI 运行任务")
@Data
@Accessors(chain = true)
public class AiTaskRetryReqVO {

    @Schema(description = "运行编号", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull
    @Positive
    private Long runId;

    @Schema(description = "乐观锁版本（运行行版本）", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull
    @PositiveOrZero
    private Integer version;
}

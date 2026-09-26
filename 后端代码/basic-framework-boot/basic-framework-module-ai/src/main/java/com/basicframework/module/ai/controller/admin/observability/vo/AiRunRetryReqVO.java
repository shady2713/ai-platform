package com.basicframework.module.ai.controller.admin.observability.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.Data;
import lombok.experimental.Accessors;

/** 管理端人工重试（Q03，协议层 VO）：显式动作，带运行行乐观锁版本。 */
@Schema(description = "管理后台 - 人工重试 AI 运行任务")
@Data
@Accessors(chain = true)
public class AiRunRetryReqVO {

    @Schema(description = "运行编号", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull
    @Positive
    private Long runId;

    @Schema(description = "乐观锁版本（运行行版本；界面从详情读取）", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull
    @PositiveOrZero
    private Integer version;
}

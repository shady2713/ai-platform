package com.basicframework.module.ai.controller.app.v1.run.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.Data;
import lombok.experimental.Accessors;

/** 取消运行（应用端协议层 VO）：取消是显式动作，幂等由乐观锁版本保证。 */
@Schema(description = "应用端 - 取消 AI 运行")
@Data
@Accessors(chain = true)
public class AiRunCancelReqVO {

    @Schema(description = "运行编号", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull
    @Positive
    private Long runId;

    @Schema(description = "乐观锁版本", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull
    @PositiveOrZero
    private Integer version;
}

package com.basicframework.module.ai.controller.app.v1.action.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.Map;
import lombok.Data;

/** 工具动作确认请求（协议层 VO）：必须携带挑战与原参数。 */
@Schema(description = "AI 应用端 - 工具动作确认请求")
@Data
public class AiToolActionConfirmReqVO {

    @Schema(description = "动作编号", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull
    @Positive
    private Long actionId;

    @Schema(description = "一次性确认挑战", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotEmpty
    @Size(max = 64)
    private String challenge;

    @Schema(description = "原参数（与发起时一致；改参数会被拒绝）")
    private Map<String, Object> arguments;
}

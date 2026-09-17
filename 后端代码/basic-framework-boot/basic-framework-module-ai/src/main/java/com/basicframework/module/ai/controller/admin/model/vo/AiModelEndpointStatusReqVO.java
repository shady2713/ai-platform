package com.basicframework.module.ai.controller.admin.model.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Schema(description = "模型端点启停请求")
@Data
public class AiModelEndpointStatusReqVO {

    @Schema(description = "端点编号", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "端点编号不能为空")
    private Long id;

    @Schema(description = "是否启用", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "启用状态不能为空")
    private Boolean enabled;

    @Schema(description = "乐观锁版本", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "乐观锁版本不能为空")
    private Integer version;
}

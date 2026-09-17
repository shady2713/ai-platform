package com.basicframework.module.ai.controller.admin.application.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** 应用启停请求（协议层 VO）。 */
@Schema(description = "管理后台 - AI 应用启停")
@Data
public class AiApplicationStatusReqVO {

    @Schema(description = "应用编号", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull
    private Long id;

    @Schema(description = "是否启用", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull
    private Boolean enabled;

    @Schema(description = "乐观锁版本", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull
    private Integer version;
}

package com.basicframework.module.ai.controller.admin.tool.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.Data;

/** 工具版本发布请求（协议层 VO）。 */
@Schema(description = "管理后台 - AI 工具版本发布请求")
@Data
public class AiToolVersionPublishReqVO {

    @Schema(description = "版本编号", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull
    @Positive
    private Long versionId;

    @Schema(description = "乐观锁版本", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull
    @PositiveOrZero
    private Integer version;
}

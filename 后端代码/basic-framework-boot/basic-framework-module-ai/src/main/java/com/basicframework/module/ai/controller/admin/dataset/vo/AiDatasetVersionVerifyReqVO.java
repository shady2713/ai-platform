package com.basicframework.module.ai.controller.admin.dataset.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.Data;

/** 版本验证/发布请求（协议层 VO）：只需要版本编号与乐观锁版本。 */
@Schema(description = "管理后台 - AI 数据集版本验证/发布请求")
@Data
public class AiDatasetVersionVerifyReqVO {

    @Schema(description = "版本编号", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull
    @Positive
    private Long versionId;

    @Schema(description = "乐观锁版本", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull
    @PositiveOrZero
    private Integer version;
}

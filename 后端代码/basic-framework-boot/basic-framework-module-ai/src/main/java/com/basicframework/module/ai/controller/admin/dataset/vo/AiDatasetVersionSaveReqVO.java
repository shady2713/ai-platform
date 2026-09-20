package com.basicframework.module.ai.controller.admin.dataset.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 语义版本创建（协议层 VO）：定义是 JSON 文本，键与取值由服务端严格校验。 */
@Schema(description = "管理后台 - AI 数据集语义版本创建")
@Data
public class AiDatasetVersionSaveReqVO {

    @Schema(description = "数据集编号", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull
    @Positive
    private Long datasetId;

    @Schema(description = "语义定义（JSON 对象文本）", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotEmpty
    @Size(max = 7000)
    private String definitionJson;
}

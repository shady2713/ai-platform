package com.basicframework.module.ai.controller.admin.tool.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 工具版本创建（协议层 VO）。 */
@Schema(description = "管理后台 - AI 工具版本创建")
@Data
public class AiToolVersionSaveReqVO {

    @Schema(description = "工具编号", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull
    @Positive
    private Long toolId;

    @Schema(description = "类型（READ/WRITE；首期只允许发布 READ）")
    private String toolType;

    @Schema(description = "执行政策（AUTO/CONFIRM/DENY；缺省 DENY）")
    private String policy;

    @Schema(description = "来源类型（首期只支持 HTTP_OPERATION）")
    private String sourceKind;

    @Schema(description = "来源标识（operationKey）", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotEmpty
    @Size(max = 128)
    private String sourceRef;

    @Schema(description = "输入 schema（JSON：参数名 → {type, required}）", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotEmpty
    @Size(max = 4000)
    private String inputSchemaJson;

    @Schema(description = "输出 schema（JSON：结果列声明）", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotEmpty
    @Size(max = 4000)
    private String outputSchemaJson;
}

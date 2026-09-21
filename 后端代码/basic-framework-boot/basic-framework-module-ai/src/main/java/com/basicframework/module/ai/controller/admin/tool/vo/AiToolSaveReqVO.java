package com.basicframework.module.ai.controller.admin.tool.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 工具新增/修改（协议层 VO）。 */
@Schema(description = "管理后台 - AI 工具新增/修改")
@Data
public class AiToolSaveReqVO {

    @Schema(description = "工具编号（修改时必填）")
    private Long id;

    @Schema(description = "工具标识（创建后不可修改）")
    @Size(max = 64)
    private String code;

    @Schema(description = "工具名称", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotEmpty
    @Size(max = 128)
    private String name;

    @Schema(description = "说明（供模型理解用途）")
    @Size(max = 512)
    private String description;

    @Schema(description = "连接器编号（创建时必填）")
    @Positive
    private Long connectorId;

    @Schema(description = "乐观锁版本（修改时必填）")
    @PositiveOrZero
    private Integer version;
}

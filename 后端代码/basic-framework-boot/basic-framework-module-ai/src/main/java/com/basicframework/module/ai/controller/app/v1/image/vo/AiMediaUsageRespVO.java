package com.basicframework.module.ai.controller.app.v1.image.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

/** 实际用量（协议层）：上游没给就是 UNKNOWN 且数量为空，不用 0 冒充真实计量（AT-060）。 */
@Data
@Accessors(chain = true)
@Schema(description = "实际用量（上游缺失时来源为 UNKNOWN 且数量为空）")
public class AiMediaUsageRespVO {

    @Schema(description = "计量单位（未知为空）", example = "TOKEN")
    private String unit;

    @Schema(description = "计量数值（未知为空）", example = "1024")
    private Long quantity;

    @Schema(description = "计量来源（REPORTED/UNKNOWN）", example = "REPORTED")
    private String source;
}

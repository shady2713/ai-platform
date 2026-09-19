package com.basicframework.module.ai.controller.admin.debug.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

/** 调试阶段摘要（协议层 VO）：只有阶段名、结果与耗时，不含正文。 */
@Schema(description = "管理后台 - AI 服务调试阶段摘要")
@Data
@Accessors(chain = true)
public class AiDebugStageRespVO {

    @Schema(description = "阶段（RESOLVE/AUTHORIZE/CONTEXT/MODEL）")
    private String stage;

    @Schema(description = "结果（OK/FAILED）")
    private String status;

    @Schema(description = "稳定说明（不含提示词与响应正文）")
    private String detail;

    @Schema(description = "该阶段耗时（毫秒）")
    private Long durationMs;
}

package com.basicframework.module.ai.controller.admin.model.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

/** 探测结果响应（协议层 VO）：只含结论与耗时，不含凭据、提示词或上游报文。 */
@Schema(description = "管理后台 - 模型能力探测结果")
@Data
@Accessors(chain = true)
public class AiModelProbeResultRespVO {

    @Schema(description = "探测类型", example = "TEXT")
    private String probeKind;

    @Schema(description = "结论状态：SUPPORTED 可用 / UNSUPPORTED 明确不支持 / FAILED 探测失败", example = "SUPPORTED")
    private String status;

    @Schema(description = "稳定明细码：失败原因名或不支持原因；成功时为空", example = "TIMEOUT")
    private String detailCode;

    @Schema(description = "嵌入探测观测到的向量维度", example = "1536")
    private Integer embeddingDimension;

    @Schema(description = "真实调用耗时（毫秒）", example = "128")
    private Integer latencyMs;

    @Schema(description = "探测时的配置版本", example = "1")
    private Integer configRevision;
}

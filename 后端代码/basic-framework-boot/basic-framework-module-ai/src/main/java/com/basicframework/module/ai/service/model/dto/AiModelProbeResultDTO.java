package com.basicframework.module.ai.service.model.dto;

import lombok.Data;
import lombok.experimental.Accessors;

/** 探测结果（服务层 DTO）：只含结论与耗时，不含凭据、提示词或上游报文。 */
@Data
@Accessors(chain = true)
public class AiModelProbeResultDTO {

    /** 探测类型（ModelProbeKind 名称） */
    private String probeKind;

    /** 结论状态（SUPPORTED/UNSUPPORTED/FAILED） */
    private String status;

    /** 稳定明细码：失败原因名或不支持原因；成功为空 */
    private String detailCode;

    /** 嵌入探测观测到的向量维度 */
    private Integer embeddingDimension;

    /** 真实调用耗时（毫秒） */
    private Integer latencyMs;

    /** 探测时的配置版本 */
    private Integer configRevision;
}

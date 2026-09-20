package com.basicframework.module.ai.service.connector.dto;

import lombok.Data;
import lombok.experimental.Accessors;

/** 连接器探测结论（D01）：只给稳定原因码与耗时，不含主机、凭据与异常正文。 */
@Data
@Accessors(chain = true)
public class AiConnectorProbeResultDTO {

    /** 连接器编号 */
    private Long connectorId;

    /** 探测类型 */
    private String probeKind;

    /** 结论（SUPPORTED/FAILED） */
    private String status;

    /** 失败原因码（稳定词表；成功为空） */
    private String detailCode;

    /** 耗时（毫秒） */
    private Integer latencyMs;
}

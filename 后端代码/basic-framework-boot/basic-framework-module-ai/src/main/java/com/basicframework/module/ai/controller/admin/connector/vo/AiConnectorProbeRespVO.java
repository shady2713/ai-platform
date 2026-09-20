package com.basicframework.module.ai.controller.admin.connector.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

/** 连接器探测结论（协议层 VO）：只给稳定原因码与耗时，不含主机与凭据。 */
@Schema(description = "管理后台 - AI 连接器探测结论")
@Data
@Accessors(chain = true)
public class AiConnectorProbeRespVO {

    @Schema(description = "连接器编号")
    private Long connectorId;

    @Schema(description = "探测类型（HTTP_CONNECTIVITY/MYSQL_CONNECTIVITY）")
    private String probeKind;

    @Schema(description = "结论（SUPPORTED/FAILED）")
    private String status;

    @Schema(description = "失败原因码（稳定词表；成功为空）")
    private String detailCode;

    @Schema(description = "耗时（毫秒）")
    private Integer latencyMs;
}

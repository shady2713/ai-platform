package com.basicframework.module.ai.controller.admin.connector.vo;

import com.basicframework.framework.common.pojo.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 连接器分页查询（协议层 VO）。 */
@Schema(description = "管理后台 - AI 连接器分页查询")
@Data
@EqualsAndHashCode(callSuper = true)
public class AiConnectorPageReqVO extends PageParam {

    @Schema(description = "类型（HTTP/MYSQL）")
    private String connectorType;

    @Schema(description = "状态（ENABLED/DISABLED）")
    private String status;
}

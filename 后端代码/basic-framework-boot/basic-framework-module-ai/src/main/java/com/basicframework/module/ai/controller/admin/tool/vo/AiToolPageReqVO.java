package com.basicframework.module.ai.controller.admin.tool.vo;

import com.basicframework.framework.common.pojo.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 工具分页查询（协议层 VO）。 */
@Schema(description = "管理后台 - AI 工具分页查询")
@Data
@EqualsAndHashCode(callSuper = true)
public class AiToolPageReqVO extends PageParam {

    @Schema(description = "连接器编号")
    private Long connectorId;

    @Schema(description = "状态（ENABLED/DISABLED）")
    private String status;
}

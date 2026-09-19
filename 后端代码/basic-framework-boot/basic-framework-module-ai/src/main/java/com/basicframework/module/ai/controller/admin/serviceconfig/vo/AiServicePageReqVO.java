package com.basicframework.module.ai.controller.admin.serviceconfig.vo;

import com.basicframework.framework.common.pojo.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 服务分页查询（协议层 VO）。 */
@Schema(description = "管理后台 - AI 服务分页查询")
@Data
@EqualsAndHashCode(callSuper = true)
public class AiServicePageReqVO extends PageParam {

    @Schema(description = "所属应用编号")
    private Long appId;

    @Schema(description = "服务标识（模糊匹配）")
    private String code;

    @Schema(description = "状态")
    private String status;
}

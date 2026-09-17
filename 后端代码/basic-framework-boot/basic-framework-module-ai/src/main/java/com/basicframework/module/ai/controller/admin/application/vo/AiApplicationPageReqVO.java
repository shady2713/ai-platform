package com.basicframework.module.ai.controller.admin.application.vo;

import com.basicframework.framework.common.pojo.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 应用分页查询（协议层 VO）。 */
@Schema(description = "管理后台 - AI 应用分页查询")
@Data
@EqualsAndHashCode(callSuper = true)
public class AiApplicationPageReqVO extends PageParam {

    @Schema(description = "应用标识（模糊匹配）")
    private String appCode;

    @Schema(description = "是否启用")
    private Boolean enabled;
}

package com.basicframework.module.ai.controller.admin.grant.vo;

import com.basicframework.framework.common.pojo.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 资源授权分页查询（协议层 VO）。 */
@Schema(description = "管理后台 - 资源授权分页查询")
@Data
@EqualsAndHashCode(callSuper = true)
public class AiResourceGrantPageReqVO extends PageParam {

    @Schema(description = "应用编号")
    private Long applicationId;

    @Schema(description = "主体类型")
    private String subjectType;

    @Schema(description = "外部用户标识（模糊匹配）")
    private String externalUserId;

    @Schema(description = "资源类型")
    private String resourceType;
}

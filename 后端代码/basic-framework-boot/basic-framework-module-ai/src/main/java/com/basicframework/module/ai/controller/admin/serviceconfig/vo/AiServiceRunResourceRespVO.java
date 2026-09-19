package com.basicframework.module.ai.controller.admin.serviceconfig.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

/** 运行固定到的单条资源版本（协议层 VO）：只有标识与版本，不含授权结论。 */
@Schema(description = "管理后台 - AI 服务运行固定的资源版本")
@Data
@Accessors(chain = true)
public class AiServiceRunResourceRespVO {

    @Schema(description = "资源绑定编号")
    private Long id;

    @Schema(description = "资源类型")
    private String resourceType;

    @Schema(description = "资源标识")
    private String resourceKey;

    @Schema(description = "固定时的绑定版本")
    private Integer version;
}

package com.basicframework.module.ai.controller.admin.serviceconfig.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;
import lombok.experimental.Accessors;

/** 发布版本状态切换（发布/停用，协议层 VO）。 */
@Schema(description = "管理后台 - AI 服务发布版本状态切换")
@Data
@Accessors(chain = true)
public class AiServiceReleaseActionReqVO {

    @Schema(description = "服务编号（停用时必填）")
    private Long serviceId;

    @Schema(description = "发布版本编号（发布时必填）")
    private Long releaseId;

    @Schema(description = "乐观锁版本（发布用版本行版本，停用用服务行版本）", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull
    @Positive
    private Integer version;
}

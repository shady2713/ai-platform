package com.basicframework.module.ai.controller.admin.serviceconfig.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.Data;
import lombok.experimental.Accessors;

/** 创建发布候选（协议层 VO）。 */
@Schema(description = "管理后台 - 创建 AI 服务发布候选")
@Data
@Accessors(chain = true)
public class AiServiceReleaseCreateReqVO {

    @Schema(description = "服务编号", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull
    private Long serviceId;

    @Schema(description = "草稿乐观锁版本（并发编辑时冲突）", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull
    @PositiveOrZero
    private Integer version;
}

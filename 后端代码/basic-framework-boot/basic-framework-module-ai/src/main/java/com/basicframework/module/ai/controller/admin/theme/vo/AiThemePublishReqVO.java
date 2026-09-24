package com.basicframework.module.ai.controller.admin.theme.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.Data;
import lombok.experimental.Accessors;

/** 主题发布/回退（协议层 VO）：同一动作，目标是历史修订即回退。 */
@Schema(description = "管理后台 - AI 主题发布/回退")
@Data
@Accessors(chain = true)
public class AiThemePublishReqVO {

    @Schema(description = "主题修订编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    @NotNull(message = "主题修订编号不能为空")
    @Positive(message = "主题修订编号必须为正数")
    private Long id;

    @Schema(description = "乐观锁版本", requiredMode = Schema.RequiredMode.REQUIRED, example = "0")
    @NotNull(message = "乐观锁版本不能为空")
    @PositiveOrZero(message = "乐观锁版本不能为负数")
    private Integer version;
}

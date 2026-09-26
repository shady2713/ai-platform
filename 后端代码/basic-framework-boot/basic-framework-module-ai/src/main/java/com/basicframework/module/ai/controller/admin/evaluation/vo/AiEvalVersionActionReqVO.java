package com.basicframework.module.ai.controller.admin.evaluation.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.Data;
import lombok.experimental.Accessors;

/** 带乐观锁版本的动作请求（Q04：冻结、创建新修订、删除样例）。 */
@Schema(description = "管理后台 - 评测动作（带乐观锁版本）")
@Data
@Accessors(chain = true)
public class AiEvalVersionActionReqVO {

    @Schema(description = "目标编号（套件或样例）", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull
    private Long id;

    @Schema(description = "乐观锁版本", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull
    @PositiveOrZero
    private Integer version;
}

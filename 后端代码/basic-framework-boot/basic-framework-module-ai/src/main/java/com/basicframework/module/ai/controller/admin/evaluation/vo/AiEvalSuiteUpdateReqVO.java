package com.basicframework.module.ai.controller.admin.evaluation.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.experimental.Accessors;

/** 更新评测套件（Q04，仅草稿可改；标识与服务不可修改）。 */
@Schema(description = "管理后台 - 更新 AI 评测套件")
@Data
@Accessors(chain = true)
public class AiEvalSuiteUpdateReqVO {

    @Schema(description = "套件编号", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull
    private Long id;

    @Schema(description = "乐观锁版本", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull
    @PositiveOrZero
    private Integer version;

    @Schema(description = "套件名称", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank
    @Size(max = 128)
    private String name;

    @Schema(description = "说明")
    @Size(max = 512)
    private String description;

    @Schema(description = "样例数据分级（L1_PUBLIC/L2_INTERNAL）")
    @Pattern(regexp = "^(L1_PUBLIC|L2_INTERNAL)$", message = "评测样例只允许公开或内部分级")
    private String dataLevel;
}

package com.basicframework.module.ai.controller.admin.evaluation.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.experimental.Accessors;

/** 创建评测套件（Q04，协议层 VO）。 */
@Schema(description = "管理后台 - 创建 AI 评测套件")
@Data
@Accessors(chain = true)
public class AiEvalSuiteSaveReqVO {

    @Schema(description = "所属应用编号", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull
    @Positive
    private Long applicationId;

    @Schema(description = "套件标识（应用内唯一，创建后不可修改）", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank
    @Pattern(regexp = "^[a-z][a-z0-9_-]{2,63}$", message = "小写字母开头，仅小写字母/数字/下划线/连字符")
    private String code;

    @Schema(description = "套件名称", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank
    @Size(max = 128)
    private String name;

    @Schema(description = "说明")
    @Size(max = 512)
    private String description;

    @Schema(description = "被评测的服务编号", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull
    @Positive
    private Long serviceId;

    @Schema(description = "执行主体类型（APP/USER）", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank
    @Pattern(regexp = "^(APP|USER)$", message = "主体类型只能是 APP 或 USER")
    private String subjectType;

    @Schema(description = "执行主体标识（合成主体，不是真实用户）")
    @Size(max = 64)
    private String externalUserId;

    @Schema(description = "样例数据分级（L1_PUBLIC/L2_INTERNAL）")
    @Pattern(regexp = "^(L1_PUBLIC|L2_INTERNAL)$", message = "评测样例只允许公开或内部分级")
    private String dataLevel;
}

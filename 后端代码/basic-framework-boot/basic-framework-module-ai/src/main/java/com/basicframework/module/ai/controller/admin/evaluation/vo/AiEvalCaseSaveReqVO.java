package com.basicframework.module.ai.controller.admin.evaluation.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.experimental.Accessors;

/** 保存评测样例（Q04，创建与更新共用；仅草稿套件可改）。 */
@Schema(description = "管理后台 - 保存 AI 评测样例")
@Data
@Accessors(chain = true)
public class AiEvalCaseSaveReqVO {

    @Schema(description = "样例编号（更新时必填）")
    private Long id;

    @Schema(description = "乐观锁版本（更新时必填）")
    @PositiveOrZero
    private Integer version;

    @Schema(description = "所属套件编号", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull
    @Positive
    private Long suiteId;

    @Schema(description = "样例标识（套件内唯一）", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank
    @Pattern(regexp = "^[a-z][a-z0-9_-]{1,63}$", message = "小写字母开头，仅小写字母/数字/下划线/连字符")
    private String caseKey;

    @Schema(description = "标题", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank
    @Size(max = 128)
    private String title;

    @Schema(description = "严重级别（BLOCKER/MAJOR/MINOR）")
    @Pattern(regexp = "^(BLOCKER|MAJOR|MINOR)$", message = "严重级别只能是 BLOCKER/MAJOR/MINOR")
    private String severity;

    @Schema(description = "合成问题（禁止真实客户数据）", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank
    @Size(max = 1024)
    private String question;

    @Schema(description = "期望的模型/服务版本标识（可空）")
    @Size(max = 64)
    private String expectVersion;

    @Schema(
            description = "期望规则（JSON 数组；词表见 docs/security/ai-eval-fixtures.md）",
            requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank
    private String checksJson;

    @Schema(description = "是否需要人工复核")
    private Boolean needsReview;
}

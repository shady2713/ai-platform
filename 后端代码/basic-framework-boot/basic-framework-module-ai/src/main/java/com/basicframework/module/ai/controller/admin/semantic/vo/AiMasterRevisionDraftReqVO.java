package com.basicframework.module.ai.controller.admin.semantic.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.LocalDateTime;
import lombok.Data;

/** 新草稿映射版本请求（协议层 VO，Y02）：版本有效期是判定条件，必须显式给出。 */
@Schema(description = "管理后台 - 新建映射版本草稿")
@Data
public class AiMasterRevisionDraftReqVO {

    @Schema(description = "统一对象编号", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull
    @Positive
    private Long masterObjectId;

    @Schema(description = "版本有效期起点（含）", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull
    private LocalDateTime validFrom;

    @Schema(description = "版本有效期终点（不含；为空表示长期有效）")
    private LocalDateTime validTo;
}

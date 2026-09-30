package com.basicframework.module.ai.controller.admin.semantic.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

/** 企业统一对象启停请求（协议层 VO，Y02）：停用后一切映射判定阻断。 */
@Schema(description = "管理后台 - 企业统一对象启停")
@Data
public class AiMasterObjectStatusReqVO {

    @Schema(description = "统一对象编号", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull
    @Positive
    private Long id;

    @Schema(description = "乐观锁版本", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull
    private Integer version;

    @Schema(description = "是否启用", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull
    private Boolean enabled;
}

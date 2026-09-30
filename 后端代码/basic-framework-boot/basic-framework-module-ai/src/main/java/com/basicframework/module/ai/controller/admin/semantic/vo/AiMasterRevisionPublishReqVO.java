package com.basicframework.module.ai.controller.admin.semantic.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

/** 发布映射版本请求（协议层 VO，Y02）：发布人由登录态决定，且必须不同于草稿创建人。 */
@Schema(description = "管理后台 - 发布映射版本")
@Data
public class AiMasterRevisionPublishReqVO {

    @Schema(description = "统一对象编号", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull
    @Positive
    private Long masterObjectId;

    @Schema(description = "映射版本号", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull
    @Positive
    private Long revisionNo;

    @Schema(description = "乐观锁版本", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull
    private Integer version;
}

package com.basicframework.module.ai.controller.admin.semantic.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 企业统一对象新建/修改请求（协议层 VO，Y02）：标识只在新建时生效且不可修改。 */
@Schema(description = "管理后台 - 企业统一对象新建/修改")
@Data
public class AiMasterObjectSaveReqVO {

    @Schema(description = "统一对象编号（修改时必填，新建时忽略）")
    private Long id;

    @Schema(description = "统一对象标识（新建必填；字母开头，字母数字与 _-，长度 3..64）")
    @Size(max = 64)
    private String objectCode;

    @Schema(description = "对象名称（仅展示，不参与判定）", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank
    @Size(max = 128)
    private String objectName;

    @Schema(
            description = "对象类型（CUSTOMER/SUPPLIER/PRODUCT/EMPLOYEE/ORGANIZATION/OTHER）",
            requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank
    @Size(max = 32)
    private String objectType;

    @Schema(description = "说明")
    @Size(max = 512)
    private String description;

    @Schema(description = "乐观锁版本（修改时必填）")
    private Integer version;
}

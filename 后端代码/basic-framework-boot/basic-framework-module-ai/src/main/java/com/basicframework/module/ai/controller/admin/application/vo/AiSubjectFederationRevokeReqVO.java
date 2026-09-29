package com.basicframework.module.ai.controller.admin.application.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

/** 联邦映射撤销请求（协议层 VO，Y01）：撤销立即生效，对已撤销映射幂等成功。 */
@Schema(description = "管理后台 - 跨系统主体联邦映射撤销")
@Data
public class AiSubjectFederationRevokeReqVO {

    @Schema(description = "映射编号", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull
    @Positive
    private Long id;

    @Schema(description = "乐观锁版本", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull
    @Positive
    private Integer version;
}

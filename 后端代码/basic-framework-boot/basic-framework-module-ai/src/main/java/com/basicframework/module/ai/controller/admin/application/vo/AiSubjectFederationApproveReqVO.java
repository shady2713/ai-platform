package com.basicframework.module.ai.controller.admin.application.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 联邦映射独立审批请求（协议层 VO，Y01）：批准人由登录态决定，请求体不能自报。 */
@Schema(description = "管理后台 - 跨系统主体联邦映射独立审批")
@Data
public class AiSubjectFederationApproveReqVO {

    @Schema(description = "映射编号", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull
    @Positive
    private Long id;

    @Schema(description = "乐观锁版本", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull
    @Positive
    private Integer version;

    @Schema(description = "审批说明（≤200 字符）")
    @Size(max = 200)
    private String approvalNote;
}

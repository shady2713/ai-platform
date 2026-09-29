package com.basicframework.module.ai.controller.admin.application.vo;

import com.basicframework.framework.common.pojo.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Positive;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 联邦映射分页查询（协议层 VO，Y01）。 */
@Schema(description = "管理后台 - 跨系统主体联邦映射分页查询")
@Data
@EqualsAndHashCode(callSuper = true)
public class AiSubjectFederationPageReqVO extends PageParam {

    @Schema(description = "来源应用编号")
    @Positive
    private Long sourceApplicationId;

    @Schema(description = "状态（PENDING/APPROVED/REVOKED）")
    private String status;
}

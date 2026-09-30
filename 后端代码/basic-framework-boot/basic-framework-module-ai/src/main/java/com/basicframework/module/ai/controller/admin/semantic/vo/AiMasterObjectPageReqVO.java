package com.basicframework.module.ai.controller.admin.semantic.vo;

import com.basicframework.framework.common.pojo.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 企业统一对象分页查询（协议层 VO，Y02）。 */
@Schema(description = "管理后台 - 企业统一对象分页查询")
@Data
@EqualsAndHashCode(callSuper = true)
public class AiMasterObjectPageReqVO extends PageParam {

    @Schema(description = "对象类型（CUSTOMER/SUPPLIER/PRODUCT/EMPLOYEE/ORGANIZATION/OTHER）")
    @Size(max = 32)
    private String objectType;

    @Schema(description = "状态（ACTIVE/DISABLED）")
    @Size(max = 16)
    private String status;

    @Schema(description = "关键字（匹配标识或名称）")
    @Size(max = 64)
    private String keyword;
}

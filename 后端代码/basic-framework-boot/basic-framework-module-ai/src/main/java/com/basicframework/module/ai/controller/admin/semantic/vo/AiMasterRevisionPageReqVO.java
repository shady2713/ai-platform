package com.basicframework.module.ai.controller.admin.semantic.vo;

import com.basicframework.framework.common.pojo.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 映射版本分页查询（协议层 VO，Y02）。 */
@Schema(description = "管理后台 - 映射版本分页查询")
@Data
@EqualsAndHashCode(callSuper = true)
public class AiMasterRevisionPageReqVO extends PageParam {

    @Schema(description = "统一对象编号")
    @Positive
    private Long masterObjectId;

    @Schema(description = "状态（DRAFT/PUBLISHED）")
    @Size(max = 16)
    private String status;
}

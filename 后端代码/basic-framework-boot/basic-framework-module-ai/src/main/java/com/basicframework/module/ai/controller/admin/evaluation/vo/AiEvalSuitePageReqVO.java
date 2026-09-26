package com.basicframework.module.ai.controller.admin.evaluation.vo;

import com.basicframework.framework.common.pojo.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import lombok.experimental.Accessors;

/** 评测套件分页（Q04）。 */
@Schema(description = "管理后台 - 评测套件分页")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Accessors(chain = true)
public class AiEvalSuitePageReqVO extends PageParam {

    @Schema(description = "应用编号")
    private Long applicationId;

    @Schema(description = "状态（DRAFT/FROZEN）")
    private String status;
}

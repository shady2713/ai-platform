package com.basicframework.module.ai.controller.admin.evaluation.vo;

import com.basicframework.framework.common.pojo.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import lombok.experimental.Accessors;

/** 评测运行分页（Q04）。 */
@Schema(description = "管理后台 - 评测运行分页")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Accessors(chain = true)
public class AiEvalRunPageReqVO extends PageParam {

    @Schema(description = "套件编号（不传表示全部）")
    private Long suiteId;
}

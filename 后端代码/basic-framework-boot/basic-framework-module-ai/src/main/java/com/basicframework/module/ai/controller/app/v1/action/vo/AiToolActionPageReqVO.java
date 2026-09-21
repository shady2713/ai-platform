package com.basicframework.module.ai.controller.app.v1.action.vo;

import com.basicframework.framework.common.pojo.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 工具动作分页查询（协议层 VO）。 */
@Schema(description = "AI 应用端 - 工具动作分页查询")
@Data
@EqualsAndHashCode(callSuper = true)
public class AiToolActionPageReqVO extends PageParam {

    @Schema(description = "运行编号（可选）")
    private Long runId;
}

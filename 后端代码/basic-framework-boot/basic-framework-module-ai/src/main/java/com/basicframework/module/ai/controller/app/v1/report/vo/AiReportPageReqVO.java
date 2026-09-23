package com.basicframework.module.ai.controller.app.v1.report.vo;

import com.basicframework.framework.common.pojo.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 报表分页查询（应用端协议层 VO）：只按当前主体过滤，请求体不提供归属条件。 */
@Schema(description = "AI 应用端 - 报表分页查询")
@Data
@EqualsAndHashCode(callSuper = true)
public class AiReportPageReqVO extends PageParam {

    @Schema(description = "模式过滤（可选）：SNAPSHOT / REFRESHABLE")
    private String mode;
}

package com.basicframework.module.ai.controller.app.v1.report.vo;

import com.basicframework.framework.common.pojo.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 报表分享分页查询（应用端协议层 VO）：只返回当前授予者的分享，请求体不提供归属条件。 */
@Schema(description = "AI 应用端 - 报表分享分页查询")
@Data
@EqualsAndHashCode(callSuper = true)
public class AiReportSharePageReqVO extends PageParam {}

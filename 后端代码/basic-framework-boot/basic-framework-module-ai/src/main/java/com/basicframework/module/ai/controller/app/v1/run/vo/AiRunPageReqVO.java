package com.basicframework.module.ai.controller.app.v1.run.vo;

import com.basicframework.framework.common.pojo.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 运行分页查询（应用端协议层 VO）：只按当前主体过滤，请求体不提供归属条件。 */
@Schema(description = "应用端 - AI 运行分页查询")
@Data
@EqualsAndHashCode(callSuper = true)
public class AiRunPageReqVO extends PageParam {}

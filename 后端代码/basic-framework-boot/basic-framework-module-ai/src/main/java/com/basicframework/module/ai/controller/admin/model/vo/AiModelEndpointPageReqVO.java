package com.basicframework.module.ai.controller.admin.model.vo;

import com.basicframework.framework.common.pojo.PageParam;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

@Schema(description = "模型端点分页请求")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class AiModelEndpointPageReqVO extends PageParam {

    @Schema(description = "端点名称（模糊匹配）")
    private String name;

    @Schema(description = "提供方标识（精确匹配）")
    private String provider;
}

package com.basicframework.module.ai.controller.admin.model.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.util.List;
import lombok.Data;

/** 不可变配置版本响应：只包含非秘密配置，永远不含任何凭据信息。 */
@Data
public class AiModelEndpointRevisionRespVO {

    @Schema(description = "版本号")
    private Integer revision;

    @Schema(description = "模型标识")
    private String modelId;

    @Schema(description = "能力集合")
    private List<String> capabilities;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;
}

package com.basicframework.module.ai.controller.admin.serviceconfig.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import lombok.Data;
import lombok.experimental.Accessors;

/** 能力校验响应（协议层 VO）：缺失能力一目了然。 */
@Schema(description = "管理后台 - 服务能力校验结果")
@Data
@Accessors(chain = true)
public class AiServiceCapabilityRespVO {

    @Schema(description = "服务所需能力")
    private List<String> required;

    @Schema(description = "端点探测确认可用的能力")
    private List<String> publishable;

    @Schema(description = "缺失能力")
    private List<String> missing;

    @Schema(description = "是否满足发布条件")
    private boolean satisfied;
}

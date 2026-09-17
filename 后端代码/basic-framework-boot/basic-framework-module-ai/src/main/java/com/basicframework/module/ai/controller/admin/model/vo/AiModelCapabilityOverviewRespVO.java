package com.basicframework.module.ai.controller.admin.model.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import lombok.Data;
import lombok.experimental.Accessors;

/** 能力总览响应（协议层 VO）：声明、探测确认与可发布范围。 */
@Schema(description = "管理后台 - 模型能力总览")
@Data
@Accessors(chain = true)
public class AiModelCapabilityOverviewRespVO {

    @Schema(description = "端点编号", example = "1")
    private Long endpointId;

    @Schema(description = "端点声明的能力（当前配置版本）")
    private List<String> declared;

    @Schema(description = "探测确认为可用的能力")
    private List<String> supported;

    @Schema(description = "可发布范围：声明与确认的交集")
    private List<String> publishable;
}

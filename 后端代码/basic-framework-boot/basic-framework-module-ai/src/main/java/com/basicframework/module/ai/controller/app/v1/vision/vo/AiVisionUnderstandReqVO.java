package com.basicframework.module.ai.controller.app.v1.vision.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.experimental.Accessors;

/** 应用端 - 图片理解请求（X02）。 */
@Schema(description = "应用端 - 图片理解请求")
@Data
@Accessors(chain = true)
public class AiVisionUnderstandReqVO {

    @Schema(description = "模型端点编号（显式指定，平台不做候选遍历）", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "端点编号不能为空")
    @Positive(message = "端点编号必须为正数")
    private Long endpointId;

    @Schema(description = "受控图片引用", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "图片引用不能为空")
    @Valid
    private AiVisionImageReqVO image;

    @Schema(description = "理解指令", requiredMode = Schema.RequiredMode.REQUIRED, example = "描述图片中的单据要点")
    @NotBlank(message = "理解指令不能为空")
    @Size(max = 4000, message = "理解指令长度不能超过 4000")
    private String instruction;

    @Schema(description = "单次调用超时（毫秒）；为空使用端点默认值", example = "60000")
    @Positive(message = "超时必须为正数")
    private Long timeoutMillis;
}

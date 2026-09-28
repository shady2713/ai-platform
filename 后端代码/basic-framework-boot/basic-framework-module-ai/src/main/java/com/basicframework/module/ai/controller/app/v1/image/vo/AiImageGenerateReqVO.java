package com.basicframework.module.ai.controller.app.v1.image.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;
import lombok.experimental.Accessors;

/** 图片生成请求（协议层，X03）。 */
@Data
@Accessors(chain = true)
@Schema(description = "图片生成请求")
public class AiImageGenerateReqVO {

    @Schema(description = "幂等键（同一主体重复提交同一键只受理一次）", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "幂等键不能为空")
    private String requestKey;

    @Schema(description = "模型端点编号（能力必须已声明且探测确认）", requiredMode = Schema.RequiredMode.REQUIRED, example = "7")
    @NotNull(message = "端点编号不能为空")
    @Positive(message = "端点编号必须为正数")
    private Long endpointId;

    @Schema(description = "生成提示词", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "提示词不能为空")
    private String prompt;

    @Schema(description = "目标尺寸（宽x高，平台允许的档位；为空由端点默认值决定）", example = "1024x1024")
    private String size;

    @Schema(description = "生成张数（1-8；为空按 1 张）", example = "1")
    private Integer count;

    @Schema(description = "输出格式（png/jpeg/webp；为空按 png）", example = "png")
    private String outputFormat;
}

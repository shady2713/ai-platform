package com.basicframework.module.ai.controller.app.v1.image.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;
import lombok.experimental.Accessors;

/** 图片编辑请求（协议层，X03）：底图必须是调用主体有权读取的私有文件。 */
@Data
@Accessors(chain = true)
@Schema(description = "图片编辑请求")
public class AiImageEditReqVO {

    @Schema(description = "幂等键", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "幂等键不能为空")
    private String requestKey;

    @Schema(description = "模型端点编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "7")
    @NotNull(message = "端点编号不能为空")
    @Positive(message = "端点编号必须为正数")
    private Long endpointId;

    @Schema(description = "编辑指令（例如：去掉水印并保持分辨率）", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "编辑指令不能为空")
    private String instruction;

    @Schema(description = "底图引用（私有文件编号 + 声明级元数据）", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "底图不能为空")
    @Valid
    private AiImageMediaRefVO image;

    @Schema(description = "目标尺寸（宽x高；为空沿用底图尺寸）", example = "1024x1024")
    private String size;

    @Schema(description = "输出格式（png/jpeg/webp；为空按 png）", example = "png")
    private String outputFormat;
}

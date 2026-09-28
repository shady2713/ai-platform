package com.basicframework.module.ai.controller.app.v1.speech.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;
import lombok.experimental.Accessors;

/** 语音合成请求（协议层，X04）：文本 + 受控的可选参数（音色/输出格式）。 */
@Data
@Accessors(chain = true)
@Schema(description = "语音合成请求（TTS）")
public class AiSpeechSynthesizeReqVO {

    @Schema(description = "幂等键（同一主体重复提交同一键只受理一次）", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "幂等键不能为空")
    private String requestKey;

    @Schema(description = "模型端点编号（能力必须已声明且探测确认）", requiredMode = Schema.RequiredMode.REQUIRED, example = "7")
    @NotNull(message = "端点编号不能为空")
    @Positive(message = "端点编号必须为正数")
    private Long endpointId;

    @Schema(description = "待合成文本（非空，最长 4096 码元）", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "合成文本不能为空")
    private String text;

    @Schema(description = "音色标识（字母开头的短标识；为空表示端点默认音色，具体音色表由端点声明）", example = "Alloy")
    private String voice;

    @Schema(description = "输出格式（mp3/wav/opus；为空按 mp3）", example = "mp3")
    private String outputFormat;
}

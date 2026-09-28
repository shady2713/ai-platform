package com.basicframework.module.ai.controller.app.v1.speech.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;
import lombok.experimental.Accessors;

/** 语音转写请求（协议层，X04）：待转写音频必须是调用主体有权读取的私有文件。 */
@Data
@Accessors(chain = true)
@Schema(description = "语音转写请求（STT）")
public class AiSpeechTranscribeReqVO {

    @Schema(description = "幂等键（同一主体重复提交同一键只受理一次）", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "幂等键不能为空")
    private String requestKey;

    @Schema(description = "模型端点编号（能力必须已声明且探测确认）", requiredMode = Schema.RequiredMode.REQUIRED, example = "7")
    @NotNull(message = "端点编号不能为空")
    @Positive(message = "端点编号必须为正数")
    private Long endpointId;

    @Schema(description = "待转写音频引用（私有文件编号 + 声明级元数据）", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "音频不能为空")
    @Valid
    private AiSpeechAudioRefVO audio;

    @Schema(description = "语言提示（冻结集合：zh-CN/zh-TW/en-US/ja-JP；为空由端点自行识别）", example = "zh-CN")
    private String languageHint;
}

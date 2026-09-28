package com.basicframework.module.ai.controller.app.v1.speech.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 私有音频引用（协议层）：只有文件编号与声明级元数据，**没有地址字段**。
 *
 * <p>字段与 X01 冻结的客户端契约 {@code audioInputSchema} 一致：时长是必填的声明值
 * （平台单段上限 300 秒），服务端在受理与执行两次核验声明与内容的一致性。
 */
@Data
@Accessors(chain = true)
@Schema(description = "私有音频引用（文件编号 + 声明级元数据）")
public class AiSpeechAudioRefVO {

    @Schema(description = "平台私有文件编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1024")
    @NotNull(message = "文件编号不能为空")
    @Positive(message = "文件编号必须为正数")
    private Long fileId;

    @Schema(
            description = "声明 MIME（白名单内取值：audio/mpeg、audio/mp4、audio/ogg、audio/wav、audio/webm）",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "audio/wav")
    @NotBlank(message = "MIME 不能为空")
    private String mime;

    @Schema(description = "声明字节数", requiredMode = Schema.RequiredMode.REQUIRED, example = "20480")
    @NotNull(message = "字节数不能为空")
    @Positive(message = "字节数必须为正数")
    private Long size;

    @Schema(description = "声明摘要（可选；非空必须与真实内容一致）")
    private String sha256;

    @Schema(description = "声明时长（毫秒；必填，单段上限 300 秒）", requiredMode = Schema.RequiredMode.REQUIRED, example = "8000")
    @NotNull(message = "时长不能为空")
    @Positive(message = "时长必须为正数")
    private Long durationMs;
}

package com.basicframework.module.ai.controller.app.v1.vision.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 应用端 - 图片输入引用（X02）：只给平台私有文件标识与声明元数据，**没有 URL 字段**。
 *
 * <p>声明值不被采信：服务端按 A07 读取真实字节后核对字节数、文件头与摘要。
 */
@Schema(description = "应用端 - 图片输入引用（私有文件）")
@Data
@Accessors(chain = true)
public class AiVisionImageReqVO {

    @Schema(description = "平台私有文件编号", requiredMode = Schema.RequiredMode.REQUIRED, example = "1024")
    @NotNull(message = "文件编号不能为空")
    @Positive(message = "文件编号必须为正数")
    private Long fileId;

    @Schema(
            description = "图片 MIME 类型（image/png、image/jpeg、image/webp）",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "image/png")
    @NotBlank(message = "图片 MIME 类型不能为空")
    private String mime;

    @Schema(description = "声明字节数", requiredMode = Schema.RequiredMode.REQUIRED, example = "20480")
    @NotNull(message = "声明字节数不能为空")
    @Positive(message = "声明字节数必须为正数")
    private Long size;

    @Schema(description = "声明的 SHA-256 摘要（可选；非空时必须与真实内容一致）")
    @Pattern(regexp = "^[0-9a-f]{64}$", message = "摘要必须是 64 位小写十六进制 SHA-256")
    private String sha256;
}

package com.basicframework.framework.ai.core.model.media;

import java.time.Duration;

/**
 * 图片编辑请求（X01 冻结，含图生图）：以平台私有图片为底，按指令生成新图片。
 *
 * <p>输入与输出都不接受厂商临时地址：底图用 {@link MediaFileRef}，产物由调用方落私有文件。
 *
 * @param modelId      模型标识（非空）
 * @param source       平台私有底图引用
 * @param instruction  编辑指令（非空，例如"去掉水印并保持分辨率"）
 * @param size         目标尺寸（{@code 宽x高}）；为空表示沿用底图尺寸（由端点决定）
 * @param outputFormat 输出格式（为空按 {@code png}）
 * @param timeout      单次调用超时；为空表示使用端点默认值
 */
public record ImageEditRequest(
        String modelId, MediaFileRef source, String instruction, String size, String outputFormat, Duration timeout) {

    public ImageEditRequest {
        modelId = MediaValues.requireText(modelId, "模型标识");
        if (source == null) {
            throw new IllegalArgumentException("底图引用不能为空");
        }
        instruction = MediaValues.requireText(instruction, "编辑指令");
        size = MediaValues.normalizeImageSize(size);
        outputFormat =
                MediaValues.normalizeOutputFormat(outputFormat, "png", ImageGenerationRequest.OUTPUT_FORMATS, "输出格式");
    }

    /** 构造只给底图与指令的请求（尺寸沿用底图，格式 png，超时用端点默认值）。 */
    public static ImageEditRequest of(String modelId, MediaFileRef source, String instruction) {
        return new ImageEditRequest(modelId, source, instruction, null, null, null);
    }
}

package com.basicframework.framework.ai.core.model.media;

import java.time.Duration;
import java.util.Set;

/**
 * 图片生成请求（X01 冻结，文生图）。
 *
 * <p>尺寸/张数/格式先做平台级硬校验（语法、1-8192 像素、1-8 张、png/jpeg/webp）；
 * 端点声明的范围可以更窄，是否接受由端点声明判定，超范围按
 * {@code MEDIA_INPUT_INVALID} 拒绝且不外发。
 *
 * @param modelId      模型标识（非空）
 * @param prompt       生成提示词（非空）
 * @param size         目标尺寸（{@code 宽x高}，如 {@code 1024x1024}）；为空表示由端点默认值决定
 * @param count        生成张数（为空按 1 张）
 * @param outputFormat 输出格式（为空按 {@code png}）
 * @param timeout      单次调用超时；为空表示使用端点默认值
 */
public record ImageGenerationRequest(
        String modelId, String prompt, String size, Integer count, String outputFormat, Duration timeout) {

    /** 平台单次生成张数上限（端点声明可更窄）。 */
    public static final int MAX_COUNT = 8;

    /** 平台支持的输出格式（端点声明可更窄）。 */
    public static final Set<String> OUTPUT_FORMATS = Set.of("png", "jpeg", "webp");

    public ImageGenerationRequest {
        modelId = MediaValues.requireText(modelId, "模型标识");
        prompt = MediaValues.requireText(prompt, "生成提示词");
        size = MediaValues.normalizeImageSize(size);
        count = count == null ? 1 : count;
        if (count < 1 || count > MAX_COUNT) {
            throw new IllegalArgumentException("生成张数必须在 1-" + MAX_COUNT + " 之间");
        }
        outputFormat = MediaValues.normalizeOutputFormat(outputFormat, "png", OUTPUT_FORMATS, "输出格式");
    }

    /** 构造只给模型与提示词的请求（其余取平台默认值，超时用端点默认值）。 */
    public static ImageGenerationRequest of(String modelId, String prompt) {
        return new ImageGenerationRequest(modelId, prompt, null, null, null, null);
    }
}

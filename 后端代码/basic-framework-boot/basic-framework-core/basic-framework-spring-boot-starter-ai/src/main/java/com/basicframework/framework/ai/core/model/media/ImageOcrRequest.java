package com.basicframework.framework.ai.core.model.media;

import java.time.Duration;

/**
 * 图片文字识别请求（X01 冻结）：与图片理解分开的能力（FR-35 要求分开探测）。
 *
 * @param modelId      模型标识（非空）
 * @param image        平台私有图片引用
 * @param languageHint 语言提示（如 {@code zh} / {@code en}）；为空表示由端点自行识别
 * @param timeout      单次调用超时；为空表示使用端点默认值
 */
public record ImageOcrRequest(String modelId, MediaFileRef image, String languageHint, Duration timeout) {

    public ImageOcrRequest {
        modelId = MediaValues.requireText(modelId, "模型标识");
        if (image == null) {
            throw new IllegalArgumentException("图片引用不能为空");
        }
        languageHint = MediaValues.optionalText(languageHint);
    }

    /** 构造不带语言提示、使用端点默认超时的请求。 */
    public static ImageOcrRequest of(String modelId, MediaFileRef image) {
        return new ImageOcrRequest(modelId, image, null, null);
    }
}

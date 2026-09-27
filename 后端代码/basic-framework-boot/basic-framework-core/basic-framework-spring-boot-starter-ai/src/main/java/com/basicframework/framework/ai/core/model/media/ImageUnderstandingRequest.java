package com.basicframework.framework.ai.core.model.media;

import java.time.Duration;

/**
 * 图片理解请求（X01 冻结）：输入是平台私有图片引用，输出是文本。
 *
 * <p>与 OCR 分开：理解用于描述/问答，不承诺逐字识别；需要文字时用
 * {@link ImageOcrRequest}，两者在端点上分别声明、分别探测。
 *
 * @param modelId     模型标识（非空；具体取哪个模型由端点配置决定）
 * @param image       平台私有图片引用
 * @param instruction 理解指令（非空，例如"描述图片中的业务流程"）
 * @param timeout     单次调用超时；为空表示使用端点默认值
 */
public record ImageUnderstandingRequest(String modelId, MediaFileRef image, String instruction, Duration timeout) {

    public ImageUnderstandingRequest {
        modelId = MediaValues.requireText(modelId, "模型标识");
        if (image == null) {
            throw new IllegalArgumentException("图片引用不能为空");
        }
        instruction = MediaValues.requireText(instruction, "理解指令");
    }

    /** 构造使用端点默认超时的请求。 */
    public static ImageUnderstandingRequest of(String modelId, MediaFileRef image, String instruction) {
        return new ImageUnderstandingRequest(modelId, image, instruction, null);
    }
}

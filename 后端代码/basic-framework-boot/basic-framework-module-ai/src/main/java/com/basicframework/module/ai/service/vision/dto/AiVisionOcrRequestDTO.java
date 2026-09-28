package com.basicframework.module.ai.service.vision.dto;

import lombok.Data;
import lombok.experimental.Accessors;

/** 单张图片 OCR 请求（X02）：显式端点 + 受控图片引用 + 可选语言提示。 */
@Data
@Accessors(chain = true)
public class AiVisionOcrRequestDTO {

    /** 模型端点编号（调用方显式指定，不做候选遍历） */
    private Long endpointId;

    /** 受控图片引用 */
    private AiVisionImageRefDTO image;

    /** 语言提示（如 zh-CN）；为空表示由端点自行识别 */
    private String languageHint;

    /** 单次调用超时（毫秒）；为空表示使用端点默认值 */
    private Long timeoutMillis;
}

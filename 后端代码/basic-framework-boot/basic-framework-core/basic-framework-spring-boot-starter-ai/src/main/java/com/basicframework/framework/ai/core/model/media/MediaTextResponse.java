package com.basicframework.framework.ai.core.model.media;

import com.basicframework.framework.ai.core.model.ModelUsage;

/**
 * 媒体文本响应（X01 冻结）：图片理解与 OCR 的共用输出。
 *
 * <p>文本必须非空：空文本属于上游协议异常，适配器应抛 {@code MEDIA_OUTPUT_EMPTY}，
 * 不得把空结果当成功交付。用量缺失记 {@link ModelUsage#UNKNOWN}，不伪造 0。
 *
 * @param text         模型返回的文本
 * @param usage        用量
 * @param modelId      实际使用的模型标识
 * @param finishReason 结束原因（厂商原始值的稳定映射，可为空）
 */
public record MediaTextResponse(String text, ModelUsage usage, String modelId, String finishReason) {

    public MediaTextResponse {
        text = MediaValues.requireText(text, "媒体文本输出");
        usage = usage == null ? ModelUsage.UNKNOWN : usage;
    }
}

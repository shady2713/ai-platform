package com.basicframework.framework.ai.core.model.media;

import com.basicframework.framework.ai.core.model.ModelUsage;

/**
 * 语音合成响应（X01 冻结）：音频产物 + 用量。
 *
 * <p>产物由调用方落平台私有文件后引用；不返回厂商临时下载地址，也不在本类型里持久化。
 *
 * @param audio   音频产物（非空）
 * @param usage   用量；缺失时为 {@link ModelUsage#UNKNOWN}
 * @param modelId 实际使用的模型标识
 */
public record SpeechSynthesisResponse(MediaArtifact audio, ModelUsage usage, String modelId) {

    public SpeechSynthesisResponse {
        if (audio == null) {
            throw new IllegalArgumentException("音频产物不能为空");
        }
        usage = usage == null ? ModelUsage.UNKNOWN : usage;
    }
}

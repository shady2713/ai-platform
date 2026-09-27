package com.basicframework.framework.ai.core.model.media;

import java.time.Duration;

/**
 * 语音转写请求（X01 冻结，非实时 STT）。
 *
 * <p>实时语音（FR-37）不在本契约内：会话协商与短期凭证需要独立 ADR 与通道设计。
 *
 * @param modelId      模型标识（非空）
 * @param audio        平台私有音频引用（时长上限由端点声明判定）
 * @param languageHint 语言提示（如 {@code zh}）；为空表示由端点自行识别
 * @param timeout      单次调用超时；为空表示使用端点默认值
 */
public record SpeechTranscriptionRequest(String modelId, MediaFileRef audio, String languageHint, Duration timeout) {

    public SpeechTranscriptionRequest {
        modelId = MediaValues.requireText(modelId, "模型标识");
        if (audio == null) {
            throw new IllegalArgumentException("音频引用不能为空");
        }
        languageHint = MediaValues.optionalText(languageHint);
    }

    /** 构造不带语言提示、使用端点默认超时的请求。 */
    public static SpeechTranscriptionRequest of(String modelId, MediaFileRef audio) {
        return new SpeechTranscriptionRequest(modelId, audio, null, null);
    }
}

package com.basicframework.framework.ai.core.model.media;

import java.time.Duration;
import java.util.Set;

/**
 * 语音合成请求（X01 冻结，非实时 TTS）。
 *
 * <p>文本长度先做平台级硬校验（1-4096 码元），端点声明的上限可以更窄；
 * 音色名由端点声明的音色表决定，本类型只拒绝空白值，不内置厂商音色。
 *
 * @param modelId      模型标识（非空）
 * @param text         待合成文本（非空，最长 4096 码元）
 * @param voice        音色标识；为空表示端点默认音色
 * @param outputFormat 输出格式（为空按 {@code mp3}；支持 mp3/wav/opus）
 * @param timeout      单次调用超时；为空表示使用端点默认值
 */
public record SpeechSynthesisRequest(String modelId, String text, String voice, String outputFormat, Duration timeout) {

    /** 平台单次合成文本上限（UTF-16 码元；端点声明可更窄）。 */
    public static final int MAX_TEXT_LENGTH = 4096;

    /** 平台支持的音频输出格式（端点声明可更窄）。 */
    public static final Set<String> OUTPUT_FORMATS = Set.of("mp3", "wav", "opus");

    public SpeechSynthesisRequest {
        modelId = MediaValues.requireText(modelId, "模型标识");
        text = MediaValues.requireMaxLength(MediaValues.requireText(text, "合成文本"), MAX_TEXT_LENGTH, "合成文本");
        voice = MediaValues.optionalText(voice);
        outputFormat = MediaValues.normalizeOutputFormat(outputFormat, "mp3", OUTPUT_FORMATS, "输出格式");
    }

    /** 构造只给模型与文本的请求（默认音色与格式，超时用端点默认值）。 */
    public static SpeechSynthesisRequest of(String modelId, String text) {
        return new SpeechSynthesisRequest(modelId, text, null, null, null);
    }
}

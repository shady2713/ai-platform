package com.basicframework.framework.ai.core.model.media;

import com.basicframework.framework.ai.core.model.ModelUsage;
import java.util.List;

/**
 * 语音转写响应（X01 冻结）：全文 + 可选分段字幕。
 *
 * <p>全文必须非空：空转写属于上游协议异常，适配器应抛 {@code MEDIA_OUTPUT_EMPTY}。
 * 分段可以为空（端点不支持分段时只给全文）；分段按上游返回顺序保留。
 *
 * @param text    转写全文
 * @param segments 分段字幕（可为空列表）
 * @param usage   用量；缺失时为 {@link ModelUsage#UNKNOWN}
 * @param modelId 实际使用的模型标识
 */
public record SpeechTranscriptionResponse(String text, List<SpeechSegment> segments, ModelUsage usage, String modelId) {

    public SpeechTranscriptionResponse {
        text = MediaValues.requireText(text, "转写文本");
        segments = segments == null ? List.of() : List.copyOf(segments);
        usage = usage == null ? ModelUsage.UNKNOWN : usage;
    }

    /** 只有全文、没有分段字幕的常用构造。 */
    public static SpeechTranscriptionResponse of(String text, ModelUsage usage, String modelId) {
        return new SpeechTranscriptionResponse(text, null, usage, modelId);
    }
}

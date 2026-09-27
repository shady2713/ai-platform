package com.basicframework.framework.ai.core.model.media;

/**
 * 语音转写分段（X01 冻结）：字幕/引用所需的稳定时间片。
 *
 * @param text        该分段的文本（非空）
 * @param startMillis 相对音频起点的开始时间（毫秒，非负）
 * @param endMillis   相对音频起点的结束时间（毫秒，不早于开始时间）
 */
public record SpeechSegment(String text, long startMillis, long endMillis) {

    public SpeechSegment {
        text = MediaValues.requireText(text, "分段文本");
        if (startMillis < 0) {
            throw new IllegalArgumentException("分段开始时间不能为负数");
        }
        if (endMillis < startMillis) {
            throw new IllegalArgumentException("分段结束时间不能早于开始时间");
        }
    }

    /** 分段时长（毫秒）。 */
    public long durationMillis() {
        return endMillis - startMillis;
    }
}

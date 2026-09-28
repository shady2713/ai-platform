package com.basicframework.module.ai.service.vision;

/**
 * OCR 置信度来源（X02）：说明"这个置信度是谁给的"，没有就明确说没有。
 *
 * <p>为什么不设"平台估算"：置信度是对单次识别质量的声明，平台没有独立证据去估算它；
 * 编造一个看起来合理的数字会让下游误以为识别质量已被验证。
 */
public enum AiVisionConfidenceSource {

    /** 上游逐区域返回了置信度（0..1），平台原样透传。 */
    PROVIDER,

    /** 上游没有返回置信度：平台不给任何数值，展示方必须按"未核验"呈现。 */
    UNKNOWN
}

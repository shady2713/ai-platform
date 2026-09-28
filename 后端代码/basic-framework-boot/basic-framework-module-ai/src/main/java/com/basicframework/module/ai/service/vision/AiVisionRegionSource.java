package com.basicframework.module.ai.service.vision;

/**
 * OCR 识别范围来源（X02）：说明"这段文本覆盖图片的哪一块"是谁判定的。
 *
 * <p>X01 冻结的端口契约（`MediaTextResponse`）只返回文本、不返回逐块坐标，
 * 所以平台当前只能给出**整页**归因；适配层将来能返回逐块结果时改为 {@link #PROVIDER}，
 * 不改变结果字段形状，也不把"整页"冒充成"逐块定位"。
 */
public enum AiVisionRegionSource {

    /** 上游逐块返回了识别区域（归一化坐标）。 */
    PROVIDER,

    /** 平台按整页归因（当前端口契约下的唯一诚实取值）。 */
    WHOLE_PAGE
}

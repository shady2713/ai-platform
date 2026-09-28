package com.basicframework.module.ai.service.document;

import com.basicframework.module.ai.service.vision.AiVisionConfidenceSource;

/**
 * 一页 OCR 识别结果（X02）：重索引的输入单元。
 *
 * <p>页码是引用可核验的前提（AT-026）：检索命中后要能回到"第几页"。
 * 置信度来源一并带入：派生文档里必须写明"置信度是上游给的"还是"没有"，
 * 不能让读者以为识别质量已被验证。
 *
 * @param page             页码（从 1 开始）
 * @param text             该页识别文本（空页允许为空串：没有文字就是没有文字）
 * @param confidenceSource 该页置信度来源
 */
public record AiOcrPage(int page, String text, AiVisionConfidenceSource confidenceSource) {

    public AiOcrPage {
        if (page < 1) {
            throw new IllegalArgumentException("页码必须从 1 开始");
        }
        text = text == null ? "" : text.trim();
        confidenceSource = confidenceSource == null ? AiVisionConfidenceSource.UNKNOWN : confidenceSource;
    }

    /** 该页是否有可用正文。 */
    public boolean hasText() {
        return !text.isEmpty();
    }
}

package com.basicframework.module.ai.adapter.document;

import java.util.List;

/**
 * 解析结果（K04）：格式、正文段、字符数与"是否需要 OCR"。
 *
 * <p>三类结论必须分清：
 * <ul>
 *   <li><b>有正文</b>：{@code segments} 非空，{@code ocrRequired=false}；</li>
 *   <li><b>需要 OCR</b>：扫描件 PDF（没有可抽取的文本层）——{@code segments} 为空且 {@code ocrRequired=true}，
 *       **不生成任何占位正文**（生成"看起来有内容"的假正文会让检索与引用同时失真）；</li>
 *   <li><b>失败</b>：抛 {@link DocumentParseException}（稳定原因码），不返回半成品。</li>
 * </ul>
 */
public record ParsedDocument(String format, List<ParsedSegment> segments, int characterCount, boolean ocrRequired) {

    public ParsedDocument {
        segments = segments == null ? List.of() : List.copyOf(segments);
    }

    /** 是否需要 OCR（扫描件提示）。 */
    public boolean requiresOcr() {
        return ocrRequired;
    }
}

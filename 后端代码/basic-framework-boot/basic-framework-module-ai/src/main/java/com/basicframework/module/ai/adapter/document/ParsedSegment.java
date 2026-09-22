package com.basicframework.module.ai.adapter.document;

/**
 * 正文段（K04）：一段可引用的正文 + 它在原文中的位置。
 *
 * <p>位置是引用可核验的前提（AT-026）：检索命中后要能回到"第几页/第几段"。
 * 位置用**人类可读的稳定标识**（`第 3 页`、`段落 12`），不落二进制偏移——
 * 偏移在不同解析器版本间不稳定，引用会因此对不上。
 */
public record ParsedSegment(int index, String locationRef, String text) {

    public ParsedSegment {
        text = text == null ? "" : text;
    }
}

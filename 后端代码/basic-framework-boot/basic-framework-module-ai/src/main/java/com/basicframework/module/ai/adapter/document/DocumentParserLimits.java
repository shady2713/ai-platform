package com.basicframework.module.ai.adapter.document;

/**
 * 解析上限（K04）：默认值保守，可由配置覆盖（构造器注入，不做字段注入）。
 *
 * <p>为什么字符上限远小于文件上限：32MB 的纯文本可能有上千万字符，全部进内存再切分会让一次入库
 * 吃掉整个进程；解析阶段就按"能进上下文的量级"设上限，超出的文档按失败处理（提示人工拆分），
 * 而不是静默截断成"看起来完整"的正文。
 */
public record DocumentParserLimits(int maxCharacters, int maxSegments, int maxPages, long timeoutMillis) {

    /** 默认上限：20 万字符、500 段、200 页、10 秒。 */
    public static final DocumentParserLimits DEFAULTS = new DocumentParserLimits(200_000, 500, 200, 10_000);

    public DocumentParserLimits {
        if (maxCharacters <= 0 || maxSegments <= 0 || maxPages <= 0 || timeoutMillis <= 0) {
            throw new IllegalArgumentException("解析上限必须为正数");
        }
    }
}

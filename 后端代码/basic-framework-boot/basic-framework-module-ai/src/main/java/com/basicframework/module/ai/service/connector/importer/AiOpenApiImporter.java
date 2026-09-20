package com.basicframework.module.ai.service.connector.importer;

import com.basicframework.module.ai.service.connector.importer.dto.AiOpenApiImportResultDTO;

/**
 * OpenAPI 导入器（D02）：把 OpenAPI 3.x 文档中的操作导入为**声明式草稿**。
 *
 * <p>限定范围（超出一律跳过并记录原因，不静默丢弃）：
 * <ul>
 *   <li>只接受 {@code paths} 下的 {@code GET}/{@code POST}；</li>
 *   <li>只解析**本地** {@code $ref}（{@code #/components/...}）；外部引用（http/file）不抓取，直接跳过该操作；</li>
 *   <li>只导入 query/path/body 参数：**header 参数不导入**（请求头只能来自连接器自身配置，模型与调用方不得替换）；</li>
 *   <li>文档大小、操作数量、参数数量都有上限；导入结果是草稿，发布后才可执行。</li>
 * </ul>
 */
public interface AiOpenApiImporter {

    /** 解析 OpenAPI 文档文本，返回操作草稿与被跳过项。 */
    AiOpenApiImportResultDTO importDocument(String documentJson);
}

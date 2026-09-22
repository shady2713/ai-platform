package com.basicframework.module.ai.service.knowledge.indexing;

import com.basicframework.module.ai.adapter.document.DocumentParser;
import com.basicframework.module.ai.adapter.document.RestrictedDocumentParser;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 索引流水线的装配（K05）：解析器与切片器都是**无状态纯函数**，按默认上限装配成单例。
 *
 * <p>放在本包而不是适配器包：装配是流水线的职责，适配器只提供能力（K04 的解析器保持无 Spring 依赖，
 * 便于在单测里直接构造）。
 */
@Configuration(proxyBeanMethods = false)
public class AiKnowledgeIndexingConfiguration {

    /** 受限解析器（K04）：默认上限（20 万字符/500 段/200 页/10 秒）。 */
    @Bean
    public DocumentParser documentParser() {
        return new RestrictedDocumentParser();
    }

    /** 切片器（K05）：固定 chunker 版本与默认窗口。 */
    @Bean
    public AiKnowledgeChunker aiKnowledgeChunker() {
        return new AiKnowledgeChunker();
    }
}

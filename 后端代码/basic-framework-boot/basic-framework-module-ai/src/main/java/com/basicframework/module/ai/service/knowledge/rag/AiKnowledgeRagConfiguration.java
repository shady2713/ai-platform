package com.basicframework.module.ai.service.knowledge.rag;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 知识问答步骤装配（K08）：RAG 步骤是无状态纯逻辑，装配成单例供运行链路注入。
 *
 * <p>与 K04 的解析器同一取舍：步骤本身不依赖 Spring（便于单测直接构造），装配放在配置类里。
 */
@Configuration(proxyBeanMethods = false)
public class AiKnowledgeRagConfiguration {

    /** RAG 运行步骤（上下文证据 + 引用校验）。 */
    @Bean
    public AiKnowledgeRagStep aiKnowledgeRagStep() {
        return new AiKnowledgeRagStep();
    }
}

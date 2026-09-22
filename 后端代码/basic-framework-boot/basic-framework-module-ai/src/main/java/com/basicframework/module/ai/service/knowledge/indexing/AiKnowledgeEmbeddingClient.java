package com.basicframework.module.ai.service.knowledge.indexing;

import java.util.List;

/**
 * 嵌入客户端（K05）：把一批文本变成向量。
 *
 * <p>为什么要单独成端口：嵌入是**外部调用**（模型端点），是整条流水线里最可能慢/失败的一步。
 * 端口把"批量、维度校验、稳定失败原因"固定下来，调用方（索引服务）只关心结论，
 * 也便于用确定性替身在集成测试里跑通整条链路（真实模型评测由 Q04/Q10 负责）。
 */
public interface AiKnowledgeEmbeddingClient {

    /**
     * 按知识库声明的嵌入模型嵌入一批文本；失败抛 {@link AiKnowledgeEmbeddingException}
     * （稳定原因码，不含正文）。模型由知识库声明，不接受调用方临时换模型（换模型必须换代，AT-029）。
     */
    EmbeddingBatch embed(String embeddingModel, List<String> texts);

    /** 嵌入结果：向量 + 模型标识 + 维度。 */
    record EmbeddingBatch(List<float[]> vectors, String modelId, int dimension) {

        public EmbeddingBatch {
            vectors = vectors == null ? List.of() : List.copyOf(vectors);
        }
    }
}

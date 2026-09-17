package com.basicframework.framework.ai.core.model;

import java.time.Duration;
import java.util.List;

/**
 * 嵌入请求（M04 冻结）：一次调用提交**一批**文本，平台保证批次上限与顺序。
 *
 * @param modelId 模型标识（端点解析出的模型，不由调用方指定端点）
 * @param texts   待嵌入文本；必须非空、逐条非空白
 * @param timeout 单次调用超时；为空表示使用实现默认
 */
public record EmbeddingRequest(String modelId, List<String> texts, Duration timeout) {

    /** 空批次或空文本在契约层直接拒绝，避免下游对空输入做隐式处理。 */
    public EmbeddingRequest {
        if (texts == null || texts.isEmpty()) {
            throw new IllegalArgumentException("嵌入请求必须包含至少一条文本");
        }
        for (String text : texts) {
            if (text == null || text.isBlank()) {
                throw new IllegalArgumentException("嵌入文本不能为空白");
            }
        }
        texts = List.copyOf(texts);
    }

    /** 构造带默认超时的嵌入请求。 */
    public static EmbeddingRequest of(String modelId, List<String> texts) {
        return new EmbeddingRequest(modelId, texts, null);
    }

    /** 批次长度。 */
    public int batchSize() {
        return texts.size();
    }
}

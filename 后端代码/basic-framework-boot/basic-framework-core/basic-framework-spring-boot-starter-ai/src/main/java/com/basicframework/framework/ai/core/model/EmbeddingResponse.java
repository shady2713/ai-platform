package com.basicframework.framework.ai.core.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 嵌入响应（M04 冻结）：向量与请求文本**按序一一对应**。
 *
 * <p>维度在构造时校验：同一次响应的所有向量长度必须一致（{@code EMBEDDING_DIMENSION_MISMATCH}），
 * 维度值由 {@link #dimensions()} 给出。与"既有索引记录的维度"的比较属于业务侧
 * （知识库写索引前用端点服务校验），本契约只保证一次响应内部自洽。
 *
 * @param vectors  向量列表，顺序与请求文本一致
 * @param usage    用量；上游缺失时为 {@link ModelUsage#UNKNOWN}（不是假 0）
 * @param modelId  实际使用的模型标识
 */
public record EmbeddingResponse(List<float[]> vectors, ModelUsage usage, String modelId) {

    /** 维度校验：空响应或长度不一致的响应都是上游协议错误。 */
    public EmbeddingResponse {
        if (vectors == null || vectors.isEmpty()) {
            throw new ModelException(ModelException.Reason.UPSTREAM_FAILED, "嵌入响应为空");
        }
        List<float[]> copy = new ArrayList<>(vectors);
        int dimensions = copy.get(0) == null ? -1 : copy.get(0).length;
        for (float[] vector : copy) {
            if (vector == null || vector.length != dimensions) {
                throw new ModelException(ModelException.Reason.EMBEDDING_DIMENSION_MISMATCH, "嵌入响应内向量维度不一致或缺失");
            }
        }
        usage = usage == null ? ModelUsage.UNKNOWN : usage;
        vectors = List.copyOf(copy);
    }

    /** 向量条数。 */
    public int size() {
        return vectors.size();
    }

    /** 向量维度（首个向量的长度；构造时已保证全部一致）。 */
    public int dimensions() {
        return vectors.get(0).length;
    }

    /** 取第 index 条向量。 */
    public float[] vector(int index) {
        return vectors.get(index);
    }
}

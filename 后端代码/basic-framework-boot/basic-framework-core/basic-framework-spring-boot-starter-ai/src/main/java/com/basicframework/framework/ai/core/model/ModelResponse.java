package com.basicframework.framework.ai.core.model;

import java.util.List;

/**
 * 自有模型响应（M02 冻结，M03 扩展用量与工具调用）：只保留平台需要的字段，
 * 不携带厂商类型与原始 JSON。
 *
 * @param text          生成的文本
 * @param usage         用量；上游缺失时为 {@link ModelUsage#UNKNOWN}（不是假 0，见 AT-060）
 * @param toolCalls     模型请求的工具调用（数据，永不自动执行）；无则为空列表
 * @param modelId       实际使用的模型标识
 * @param finishReason  结束原因（厂商原始值的稳定映射，可为空）
 */
public record ModelResponse(
        String text, ModelUsage usage, List<ModelToolCall> toolCalls, String modelId, String finishReason) {

    /** 空值收敛：用量缺失记 UNKNOWN，工具调用缺失记空列表。 */
    public ModelResponse {
        usage = usage == null ? ModelUsage.UNKNOWN : usage;
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }

    /** 是否包含工具调用请求。 */
    public boolean hasToolCalls() {
        return !toolCalls.isEmpty();
    }
}

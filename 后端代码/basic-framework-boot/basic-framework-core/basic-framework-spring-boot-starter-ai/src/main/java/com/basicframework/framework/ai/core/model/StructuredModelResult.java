package com.basicframework.framework.ai.core.model;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 结构化输出结果（M03 冻结）。
 *
 * @param json          已通过校验的紧凑 JSON 文本（用于哈希、审计与回放）
 * @param value         解析后的对象节点（一定是 JSON object，字段级校验由调用方完成）
 * @param usage         用量；上游缺失时为 {@link ModelUsage#UNKNOWN}，不是假 0
 * @param finishReason  结束原因（厂商原始值的稳定映射，可为空）
 * @param modelId       实际使用的模型标识
 */
public record StructuredModelResult(
        String json, JsonNode value, ModelUsage usage, String finishReason, String modelId) {}

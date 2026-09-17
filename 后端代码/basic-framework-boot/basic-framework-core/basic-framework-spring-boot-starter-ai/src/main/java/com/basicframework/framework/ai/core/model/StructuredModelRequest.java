package com.basicframework.framework.ai.core.model;

import java.time.Duration;

/**
 * 结构化输出请求（M03 冻结）：要求模型返回**单个 JSON 对象**，由平台侧校验后才交给业务。
 *
 * <p>{@code jsonSchema} 是调用方给出的 JSON Schema 文本：平台把它嵌入提示词作为输出契约，
 * 并在请求构造时校验它本身是可解析的 JSON 对象（{@code INVALID_STRUCTURED_INPUT}）。
 * 深层的字段级校验属于调用方的领域契约（{@code docs/contracts/ai} 下的 Schema 由应用层校验），
 * 本接缝只保证"得到的是合法 JSON 对象"并给出稳定错误。
 */
public record StructuredModelRequest(String modelId, String prompt, String jsonSchema, Duration timeout) {

    /** 构造带默认超时的结构化请求。 */
    public static StructuredModelRequest of(String modelId, String prompt, String jsonSchema) {
        return new StructuredModelRequest(modelId, prompt, jsonSchema, null);
    }
}

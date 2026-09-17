package com.basicframework.framework.ai.core.model;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 结构化输出解析（M03）：对模型返回的 JSON 做**有界修复**，修复后仍不可用时给出稳定错误。
 *
 * <p>修复按固定顺序尝试，每一步都计入 {@code maxRepairSteps} 预算，且只在解析失败时才应用
 * （合法 JSON 不会被改写）：
 * <ol>
 *   <li>去掉 Markdown 代码块围栏（{@code ```json ... ```}）；</li>
 *   <li>截取最外层 {@code {...}} 片段（模型在 JSON 前后加了说明文字时）；</li>
 *   <li>去掉对象/数组末尾的多余逗号（{@code {"a":1,}}）。</li>
 * </ol>
 * 修复只做以上三种确定性变换：不补全字段、不猜测语义、不调用模型重试。解析结果必须是
 * JSON 对象；数组、标量与"修复后仍不可解析"一律给出
 * {@link ModelException.Reason#INVALID_STRUCTURED_OUTPUT}。
 */
public final class StructuredJsonOutput {

    /** 实现支持的修复类型数量，也是 {@code maxRepairSteps} 的有效上限。 */
    public static final int MAX_SUPPORTED_REPAIR_STEPS = 3;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private StructuredJsonOutput() {}

    /**
     * 校验调用方给出的 JSON Schema 文本：必须是可解析的 JSON 对象，否则给出
     * {@link ModelException.Reason#INVALID_STRUCTURED_INPUT}（调用方错误，不是上游失败）。
     */
    public static void validateRequestSchema(String jsonSchema) {
        if (jsonSchema == null || jsonSchema.isBlank()) {
            throw new ModelException(ModelException.Reason.INVALID_STRUCTURED_INPUT, "结构化输出请求缺少 JSON Schema");
        }
        JsonNode node;
        try {
            node = MAPPER.readTree(jsonSchema);
        } catch (JsonProcessingException exception) {
            throw new ModelException(
                    ModelException.Reason.INVALID_STRUCTURED_INPUT, "JSON Schema 不是可解析的 JSON", exception);
        }
        if (!node.isObject()) {
            throw new ModelException(ModelException.Reason.INVALID_STRUCTURED_INPUT, "JSON Schema 必须是 JSON 对象");
        }
    }

    /**
     * 有界修复并解析为 JSON 对象。
     *
     * @param raw            模型原始输出
     * @param maxRepairSteps 可用修复步数；0 表示不做任何修复，直接解析
     * @return 解析后的 JSON 对象节点
     * @throws ModelException 修复后仍不可解析或不是 JSON 对象：{@code INVALID_STRUCTURED_OUTPUT}
     */
    public static JsonNode parseObject(String raw, int maxRepairSteps) {
        if (raw == null || raw.isBlank()) {
            throw invalidOutput("模型未返回结构化内容");
        }
        int budget = Math.max(0, Math.min(maxRepairSteps, MAX_SUPPORTED_REPAIR_STEPS));
        String candidate = raw.trim();
        JsonNode node = tryParse(candidate);
        if (node == null && budget > 0) {
            budget--;
            candidate = stripFence(candidate);
            node = tryParse(candidate);
        }
        if (node == null && budget > 0) {
            budget--;
            candidate = extractObject(candidate);
            node = tryParse(candidate);
        }
        if (node == null && budget > 0) {
            candidate = removeTrailingCommas(candidate);
            node = tryParse(candidate);
        }
        if (node == null) {
            throw invalidOutput("模型输出不是可解析的 JSON");
        }
        if (!node.isObject()) {
            throw invalidOutput("结构化输出必须是 JSON 对象");
        }
        return node;
    }

    /** 紧凑序列化：稳定文本用于哈希、审计与回放。 */
    public static String compact(JsonNode node) {
        try {
            return MAPPER.writeValueAsString(node);
        } catch (JsonProcessingException exception) {
            throw invalidOutput("结构化输出无法序列化");
        }
    }

    private static JsonNode tryParse(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (JsonProcessingException exception) {
            return null;
        }
    }

    /** 修复 1：去掉 Markdown 代码块围栏。 */
    private static String stripFence(String value) {
        if (!value.startsWith("```")) {
            return value;
        }
        int firstLineEnd = value.indexOf('\n');
        if (firstLineEnd < 0) {
            return value;
        }
        String body = value.substring(firstLineEnd + 1);
        int closing = body.lastIndexOf("```");
        if (closing >= 0) {
            body = body.substring(0, closing);
        }
        return body.trim();
    }

    /** 修复 2：截取最外层对象片段，丢弃前后说明文字。 */
    private static String extractObject(String value) {
        int start = value.indexOf('{');
        int end = value.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return value;
        }
        return value.substring(start, end + 1);
    }

    /** 修复 3：去掉对象/数组末尾的多余逗号。 */
    private static String removeTrailingCommas(String value) {
        return value.replaceAll(",\\s*([}\\]])", "$1");
    }

    private static ModelException invalidOutput(String message) {
        return new ModelException(ModelException.Reason.INVALID_STRUCTURED_OUTPUT, message);
    }
}

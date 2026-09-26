package com.basicframework.module.ai.service.evaluation;

import com.basicframework.framework.common.util.json.JsonUtils;
import com.basicframework.module.ai.service.knowledge.ingestion.AiKnowledgeIngestionFilePolicy;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 评测摘要（Q04）：**同内容同摘要**，用于"套件修改不改变已有 eval 运行"与可复现报告。
 *
 * <p>规范化规则：对象键按字典序、数组保持原序、无多余空白；摘要只看规范化结果，
 * 因此字段书写顺序与空白差异不会造成"看起来一样却摘要不同"。
 * 摘要使用与文件指纹同一个 SHA-256 实现（{@link AiKnowledgeIngestionFilePolicy#sha256}），
 * 全仓只有一处哈希实现。
 */
public final class AiEvalDigest {

    private AiEvalDigest() {}

    /** 多个片段规范化拼接后的 SHA-256（十六进制）。 */
    public static String digest(String... parts) {
        StringBuilder joined = new StringBuilder();
        if (parts != null) {
            for (String part : parts) {
                joined.append(canonicalJson(part)).append('\n');
            }
        }
        return AiKnowledgeIngestionFilePolicy.sha256(joined.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** 任意值 → 规范化 JSON（对象/映射按字典序输出键）。 */
    public static String canonicalJson(Object value) {
        if (value instanceof String text) {
            return canonicalJsonText(text);
        }
        StringBuilder out = new StringBuilder();
        write(value, out);
        return out.toString();
    }

    /** JSON 文本 → 规范化 JSON；非 JSON 文本按字符串字面量处理。 */
    public static String canonicalJsonText(String raw) {
        if (raw == null) {
            return "null";
        }
        if (!JsonUtils.isJson(raw)) {
            return JsonUtils.toJsonString(raw);
        }
        Object parsed;
        try {
            parsed = JsonUtils.parseObject(raw, Object.class);
        } catch (RuntimeException exception) {
            return JsonUtils.toJsonString(raw);
        }
        StringBuilder out = new StringBuilder();
        write(parsed, out);
        return out.toString();
    }

    private static void write(Object value, StringBuilder out) {
        if (value == null) {
            out.append("null");
            return;
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sorted = new TreeMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                sorted.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            out.append('{');
            boolean first = true;
            for (Map.Entry<String, Object> entry : sorted.entrySet()) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                out.append(JsonUtils.toJsonString(entry.getKey())).append(':');
                write(entry.getValue(), out);
            }
            out.append('}');
            return;
        }
        if (value instanceof List<?> list) {
            out.append('[');
            for (int index = 0; index < list.size(); index++) {
                if (index > 0) {
                    out.append(',');
                }
                write(list.get(index), out);
            }
            out.append(']');
            return;
        }
        if (value instanceof String text) {
            out.append(JsonUtils.toJsonString(text));
            return;
        }
        if (value instanceof Number || value instanceof Boolean) {
            out.append(value);
            return;
        }
        out.append(JsonUtils.toJsonString(String.valueOf(value)));
    }
}

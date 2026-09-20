package com.basicframework.module.ai.adapter.knowledge;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_REQUEST_INVALID;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 向量索引服务端过滤（K01）：ACL 过滤条件的**唯一构造入口**。
 *
 * <p>安全约束（AT-029）：过滤条件最终由向量服务解析，因此
 * <ul>
 *   <li>字段名只允许白名单内的标识符（`^[a-z][a-z0-9_]{0,31}$`），杜绝表达式注入；</li>
 *   <li>取值只允许"安全标量"（字母数字、下划线、连字符、点、冒号、中文与空格），
 *       任何引号、括号、反斜杠、通配符、换行等特殊字符一律**拒绝**而不是转义；
 *       拒绝而不是转义的理由：转义规则依赖上游实现，拒绝是唯一不依赖上游行为的做法；</li>
 *   <li>过滤条件只表达"命中这些值"，不能表达否定、范围或脚本（最小表达力）。</li>
 * </ul>
 *
 * <p>调用方（检索链路）只构造 {@link #of} 与 {@link #and}，不接触任何查询语法。
 */
public final class KnowledgeFilter {

    /** 允许过滤的字段名（载荷字段白名单）。 */
    private static final Pattern FIELD_PATTERN = Pattern.compile("^[a-z][a-z0-9_]{0,31}$");

    /** 允许的取值：安全标量（含中文），不含任何查询语法字符。 */
    private static final Pattern VALUE_PATTERN = Pattern.compile("^[\\p{L}\\p{N}_.:\\- ]{1,128}$");

    private final Map<String, List<String>> conditions;

    private KnowledgeFilter(Map<String, List<String>> conditions) {
        this.conditions = conditions;
    }

    /** 构造单字段过滤（字段必须命中给定取值之一）。 */
    public static KnowledgeFilter of(String field, List<String> values) {
        if (!FIELD_PATTERN.matcher(field == null ? "" : field).matches() || values == null || values.isEmpty()) {
            throw exception(AI_REQUEST_INVALID);
        }
        List<String> safe = new ArrayList<>();
        for (String value : values) {
            if (!isSafeValue(value)) {
                // 特殊字符（引号/括号/通配符/反斜杠/换行等）一律拒绝，不做转义
                throw exception(AI_REQUEST_INVALID);
            }
            safe.add(value);
        }
        Map<String, List<String>> conditions = new LinkedHashMap<>();
        conditions.put(field, List.copyOf(safe));
        return new KnowledgeFilter(conditions);
    }

    /** 与另一个过滤条件取交集（多字段 AND）。 */
    public KnowledgeFilter and(KnowledgeFilter other) {
        if (other == null) {
            return this;
        }
        Map<String, List<String>> merged = new LinkedHashMap<>(conditions);
        other.conditions.forEach((field, values) -> merged.merge(field, values, (left, right) -> {
            List<String> intersection = new ArrayList<>(left);
            intersection.retainAll(right);
            return List.copyOf(intersection);
        }));
        return new KnowledgeFilter(merged);
    }

    /** 过滤条件（字段 → 允许的取值；空表示不过滤）。 */
    public Map<String, List<String>> conditions() {
        return Map.copyOf(conditions);
    }

    /** 是否为空过滤（不加任何条件）。 */
    public boolean isEmpty() {
        return conditions.isEmpty();
    }

    /** 取值是否安全（公开给测试，便于逐字符证明拒绝规则）。 */
    public static boolean isSafeValue(String value) {
        return value != null && VALUE_PATTERN.matcher(value).matches();
    }
}

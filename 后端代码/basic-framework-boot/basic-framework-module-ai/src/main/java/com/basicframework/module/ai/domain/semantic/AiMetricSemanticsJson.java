package com.basicframework.module.ai.domain.semantic;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_METRIC_CALIBER_MISSING_CONFLICT;

import com.basicframework.framework.common.util.json.JsonUtils;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 跨源口径定义的解析原语（Y03）：形状、类型与枚举的统一收口。
 *
 * <p>刻意与 {@link AiMetricSemantics} 分开：解析原语是"这段 JSON 长得对不对"，
 * 语义判定是"这些声明彼此相不矛盾"。两者混在一个类里会让 800 行上限很快被撑破，
 * 而且形状错误与口径冲突本来就需要不同的错误码。
 *
 * <p>统一原则：**未知、缺失、形状不符一律拒绝**。这里没有任何"给个默认值"的分支——
 * 跨源指标少一个币种声明，结果就是把不同币种当成同币种相加。
 */
final class AiMetricSemanticsJson {

    private static final Pattern NAME_PATTERN = Pattern.compile("^[a-z][a-z0-9_]{0,63}$");

    private static final Pattern CURRENCY_PATTERN = Pattern.compile("^(NONE|[A-Z]{3})$");

    private static final Pattern TIMEZONE_PATTERN = Pattern.compile("^[A-Za-z_]+(?:/[A-Za-z0-9_+.-]+)*$");

    private static final Pattern RULE_PATTERN = Pattern.compile("^[a-z][a-z0-9_]{0,63}$");

    private AiMetricSemanticsJson() {}

    /** 读取一个 JSON 对象（null / 非对象 / 非法 JSON 一律拒绝）。 */
    static Map<String, Object> readObject(Object value) {
        if (value == null) {
            throw exception(AI_METRIC_CALIBER_MISSING_CONFLICT);
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> mapped = new LinkedHashMap<>();
            map.forEach((key, entry) -> mapped.put(String.valueOf(key), entry));
            return mapped;
        }
        if (value instanceof String text) {
            try {
                Map<?, ?> parsed = JsonUtils.parseObject(text, Map.class);
                if (parsed == null) {
                    throw exception(AI_METRIC_CALIBER_MISSING_CONFLICT);
                }
                Map<String, Object> mapped = new LinkedHashMap<>();
                parsed.forEach((key, entry) -> mapped.put(String.valueOf(key), entry));
                return mapped;
            } catch (IllegalArgumentException notAnObject) {
                throw exception(AI_METRIC_CALIBER_MISSING_CONFLICT);
            }
        }
        throw exception(AI_METRIC_CALIBER_MISSING_CONFLICT);
    }

    /** 键白名单：出现任何未声明键即拒绝（防止"多写一个字段就改变语义"）。 */
    static void requireKeys(Map<String, Object> values, Set<String> allowed) {
        for (String key : values.keySet()) {
            if (!allowed.contains(key)) {
                throw AiMetricSemanticsErrors.invalidSource();
            }
        }
    }

    /** 对象数组（上下限都强制；空数组不合法——跨源聚合至少要一个来源）。 */
    static List<Map<String, Object>> objectList(Object value, int max, int min) {
        if (!(value instanceof List<?> list) || list.size() > max || list.size() < min) {
            throw AiMetricSemanticsErrors.invalidSource();
        }
        List<Map<String, Object>> items = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) {
                throw AiMetricSemanticsErrors.invalidSource();
            }
            Map<String, Object> mapped = new LinkedHashMap<>();
            map.forEach((key, entry) -> mapped.put(String.valueOf(key), entry));
            items.add(mapped);
        }
        return items;
    }

    /** 逻辑名（角色 / 粒度键 / 数据集标识）。 */
    static String name(Object value) {
        String text = text(value);
        if (text == null || !NAME_PATTERN.matcher(text).matches()) {
            throw AiMetricSemanticsErrors.invalidSource();
        }
        return text;
    }

    /** 名称数组（去重且保序：重复项说明有人把同一个粒度写了两遍）。 */
    static List<String> nameList(Object value, int max, int min) {
        if (!(value instanceof List<?> list) || list.size() > max || list.size() < min) {
            throw AiMetricSemanticsErrors.invalidSource();
        }
        List<String> names = new ArrayList<>();
        Set<String> unique = new LinkedHashSet<>();
        for (Object item : list) {
            String name = name(item);
            if (!unique.add(name)) {
                throw AiMetricSemanticsErrors.invalidSource();
            }
            names.add(name);
        }
        return names;
    }

    /** 枚举值（大小写不敏感归一为大写词表取值；未知取值拒绝）。 */
    static String enumValue(Object value, Set<String> allowed) {
        String text = text(value);
        if (text == null) {
            throw AiMetricSemanticsErrors.invalidSource();
        }
        String normalized = text.toUpperCase(java.util.Locale.ROOT);
        if (!allowed.contains(normalized)) {
            throw AiMetricSemanticsErrors.invalidSource();
        }
        return normalized;
    }

    /** 币种：ISO 三字母大写码或 {@code NONE}。 */
    static String currency(Object value) {
        String text = text(value);
        if (text == null) {
            throw AiMetricSemanticsErrors.missingCaliber();
        }
        String normalized = text.toUpperCase(java.util.Locale.ROOT);
        if (!CURRENCY_PATTERN.matcher(normalized).matches()) {
            throw AiMetricSemanticsErrors.invalidSource();
        }
        return normalized;
    }

    /** 时区。 */
    static String timezone(Object value) {
        String text = text(value);
        if (text == null) {
            throw AiMetricSemanticsErrors.missingCaliber();
        }
        if (!TIMEZONE_PATTERN.matcher(text).matches()) {
            throw AiMetricSemanticsErrors.invalidSource();
        }
        return text;
    }

    /** 换算规则标识。 */
    static String rule(Object value) {
        String text = text(value);
        if (text == null || !RULE_PATTERN.matcher(text).matches()) {
            throw AiMetricSemanticsErrors.conversionRuleConflict();
        }
        return text;
    }

    /** 正整数（数据集版本号从 1 开始）。 */
    static Integer positiveInt(Object value) {
        if (!(value instanceof Number number) || number.intValue() < 1) {
            throw AiMetricSemanticsErrors.invalidSource();
        }
        return number.intValue();
    }

    /** 正整数长（映射版本号）。 */
    static Long positiveLong(Object value) {
        if (!(value instanceof Number number) || number.longValue() < 1) {
            throw AiMetricSemanticsErrors.invalidSource();
        }
        return number.longValue();
    }

    /** 布尔值：缺省为 false（{@code optional} 只能是显式 true 才算"可选来源"）。 */
    static boolean boolValue(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean flag) {
            return flag;
        }
        if (value instanceof String text) {
            if ("true".equalsIgnoreCase(text)) {
                return true;
            }
            if ("false".equalsIgnoreCase(text)) {
                return false;
            }
        }
        throw AiMetricSemanticsErrors.invalidSource();
    }

    private static String text(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String text) {
            String trimmed = text.trim();
            return trimmed.isEmpty() ? null : trimmed;
        }
        if (value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        throw AiMetricSemanticsErrors.invalidSource();
    }
}

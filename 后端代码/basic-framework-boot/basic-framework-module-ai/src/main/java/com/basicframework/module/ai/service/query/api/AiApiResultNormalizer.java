package com.basicframework.module.ai.service.query.api;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_QUERY_RESULT_FORMAT_DRIFT;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_QUERY_RESULT_INVALID;

import com.basicframework.framework.common.util.json.JsonUtils;
import com.basicframework.module.ai.domain.query.ValidatedQueryPlan;
import com.basicframework.module.ai.domain.result.AiNormalizedResult;
import com.basicframework.module.ai.domain.result.AiResultSchema;
import com.basicframework.module.ai.domain.semantic.AiSemanticTypes;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.stereotype.Component;

/**
 * API 结果归一化（D07）：上游条目 → 结果 Schema + 行 + 完整性结论。
 *
 * <p>顺序是安全语义，不能颠倒：
 * <ol>
 *   <li><b>投影</b>：只保留计划里出现的列（结果 Schema 即白名单），上游多余字段一律丢弃——
 *       模型与报表只能看到授权范围内的列；</li>
 *   <li><b>归一</b>：按语义类型解析取值（金额必须是十进制数 → 输出十进制字符串；时间归一为 ISO-8601）；</li>
 *   <li><b>漂移判定</b>：条目缺少全部期望列、或取值类型与声明不符 → 稳定错误码（不静默补 0/空）；</li>
 *   <li><b>聚合</b>：按计划维度分组、按聚合白名单计算；金额用 {@link BigDecimal}（绝不用 double）；</li>
 *   <li><b>完整性</b>：只有上游确认取完且未触达任何上限，才允许宣称完整统计。</li>
 * </ol>
 */
@Component
public class AiApiResultNormalizer {

    /** AVG 的十进制精度（避免无限小数）。 */
    private static final int AVG_SCALE = 6;

    /** 单次归一化的条目上限（与 D02 执行器的条目预算一致）。 */
    public static final int MAX_ITEMS = 1_000;

    /** 归一化：把 D02 执行结果（条目 JSON 文本）变成计划形状的结果。 */
    public AiNormalizedResult normalize(
            ValidatedQueryPlan plan, List<String> items, String sourceStatus, String sourceReason, int pages) {
        AiResultSchema schema = schemaOf(plan);
        List<Map<String, Object>> projected = new ArrayList<>();
        Set<String> expectedCodes = schema.codes();
        boolean anyItemSeen = false;
        boolean anyCodeMatched = false;
        for (String item : items == null ? List.<String>of() : items) {
            Map<String, Object> source = parseItem(item);
            if (source == null) {
                // 条目不是 JSON 对象：声明了列表却拿到别的形状 → 格式漂移
                throw exception(AI_QUERY_RESULT_FORMAT_DRIFT);
            }
            anyItemSeen = true;
            Map<String, Object> row = project(source, plan, expectedCodes);
            if (row.isEmpty()) {
                continue;
            }
            anyCodeMatched = true;
            projected.add(row);
        }
        if (anyItemSeen && !anyCodeMatched) {
            // 有数据但一个期望列都没有：上游字段名/结构变了（格式漂移），不能当成"空结果"
            throw exception(AI_QUERY_RESULT_FORMAT_DRIFT);
        }

        List<Map<String, Object>> rows = aggregate(plan, projected);
        String completeness = completenessOf(sourceStatus, rows.size());
        // 停止原因一律保留（即使是 COMPLETE，也要能看到"为什么停"）
        String reason = sourceReason;
        return new AiNormalizedResult(schema, rows, completeness, reason, pages, projected.size(), sourceStatus);
    }

    /** 结果 Schema：维度在前、指标在后（与 SELECT 顺序一致）。 */
    static AiResultSchema schemaOf(ValidatedQueryPlan plan) {
        List<AiResultSchema.Column> columns = new ArrayList<>();
        for (ValidatedQueryPlan.Dimension dimension : plan.dimensions()) {
            columns.add(new AiResultSchema.Column(dimension.code(), dimension.code(), AiSemanticTypes.STRING, "NONE"));
        }
        for (ValidatedQueryPlan.Metric metric : plan.metrics()) {
            columns.add(
                    new AiResultSchema.Column(metric.code(), metric.code(), AiSemanticTypes.DECIMAL, metric.unit()));
        }
        return new AiResultSchema(columns);
    }

    /** 完整性：只有上游 COMPLETE 且没有行数上限截断，才允许宣称完整。 */
    private static String completenessOf(String sourceStatus, int rowCount) {
        if ("FAILED".equals(sourceStatus)) {
            return AiNormalizedResult.FAILED;
        }
        if (!"COMPLETE".equals(sourceStatus)) {
            return AiNormalizedResult.PARTIAL;
        }
        return AiNormalizedResult.COMPLETE;
    }

    private static Map<String, Object> parseItem(String item) {
        if (item == null || item.isBlank()) {
            return null;
        }
        Map<?, ?> parsed;
        try {
            parsed = JsonUtils.parseObject(item, Map.class);
        } catch (IllegalArgumentException notAnObject) {
            return null;
        }
        if (parsed == null) {
            return null;
        }
        Map<String, Object> mapped = new LinkedHashMap<>();
        parsed.forEach((key, value) -> mapped.put(String.valueOf(key), value));
        return mapped;
    }

    /** 投影：只取计划里的列（按逻辑码，其次按物理列名），并按语义类型归一取值。 */
    private static Map<String, Object> project(
            Map<String, Object> source, ValidatedQueryPlan plan, Set<String> expectedCodes) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (ValidatedQueryPlan.Dimension dimension : plan.dimensions()) {
            Object value = valueOf(source, dimension.code(), dimension.sourceColumn());
            if (value == UNRESOLVED) {
                continue;
            }
            row.put(dimension.code(), coerce(value, AiSemanticTypes.STRING, dimension.code()));
        }
        for (ValidatedQueryPlan.Metric metric : plan.metrics()) {
            Object value = valueOf(source, metric.code(), metric.sourceColumn());
            if (value == UNRESOLVED) {
                continue;
            }
            // 指标列来自原始条目：按数值语义归一（聚合在下一步做）
            row.put(metric.code(), coerceNumber(value, metric.code()));
        }
        return row;
    }

    /** 取值：先按逻辑码，再按物理列名；都没有则视为未解析。 */
    private static final Object UNRESOLVED = new Object();

    private static Object valueOf(Map<String, Object> source, String code, String sourceColumn) {
        if (source.containsKey(code)) {
            return source.get(code);
        }
        if (sourceColumn != null && source.containsKey(sourceColumn)) {
            return source.get(sourceColumn);
        }
        return UNRESOLVED;
    }

    /** 按语义类型归一：文本、布尔、时间（ISO-8601）与十进制。 */
    private static Object coerce(Object value, String semanticType, String code) {
        if (value == null) {
            return null;
        }
        return switch (semanticType) {
            case AiSemanticTypes.DECIMAL, AiSemanticTypes.NUMBER -> coerceNumber(value, code);
            case AiSemanticTypes.BOOLEAN -> {
                if (value instanceof Boolean flag) {
                    yield flag;
                }
                String text = String.valueOf(value).trim().toLowerCase(Locale.ROOT);
                if ("true".equals(text) || "false".equals(text)) {
                    yield Boolean.valueOf(text);
                }
                throw exception(AI_QUERY_RESULT_INVALID);
            }
            default -> {
                String text = String.valueOf(value);
                if (text.length() > 4_000) {
                    throw exception(AI_QUERY_RESULT_INVALID);
                }
                yield text;
            }
        };
    }

    /** 数值归一：输出十进制字符串语义（BigDecimal），解析失败明确报错。 */
    private static Object coerceNumber(Object value, String code) {
        if (value == null) {
            return null;
        }
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Number number) {
            return new BigDecimal(number.toString());
        }
        String text = String.valueOf(value).trim();
        try {
            return new BigDecimal(text);
        } catch (NumberFormatException invalid) {
            // 例如上游把金额写成 "1,234.00" 或 "N/A"：明确报错，不静默补 0
            throw exception(AI_QUERY_RESULT_INVALID);
        }
    }

    /** 聚合：按维度分组，按计划里的聚合方式计算指标。 */
    private static List<Map<String, Object>> aggregate(ValidatedQueryPlan plan, List<Map<String, Object>> projected) {
        List<String> dimensionCodes = plan.dimensions().stream()
                .map(ValidatedQueryPlan.Dimension::code)
                .toList();
        Map<String, Map<String, List<Object>>> grouped = new LinkedHashMap<>();
        Map<String, Map<String, Object>> dimensionValues = new LinkedHashMap<>();
        for (Map<String, Object> row : projected) {
            StringBuilder keyBuilder = new StringBuilder();
            Map<String, Object> dims = new LinkedHashMap<>();
            for (String code : dimensionCodes) {
                Object value = row.get(code);
                dims.put(code, value);
                keyBuilder.append(value == null ? "\u0000" : value).append('\u0001');
            }
            String key = keyBuilder.toString();
            grouped.computeIfAbsent(key, ignored -> new LinkedHashMap<>());
            dimensionValues.putIfAbsent(key, dims);
            for (ValidatedQueryPlan.Metric metric : plan.metrics()) {
                grouped.get(key)
                        .computeIfAbsent(metric.code(), ignored -> new ArrayList<>())
                        .add(row.get(metric.code()));
            }
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map.Entry<String, Map<String, List<Object>>> entry : grouped.entrySet()) {
            Map<String, Object> row = new LinkedHashMap<>(dimensionValues.get(entry.getKey()));
            for (ValidatedQueryPlan.Metric metric : plan.metrics()) {
                row.put(metric.code(), aggregateMetric(metric, entry.getValue().get(metric.code())));
            }
            rows.add(row);
        }
        // 稳定输出顺序：按维度值升序（分页与快照比对可复现）
        rows.sort((left, right) -> {
            for (String code : dimensionCodes) {
                int compared = compareValues(left.get(code), right.get(code));
                if (compared != 0) {
                    return compared;
                }
            }
            return 0;
        });
        return rows;
    }

    private static int compareValues(Object left, Object right) {
        if (left == null && right == null) {
            return 0;
        }
        if (left == null) {
            return -1;
        }
        if (right == null) {
            return 1;
        }
        return String.valueOf(left).compareTo(String.valueOf(right));
    }

    /** 单个指标聚合：SUM/AVG/COUNT/COUNT_DISTINCT/MIN/MAX（金额保持十进制精度）。 */
    private static Object aggregateMetric(ValidatedQueryPlan.Metric metric, List<Object> values) {
        List<Object> present = values == null ? List.of() : values;
        String aggregation =
                metric.aggregation() == null ? "" : metric.aggregation().toUpperCase(Locale.ROOT);
        return switch (aggregation) {
            case "SUM" -> sum(present);
            case "AVG" -> average(present);
            case "COUNT" ->
                BigDecimal.valueOf(
                        present.stream().filter(java.util.Objects::nonNull).count());
            case "COUNT_DISTINCT" ->
                BigDecimal.valueOf(present.stream()
                        .filter(java.util.Objects::nonNull)
                        .map(Object::toString)
                        .collect(java.util.stream.Collectors.toCollection(TreeSet::new))
                        .size());
            case "MIN" -> extreme(present, true);
            case "MAX" -> extreme(present, false);
            default -> throw exception(AI_QUERY_RESULT_FORMAT_DRIFT);
        };
    }

    private static BigDecimal sum(List<Object> values) {
        BigDecimal total = BigDecimal.ZERO;
        for (Object value : values) {
            if (value instanceof BigDecimal decimal) {
                total = total.add(decimal);
            }
        }
        return total;
    }

    private static BigDecimal average(List<Object> values) {
        List<BigDecimal> numbers = values.stream()
                .filter(BigDecimal.class::isInstance)
                .map(BigDecimal.class::cast)
                .toList();
        if (numbers.isEmpty()) {
            return null;
        }
        BigDecimal total = numbers.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        return total.divide(BigDecimal.valueOf(numbers.size()), AVG_SCALE, RoundingMode.HALF_UP);
    }

    private static BigDecimal extreme(List<Object> values, boolean min) {
        BigDecimal best = null;
        for (Object value : values) {
            if (!(value instanceof BigDecimal decimal)) {
                continue;
            }
            if (best == null || (min ? decimal.compareTo(best) < 0 : decimal.compareTo(best) > 0)) {
                best = decimal;
            }
        }
        return best;
    }

    /** 结果列白名单（供调用方在把结果交给模型前再次确认）。 */
    public static Set<String> authorizedCodes(ValidatedQueryPlan plan) {
        return new LinkedHashSet<>(schemaOf(plan).codes());
    }
}

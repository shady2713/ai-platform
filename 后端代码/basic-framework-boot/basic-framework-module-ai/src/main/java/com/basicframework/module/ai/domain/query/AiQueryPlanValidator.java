package com.basicframework.module.ai.domain.query;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_QUERY_CLARIFICATION_REQUIRED;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_QUERY_DATASET_NOT_ALLOWED;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_QUERY_PLAN_INVALID;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_QUERY_SQL_REJECTED;

import com.basicframework.framework.common.util.json.JsonUtils;
import com.basicframework.module.ai.domain.semantic.AiDatasetDefinition;
import com.basicframework.module.ai.domain.semantic.AiSemanticTypes;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.util.StringUtils;

/**
 * 查询计划校验器（D05）：把模型输出的 PLAN 变成 {@link ValidatedQueryPlan} 的**唯一**通道。
 *
 * <p>校验顺序固定（结构 → 授权与版本 → 指标字段类型 → 时间 → 参数），
 * 顺序本身是安全语义：先挡住 SQL 与结构越界，再确认数据集/版本授权，然后才解析业务口径，
 * 避免"先解析再授权"造成的越权信息泄漏（错误消息里出现未授权字段名）。
 *
 * <p>三类结果必须分清：
 * <ul>
 *   <li><b>拒绝</b>（{@code AI_QUERY_PLAN_INVALID} / {@code AI_QUERY_SQL_REJECTED} / {@code AI_QUERY_DATASET_NOT_ALLOWED}）：
 *       结构非法、SQL 片段、越权数据集；</li>
 *   <li><b>需要澄清</b>（{@code AI_QUERY_CLARIFICATION_REQUIRED}）：引用了目录里的**别名**而不是逻辑码——
 *       这是用户措辞歧义，应当追问，不能猜；</li>
 *   <li><b>通过</b>：产出带物理映射与计划哈希的 {@link ValidatedQueryPlan}。</li>
 * </ul>
 *
 * <p>时间是确定性判定：所有边界基于调用方传入的 {@code now}（固定 Clock 注入），
 * 不读系统时钟，保证同一输入在任何时刻得到同一结论。
 */
public final class AiQueryPlanValidator {

    /** 计划契约版本（与 query-plan.schema.json 的 const 一致）。 */
    public static final String SCHEMA_VERSION = "1.0";

    public static final int MAX_METRICS = 20;

    public static final int MAX_DIMENSIONS = 8;

    public static final int MAX_FILTERS = 20;

    public static final int MAX_ORDER_BY = 8;

    public static final int MAX_IN_VALUES = 100;

    public static final int MAX_VALUE_LENGTH = 1_000;

    public static final int MIN_LIMIT = 1;

    public static final int MAX_LIMIT = 1_000;

    /** 时间窗口最大跨度（天）：超过即拒绝（"过宽"不允许）。 */
    public static final int MAX_WINDOW_DAYS = 366;

    /** 窗口末端允许的最大未来偏移（天）。 */
    public static final int MAX_FUTURE_DAYS = 366;

    /** 窗口起点允许的最大过去偏移（天）。 */
    public static final int MAX_PAST_DAYS = 3_650;

    private static final Pattern CODE_PATTERN = Pattern.compile("^[a-z][a-z0-9_]{0,63}$");

    private static final Pattern DATASET_ID_PATTERN = Pattern.compile("^dset_[A-Za-z0-9_-]{3,35}$");

    private static final Pattern TIMEZONE_PATTERN = Pattern.compile("^[A-Za-z_]+(?:/[A-Za-z0-9_+.-]+)*$");

    private static final Set<String> TOP_LEVEL_KEYS = Set.of(
            "schemaVersion",
            "datasetId",
            "datasetVersion",
            "metrics",
            "dimensions",
            "filters",
            "timeRange",
            "orderBy",
            "limit");

    private static final Set<String> COMPARISON_OPERATORS = Set.of("EQ", "NE", "GT", "GE", "LT", "LE");

    private static final Set<String> OPERATORS = Set.of("EQ", "NE", "GT", "GE", "LT", "LE", "IN", "BETWEEN", "IS_NULL");

    private static final Set<String> DIRECTIONS = Set.of("ASC", "DESC");

    /** SQL 片段标记：出现即判定"模型返回 SQL"，直接拒绝（不做"看起来像 SQL 就试着修复"）。 */
    private static final Pattern SQL_MARKER = Pattern.compile(
            "(?i)(;|--|/\\*|\\(|\\)|\\b(select|from|where|join|union|drop|insert|update|delete|alter|grant|exec|"
                    + "call|truncate|into|outfile|load_file)\\b)");

    /** 校验模型输出的 PLAN（不通过一律抛稳定错误码）。 */
    public ValidatedQueryPlan validate(String planJson, ResolvedDatasetVersion dataset, Instant now) {
        if (!StringUtils.hasText(planJson) || dataset == null || now == null) {
            throw exception(AI_QUERY_PLAN_INVALID);
        }
        // 1) 结构：SQL 片段、JSON 形状、契约版本、顶层键白名单
        if (SQL_MARKER.matcher(planJson).find()) {
            throw exception(AI_QUERY_SQL_REJECTED);
        }
        Map<String, Object> plan = parseObject(planJson);
        requireKeys(plan, TOP_LEVEL_KEYS);
        if (!SCHEMA_VERSION.equals(String.valueOf(plan.get("schemaVersion")))) {
            throw exception(AI_QUERY_PLAN_INVALID);
        }
        // 2) 授权与版本：数据集必须是本次解析出的那一个，版本必须一致（不允许模型换数据集/换版本）
        requireDatasetBinding(plan, dataset);
        // 3) 指标与字段：从服务端目录解析逻辑码，校验类型/单位/聚合与授权范围
        Map<String, AiDatasetDefinition.Metric> metricCatalog = metricCatalog(dataset);
        Map<String, AiDatasetDefinition.Field> fieldCatalog = fieldCatalog(dataset);
        List<ValidatedQueryPlan.Metric> metrics = parseMetrics(plan.get("metrics"), metricCatalog, fieldCatalog);
        List<ValidatedQueryPlan.Dimension> dimensions =
                parseDimensions(plan.get("dimensions"), dataset.definition().dimensions(), fieldCatalog);
        // 4) 时间：固定 now 判定边界与跨度，时区必须与数据集声明一致
        ValidatedQueryPlan.TimeWindow timeWindow = parseTimeRange(plan.get("timeRange"), dataset, fieldCatalog, now);
        // 5) 参数：过滤值类型、枚举、排序字段与 limit
        List<ValidatedQueryPlan.Filter> filters = parseFilters(plan.get("filters"), fieldCatalog);
        List<ValidatedQueryPlan.Order> orderBy = parseOrderBy(plan.get("orderBy"), metrics, dimensions, fieldCatalog);
        int limit = parseLimit(plan.get("limit"));

        return new ValidatedQueryPlan(
                dataset.datasetId(),
                dataset.datasetCode(),
                dataset.datasetVersionId(),
                dataset.datasetVersionNo(),
                dataset.schemaHash(),
                dataset.sourceObject(),
                metrics,
                dimensions,
                filters,
                timeWindow,
                orderBy,
                limit);
    }

    /** 数据集标识与版本必须与解析结果一致；数据集本身必须满足计划契约的 id 形状。 */
    private static void requireDatasetBinding(Map<String, Object> plan, ResolvedDatasetVersion dataset) {
        String expectedId = dataset.planDatasetId();
        if (!DATASET_ID_PATTERN.matcher(expectedId).matches()) {
            // 数据集标识过长/非法时不能进入计划契约：这是配置问题，不是模型问题
            throw exception(AI_QUERY_PLAN_INVALID);
        }
        String planDatasetId = String.valueOf(plan.get("datasetId"));
        if (!expectedId.equals(planDatasetId)) {
            // 模型提到别的数据集：可能是越权尝试，也可能是"问题超出范围"；这里按越权拒绝，由调用方决定是否追问
            throw exception(AI_QUERY_DATASET_NOT_ALLOWED);
        }
        Object version = plan.get("datasetVersion");
        if (!(version instanceof Number number) || number.intValue() != dataset.datasetVersionNo()) {
            throw exception(AI_QUERY_PLAN_INVALID);
        }
    }

    private static Map<String, AiDatasetDefinition.Metric> metricCatalog(ResolvedDatasetVersion dataset) {
        Map<String, AiDatasetDefinition.Metric> catalog = new LinkedHashMap<>();
        for (AiDatasetDefinition.Metric metric : dataset.definition().metrics()) {
            if (dataset.allowsField(metric.name())) {
                catalog.put(metric.name(), metric);
            }
        }
        return catalog;
    }

    private static Map<String, AiDatasetDefinition.Field> fieldCatalog(ResolvedDatasetVersion dataset) {
        Map<String, AiDatasetDefinition.Field> catalog = new LinkedHashMap<>();
        for (AiDatasetDefinition.Field field : dataset.definition().fields()) {
            if (dataset.allowsField(field.name())) {
                catalog.put(field.name(), field);
            }
        }
        return catalog;
    }

    private static List<ValidatedQueryPlan.Metric> parseMetrics(
            Object value,
            Map<String, AiDatasetDefinition.Metric> catalog,
            Map<String, AiDatasetDefinition.Field> fieldCatalog) {
        List<String> codes = codeList(value, MAX_METRICS, 1);
        List<ValidatedQueryPlan.Metric> metrics = new ArrayList<>();
        for (String code : codes) {
            AiDatasetDefinition.Metric metric = catalog.get(code);
            if (metric == null) {
                throw unknownMetricCode(code, fieldCatalog);
            }
            AiDatasetDefinition.Field field = fieldCatalog.get(metric.field());
            if (field == null) {
                // 指标依赖的字段不在授权范围内：该指标不可用
                throw exception(AI_QUERY_DATASET_NOT_ALLOWED);
            }
            metrics.add(new ValidatedQueryPlan.Metric(code, field.sourceColumn(), metric.aggregation(), metric.unit()));
        }
        return metrics;
    }

    /** 未知指标码：命中某个可聚合字段的别名时按"措辞歧义"追问，否则按非法字段拒绝。 */
    private static RuntimeException unknownMetricCode(
            String code, Map<String, AiDatasetDefinition.Field> fieldCatalog) {
        boolean aliasHit = fieldCatalog.values().stream()
                .filter(field -> AiSemanticTypes.AGGREGATABLE.contains(field.type()))
                .anyMatch(field -> field.aliases().stream().anyMatch(alias -> alias.equalsIgnoreCase(code)));
        return aliasHit ? exception(AI_QUERY_CLARIFICATION_REQUIRED) : exception(AI_QUERY_PLAN_INVALID);
    }

    private static List<ValidatedQueryPlan.Dimension> parseDimensions(
            Object value,
            List<AiDatasetDefinition.Dimension> declared,
            Map<String, AiDatasetDefinition.Field> fieldCatalog) {
        List<String> codes = codeList(value, MAX_DIMENSIONS, 0);
        List<ValidatedQueryPlan.Dimension> dimensions = new ArrayList<>();
        for (String code : codes) {
            AiDatasetDefinition.Dimension dimension = declared.stream()
                    .filter(candidate -> candidate.name().equals(code))
                    .findFirst()
                    .orElse(null);
            if (dimension == null) {
                throw unknownFieldCode(code, fieldCatalog);
            }
            AiDatasetDefinition.Field field = fieldCatalog.get(dimension.field());
            if (field == null) {
                // 维度引用的字段不在授权范围内：等价于该维度不可用
                throw exception(AI_QUERY_DATASET_NOT_ALLOWED);
            }
            dimensions.add(new ValidatedQueryPlan.Dimension(code, field.sourceColumn()));
        }
        return dimensions;
    }

    private static List<ValidatedQueryPlan.Filter> parseFilters(
            Object value, Map<String, AiDatasetDefinition.Field> fieldCatalog) {
        List<Map<String, Object>> items = objectList(value, MAX_FILTERS);
        List<ValidatedQueryPlan.Filter> filters = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Map<String, Object> item : items) {
            String code = String.valueOf(item.get("field"));
            AiDatasetDefinition.Field field = fieldCatalog.get(code);
            if (field == null) {
                throw unknownFieldCode(code, fieldCatalog);
            }
            String operator = String.valueOf(item.get("operator"));
            if (!OPERATORS.contains(operator)) {
                throw exception(AI_QUERY_PLAN_INVALID);
            }
            if (!seen.add(code + "#" + operator)) {
                throw exception(AI_QUERY_PLAN_INVALID);
            }
            List<Object> values = filterValues(item, operator, field);
            filters.add(new ValidatedQueryPlan.Filter(code, field.sourceColumn(), field.type(), operator, values));
        }
        return filters;
    }

    private static List<Object> filterValues(
            Map<String, Object> item, String operator, AiDatasetDefinition.Field field) {
        if ("IS_NULL".equals(operator)) {
            if (item.containsKey("value")
                    || item.containsKey("values")
                    || item.containsKey("lower")
                    || item.containsKey("upper")) {
                throw exception(AI_QUERY_PLAN_INVALID);
            }
            return List.of();
        }
        if ("IN".equals(operator)) {
            if (!(item.get("values") instanceof List<?> raw) || raw.isEmpty() || raw.size() > MAX_IN_VALUES) {
                throw exception(AI_QUERY_PLAN_INVALID);
            }
            List<Object> values = new ArrayList<>();
            Set<Object> unique = new LinkedHashSet<>();
            for (Object candidate : raw) {
                Object value = typedValue(candidate, field);
                if (!unique.add(value)) {
                    throw exception(AI_QUERY_PLAN_INVALID);
                }
                values.add(value);
            }
            return values;
        }
        if ("BETWEEN".equals(operator)) {
            Object lower = typedValue(item.get("lower"), field);
            Object upper = typedValue(item.get("upper"), field);
            if (lower == null || upper == null || !ordered(lower, upper, field.type())) {
                throw exception(AI_QUERY_PLAN_INVALID);
            }
            return List.of(lower, upper);
        }
        if (!COMPARISON_OPERATORS.contains(operator) || !item.containsKey("value")) {
            throw exception(AI_QUERY_PLAN_INVALID);
        }
        Object value = typedValue(item.get("value"), field);
        if (value == null) {
            throw exception(AI_QUERY_PLAN_INVALID);
        }
        return List.of(value);
    }

    /** 取值类型与枚举校验：类型必须与字段语义一致，枚举字段只能取声明过的值。 */
    private static Object typedValue(Object value, AiDatasetDefinition.Field field) {
        if (value == null) {
            return null;
        }
        return switch (field.type()) {
            case AiSemanticTypes.NUMBER, AiSemanticTypes.DECIMAL -> {
                if (!(value instanceof Number number)) {
                    throw exception(AI_QUERY_PLAN_INVALID);
                }
                yield number instanceof Double || number instanceof Float ? number.doubleValue() : number.longValue();
            }
            case AiSemanticTypes.BOOLEAN -> {
                if (!(value instanceof Boolean flag)) {
                    throw exception(AI_QUERY_PLAN_INVALID);
                }
                yield flag;
            }
            case AiSemanticTypes.DATE, AiSemanticTypes.DATETIME -> {
                if (!(value instanceof String text) || !isIsoDateTime(text)) {
                    throw exception(AI_QUERY_PLAN_INVALID);
                }
                yield text;
            }
            default -> {
                if (!(value instanceof String text) || text.isBlank() || text.length() > MAX_VALUE_LENGTH) {
                    throw exception(AI_QUERY_PLAN_INVALID);
                }
                if (AiSemanticTypes.ENUM.equals(field.type())
                        && !field.enumValues().contains(text)) {
                    throw exception(AI_QUERY_PLAN_INVALID);
                }
                yield text;
            }
        };
    }

    private static boolean ordered(Object lower, Object upper, String type) {
        return switch (type) {
            case AiSemanticTypes.NUMBER, AiSemanticTypes.DECIMAL ->
                ((Number) lower).doubleValue() <= ((Number) upper).doubleValue();
            case AiSemanticTypes.DATE, AiSemanticTypes.DATETIME -> ((String) lower).compareTo((String) upper) <= 0;
            default -> false;
        };
    }

    private static ValidatedQueryPlan.TimeWindow parseTimeRange(
            Object value,
            ResolvedDatasetVersion dataset,
            Map<String, AiDatasetDefinition.Field> fieldCatalog,
            Instant now) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof Map<?, ?> raw)) {
            throw exception(AI_QUERY_PLAN_INVALID);
        }
        Map<String, Object> item = new LinkedHashMap<>();
        raw.forEach((key, entry) -> item.put(String.valueOf(key), entry));
        requireKeys(item, Set.of("field", "startInclusive", "endExclusive", "timezone"));
        String code = String.valueOf(item.get("field"));
        AiDatasetDefinition.Field field = fieldCatalog.get(code);
        if (field == null || !AiSemanticTypes.TIME_TYPES.contains(field.type())) {
            throw unknownFieldCode(code, fieldCatalog);
        }
        String timezone = String.valueOf(item.get("timezone"));
        if (!TIMEZONE_PATTERN.matcher(timezone).matches()) {
            throw exception(AI_QUERY_PLAN_INVALID);
        }
        AiDatasetDefinition.TimeSemantics declaredTime = dataset.definition().time();
        if (declaredTime != null && !declaredTime.timezone().equals(timezone)) {
            // 时区口径不一致会改变"上个月"的边界：必须澄清/拒绝，不能按本地时区猜
            throw exception(AI_QUERY_CLARIFICATION_REQUIRED);
        }
        OffsetDateTime start = parseInstant(item.get("startInclusive"));
        OffsetDateTime end = parseInstant(item.get("endExclusive"));
        if (start == null || end == null || !start.toInstant().isBefore(end.toInstant())) {
            throw exception(AI_QUERY_PLAN_INVALID);
        }
        Duration window = Duration.between(start.toInstant(), end.toInstant());
        if (window.toDays() > MAX_WINDOW_DAYS) {
            throw exception(AI_QUERY_PLAN_INVALID);
        }
        if (end.toInstant().isAfter(now.plus(Duration.ofDays(MAX_FUTURE_DAYS)))
                || start.toInstant().isBefore(now.minus(Duration.ofDays(MAX_PAST_DAYS)))) {
            throw exception(AI_QUERY_PLAN_INVALID);
        }
        String granularity = declaredTime == null ? null : declaredTime.granularity();
        return new ValidatedQueryPlan.TimeWindow(
                code, field.sourceColumn(), start.toString(), end.toString(), timezone, granularity);
    }

    private static List<ValidatedQueryPlan.Order> parseOrderBy(
            Object value,
            List<ValidatedQueryPlan.Metric> metrics,
            List<ValidatedQueryPlan.Dimension> dimensions,
            Map<String, AiDatasetDefinition.Field> fieldCatalog) {
        List<Map<String, Object>> items = objectList(value, MAX_ORDER_BY);
        List<ValidatedQueryPlan.Order> orderBy = new ArrayList<>();
        for (Map<String, Object> item : items) {
            requireKeys(item, Set.of("field", "direction"));
            String code = String.valueOf(item.get("field"));
            String direction = String.valueOf(item.get("direction"));
            if (!DIRECTIONS.contains(direction)) {
                throw exception(AI_QUERY_PLAN_INVALID);
            }
            boolean isMetric = metrics.stream().anyMatch(metric -> metric.code().equals(code));
            boolean isDimension =
                    dimensions.stream().anyMatch(dimension -> dimension.code().equals(code));
            if (!isMetric && !isDimension) {
                // 只能按本次查询结果里的字段排序：不能对未选中的字段排序（可能泄漏未选中的列）；
                // 命中别名同样按"措辞歧义"处理，与指标/维度/过滤保持一致
                boolean aliasHit = fieldCatalog.values().stream()
                        .anyMatch(field -> field.aliases().stream().anyMatch(alias -> alias.equalsIgnoreCase(code)));
                throw aliasHit ? exception(AI_QUERY_CLARIFICATION_REQUIRED) : exception(AI_QUERY_PLAN_INVALID);
            }
            orderBy.add(new ValidatedQueryPlan.Order(code, isMetric ? "METRIC" : "DIMENSION", direction));
        }
        return orderBy;
    }

    private static int parseLimit(Object value) {
        if (!(value instanceof Number number)) {
            throw exception(AI_QUERY_PLAN_INVALID);
        }
        int limit = number.intValue();
        if (limit < MIN_LIMIT || limit > MAX_LIMIT) {
            throw exception(AI_QUERY_PLAN_INVALID);
        }
        return limit;
    }

    /**
     * 未知字段码：命中某个字段的**别名**说明用户措辞有歧义（例如说"销售额"而目录里叫 net_amount），
     * 此时应当追问而不是猜；否则按非法字段拒绝。
     */
    private static RuntimeException unknownFieldCode(String code, Map<String, AiDatasetDefinition.Field> fieldCatalog) {
        if (code != null
                && fieldCatalog.values().stream()
                        .anyMatch(field -> field.aliases().stream().anyMatch(alias -> alias.equalsIgnoreCase(code)))) {
            return exception(AI_QUERY_CLARIFICATION_REQUIRED);
        }
        return exception(AI_QUERY_PLAN_INVALID);
    }

    private static List<String> codeList(Object value, int max, int min) {
        if (!(value instanceof List<?> list) || list.size() > max || list.size() < min) {
            throw exception(AI_QUERY_PLAN_INVALID);
        }
        List<String> codes = new ArrayList<>();
        Set<String> unique = new LinkedHashSet<>();
        for (Object item : list) {
            // 这里只做"形状"检查（非空、有长度上限、不重复）：是否合法由目录解析决定，
            // 否则中文别名等非 ASCII 措辞会在到达"歧义判定"之前就被误判成结构错误。
            if (!(item instanceof String code) || code.isBlank() || code.length() > 64 || !unique.add(code)) {
                throw exception(AI_QUERY_PLAN_INVALID);
            }
            codes.add(code);
        }
        return codes;
    }

    private static List<Map<String, Object>> objectList(Object value, int max) {
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> list) || list.size() > max) {
            throw exception(AI_QUERY_PLAN_INVALID);
        }
        List<Map<String, Object>> items = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> raw)) {
                throw exception(AI_QUERY_PLAN_INVALID);
            }
            Map<String, Object> mapped = new LinkedHashMap<>();
            raw.forEach((key, entry) -> mapped.put(String.valueOf(key), entry));
            items.add(mapped);
        }
        return items;
    }

    private static Map<String, Object> parseObject(String json) {
        Map<?, ?> raw;
        try {
            raw = JsonUtils.parseObject(json, Map.class);
        } catch (IllegalArgumentException notAnObject) {
            throw exception(AI_QUERY_PLAN_INVALID);
        }
        if (raw == null) {
            throw exception(AI_QUERY_PLAN_INVALID);
        }
        Map<String, Object> mapped = new LinkedHashMap<>();
        raw.forEach((key, value) -> mapped.put(String.valueOf(key), value));
        return mapped;
    }

    private static void requireKeys(Map<String, Object> values, Set<String> allowed) {
        for (String key : values.keySet()) {
            if (!allowed.contains(key)) {
                throw exception(AI_QUERY_PLAN_INVALID);
            }
        }
    }

    private static OffsetDateTime parseInstant(Object value) {
        if (!(value instanceof String text) || !isIsoDateTime(text)) {
            return null;
        }
        try {
            return OffsetDateTime.parse(text);
        } catch (DateTimeParseException notAnOffset) {
            return null;
        }
    }

    private static boolean isIsoDateTime(String text) {
        try {
            OffsetDateTime.parse(text);
            return true;
        } catch (DateTimeParseException invalid) {
            return false;
        }
    }

    /** 规范化：供调用方构造"同义"判定（大小写与空白不改变逻辑码）。 */
    public static String normalizeCode(String code) {
        return code == null ? null : code.trim().toLowerCase(Locale.ROOT);
    }
}

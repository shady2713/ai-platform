package com.basicframework.module.ai.domain.semantic;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_DATASET_ALIAS_AMBIGUOUS;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_DATASET_DEFINITION_INVALID;

import com.basicframework.framework.common.util.json.JsonUtils;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 数据集语义定义（D04）：字段/指标/维度/枚举/时间/单位/粒度 + 逐字段权限策略的**唯一校验入口**。
 *
 * <p>为什么定义要独立成不可变对象：它同时是（1）模型可见的语义面、（2）SQL 编译的唯一字段来源、
 * （3）版本内容哈希的输入。三者必须来自同一份规范化结果，否则"模型看到的"和"实际执行的"会漂移。
 *
 * <p>校验原则与连接器配置一致：白名单键 + 严格取值 + 未知一律拒绝（不猜测、不忽略）。
 * 具体规则：
 * <ul>
 *   <li><b>命名空间共享</b>：字段、指标、维度名在同一个命名空间内，重名即拒绝（QueryPlan 只按名字引用）；</li>
 *   <li><b>权限策略必填</b>：每个字段与指标都必须声明 {@code visibility}，
 *       {@code RESTRICTED} 必须给出 {@code permission} 权限码（"无权限策略不可发布"的静态部分）；</li>
 *   <li><b>别名可判定歧义</b>：别名大小写不敏感，重复、或与任一字段/指标/维度名冲突即拒绝；</li>
 *   <li><b>引用完整性</b>：时间字段必须是已声明的日期/时间字段，指标/维度必须引用已声明字段，
 *       SUM/AVG 只能作用于数值字段。</li>
 * </ul>
 */
public final class AiDatasetDefinition {

    /** 字段数上限。 */
    public static final int MAX_FIELDS = 64;

    /** 指标数上限。 */
    public static final int MAX_METRICS = 32;

    /** 维度数上限。 */
    public static final int MAX_DIMENSIONS = 32;

    /** 单字段别名数上限。 */
    public static final int MAX_ALIASES = 8;

    /** 枚举取值数上限。 */
    public static final int MAX_ENUM_VALUES = 64;

    /** 定义文本长度上限（列容量 8000，留出余量）。 */
    public static final int MAX_DEFINITION_LENGTH = 7_000;

    /** 逻辑名（字段/指标/维度）：与 QueryPlan 契约同一模式。 */
    private static final Pattern NAME_PATTERN = Pattern.compile("^[a-z][a-z0-9_]{0,63}$");

    /** 上游列名：只允许标识符字符。 */
    private static final Pattern COLUMN_PATTERN = Pattern.compile("^[A-Za-z0-9_]{1,64}$");

    /** 别名：字母（含中文）、数字、下划线、连字符。 */
    private static final Pattern ALIAS_PATTERN = Pattern.compile("^[\\p{L}\\p{N}_-]{1,64}$");

    /** 权限码：与菜单权限同一模式。 */
    private static final Pattern PERMISSION_PATTERN = Pattern.compile("^[a-z][a-z0-9:_-]{2,63}$");

    /** 时区：与 QueryPlan 契约同一模式。 */
    private static final Pattern TIMEZONE_PATTERN = Pattern.compile("^[A-Za-z_]+(?:/[A-Za-z0-9_+.-]+)*$");

    private static final Set<String> TOP_LEVEL_KEYS = Set.of("grain", "time", "fields", "metrics", "dimensions");

    private static final Set<String> FIELD_KEYS =
            Set.of("name", "sourceColumn", "type", "unit", "enumValues", "aliases", "visibility", "permission");

    private static final Set<String> METRIC_KEYS =
            Set.of("name", "field", "aggregation", "unit", "visibility", "permission");

    private static final Set<String> DIMENSION_KEYS = Set.of("name", "field");

    private static final Set<String> TIME_KEYS = Set.of("field", "granularity", "timezone");

    private static final Set<String> UNITS = Set.of("NONE", "CURRENCY", "PERCENT", "COUNT", "DURATION");

    private static final Set<String> VISIBILITIES = Set.of("PUBLIC", "INTERNAL", "RESTRICTED");

    private static final Set<String> AGGREGATIONS = Set.of("SUM", "AVG", "COUNT", "COUNT_DISTINCT", "MIN", "MAX");

    private static final Set<String> GRANULARITIES = Set.of("HOUR", "DAY", "WEEK", "MONTH", "QUARTER", "YEAR");

    private final String grain;

    private final TimeSemantics time;

    private final List<Field> fields;

    private final List<Metric> metrics;

    private final List<Dimension> dimensions;

    private final Map<String, Field> fieldsByName;

    private AiDatasetDefinition(
            String grain, TimeSemantics time, List<Field> fields, List<Metric> metrics, List<Dimension> dimensions) {
        this.grain = grain;
        this.time = time;
        this.fields = List.copyOf(fields);
        this.metrics = List.copyOf(metrics);
        this.dimensions = List.copyOf(dimensions);
        Map<String, Field> byName = new LinkedHashMap<>();
        fields.forEach(field -> byName.put(field.name(), field));
        this.fieldsByName = Map.copyOf(byName);
    }

    /** 解析并校验语义定义（不合规一律抛稳定错误码）。 */
    public static AiDatasetDefinition parse(String definitionJson) {
        if (definitionJson == null || definitionJson.isBlank() || definitionJson.length() > MAX_DEFINITION_LENGTH) {
            throw exception(AI_DATASET_DEFINITION_INVALID);
        }
        Map<?, ?> raw;
        try {
            raw = JsonUtils.parseObject(definitionJson, Map.class);
        } catch (IllegalArgumentException notAnObject) {
            throw exception(AI_DATASET_DEFINITION_INVALID);
        }
        if (raw == null) {
            throw exception(AI_DATASET_DEFINITION_INVALID);
        }
        Map<String, Object> values = new LinkedHashMap<>();
        raw.forEach((key, value) -> values.put(String.valueOf(key), value));
        requireKeys(values, TOP_LEVEL_KEYS);

        String grain = stringValue(values.get("grain"), 128);
        List<Field> fields = parseFields(values.get("fields"));
        List<Metric> metrics = parseMetrics(values.get("metrics"), fields);
        List<Dimension> dimensions = parseDimensions(values.get("dimensions"), fields);
        TimeSemantics time = parseTime(values.get("time"), fields);
        AiDatasetDefinition definition = new AiDatasetDefinition(grain, time, fields, metrics, dimensions);
        definition.requireUniqueNames(fields, metrics, dimensions);
        definition.requireUnambiguousAliases(fields, metrics, dimensions);
        return definition;
    }

    /** 规范化 JSON（键序固定；schemaHash 与落库内容都以它为准）。 */
    public String canonicalJson() {
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("grain", grain);
        canonical.put(
                "time",
                time == null
                        ? null
                        : ordered(
                                List.of("field", "granularity", "timezone"),
                                java.util.Arrays.asList(time.field(), time.granularity(), time.timezone())));
        List<Object> fieldList = new ArrayList<>();
        for (Field field : fields) {
            fieldList.add(ordered(
                    List.of(
                            "name",
                            "sourceColumn",
                            "type",
                            "unit",
                            "enumValues",
                            "aliases",
                            "visibility",
                            "permission"),
                    java.util.Arrays.asList(
                            field.name(),
                            field.sourceColumn(),
                            field.type(),
                            field.unit(),
                            field.enumValues().isEmpty() ? null : field.enumValues(),
                            field.aliases(),
                            field.visibility(),
                            field.permission())));
        }
        canonical.put("fields", fieldList);
        List<Object> metricList = new ArrayList<>();
        for (Metric metric : metrics) {
            metricList.add(ordered(
                    List.of("name", "field", "aggregation", "unit", "visibility", "permission"),
                    java.util.Arrays.asList(
                            metric.name(),
                            metric.field(),
                            metric.aggregation(),
                            metric.unit(),
                            metric.visibility(),
                            metric.permission())));
        }
        canonical.put("metrics", metricList);
        List<Object> dimensionList = new ArrayList<>();
        for (Dimension dimension : dimensions) {
            dimensionList.add(
                    ordered(List.of("name", "field"), java.util.Arrays.asList(dimension.name(), dimension.field())));
        }
        canonical.put("dimensions", dimensionList);
        return JsonUtils.toJsonString(canonical);
    }

    /** 定义内容哈希（sha256 hex）：同一份定义永远得到同一个哈希，顺序与键序不影响。 */
    public String schemaHash() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonicalJson().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 不可用", impossible);
        }
    }

    public String grain() {
        return grain;
    }

    public TimeSemantics time() {
        return time;
    }

    public List<Field> fields() {
        return fields;
    }

    public List<Metric> metrics() {
        return metrics;
    }

    public List<Dimension> dimensions() {
        return dimensions;
    }

    /** 定义引用的上游列（去重后按声明顺序）。 */
    public Set<String> sourceColumns() {
        Set<String> columns = new LinkedHashSet<>();
        fields.forEach(field -> columns.add(field.sourceColumn()));
        return columns;
    }

    /** 按逻辑名取字段（不存在返回 null）。 */
    public Field field(String name) {
        return name == null ? null : fieldsByName.get(name.toLowerCase(Locale.ROOT));
    }

    /** 字段是否声明了权限策略（RESTRICTED 必须给出权限码）。 */
    public static boolean hasPermissionPolicy(String visibility, String permission) {
        if (visibility == null || !VISIBILITIES.contains(visibility)) {
            return false;
        }
        if (!"RESTRICTED".equals(visibility)) {
            return true;
        }
        // 不能写成 String.valueOf(permission)：null 会变成字符串 "null" 而"通过"权限码校验
        return permission != null && PERMISSION_PATTERN.matcher(permission).matches();
    }

    private void requireUniqueNames(List<Field> fields, List<Metric> metrics, List<Dimension> dimensions) {
        Set<String> names = new LinkedHashSet<>();
        for (String name : namesOf(
                fields.stream().map(Field::name).toList(),
                metrics.stream().map(Metric::name).toList(),
                dimensions.stream().map(Dimension::name).toList())) {
            if (!names.add(name)) {
                throw exception(AI_DATASET_DEFINITION_INVALID);
            }
        }
    }

    private static List<String> namesOf(List<String> fields, List<String> metrics, List<String> dimensions) {
        List<String> names = new ArrayList<>(fields);
        names.addAll(metrics);
        names.addAll(dimensions);
        return names;
    }

    /** 别名歧义判定：别名之间、别名与任何逻辑名（字段/指标/维度）之间都不能重复。 */
    private void requireUnambiguousAliases(List<Field> fields, List<Metric> metrics, List<Dimension> dimensions) {
        Set<String> logicalNames = new LinkedHashSet<>(namesOf(
                fields.stream().map(Field::name).toList(),
                metrics.stream().map(Metric::name).toList(),
                dimensions.stream().map(Dimension::name).toList()));
        Set<String> seen = new LinkedHashSet<>();
        for (Field field : fields) {
            for (String alias : field.aliases()) {
                String normalized = alias.toLowerCase(Locale.ROOT);
                if (logicalNames.contains(normalized) || !seen.add(normalized)) {
                    throw exception(AI_DATASET_ALIAS_AMBIGUOUS);
                }
            }
        }
    }

    private static List<Field> parseFields(Object value) {
        List<Map<String, Object>> items = objectList(value, MAX_FIELDS, 1);
        List<Field> fields = new ArrayList<>();
        for (Map<String, Object> item : items) {
            requireKeys(item, FIELD_KEYS);
            String name = name(item.get("name"));
            String sourceColumn = requiredMatch(item.get("sourceColumn"), COLUMN_PATTERN);
            String type = enumValue(item.get("type"), AiSemanticTypes.TYPES);
            String unit = enumValue(item.get("unit") == null ? "NONE" : item.get("unit"), UNITS);
            String visibility = enumValue(item.get("visibility"), VISIBILITIES);
            String permission = optionalString(item.get("permission"), 64);
            if (!hasPermissionPolicy(visibility, permission)) {
                throw exception(AI_DATASET_DEFINITION_INVALID);
            }
            List<String> enumValues = parseEnumValues(item.get("enumValues"), type);
            List<String> aliases = parseAliases(item.get("aliases"));
            fields.add(new Field(name, sourceColumn, type, unit, enumValues, aliases, visibility, permission));
        }
        return fields;
    }

    private static List<String> parseEnumValues(Object value, String type) {
        if (!AiSemanticTypes.ENUM.equals(type)) {
            // 非枚举字段不声明取值；空数组等价于未声明（规范化输出可能带出空数组）
            if (value != null && !(value instanceof List<?> empty && empty.isEmpty())) {
                throw exception(AI_DATASET_DEFINITION_INVALID);
            }
            return List.of();
        }
        if (!(value instanceof List<?> list) || list.isEmpty() || list.size() > MAX_ENUM_VALUES) {
            throw exception(AI_DATASET_DEFINITION_INVALID);
        }
        List<String> values = new ArrayList<>();
        Set<String> unique = new LinkedHashSet<>();
        for (Object item : list) {
            String text = optionalString(item, 64);
            if (text == null || !unique.add(text)) {
                throw exception(AI_DATASET_DEFINITION_INVALID);
            }
            values.add(text);
        }
        return values;
    }

    private static List<String> parseAliases(Object value) {
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> list) || list.size() > MAX_ALIASES) {
            throw exception(AI_DATASET_DEFINITION_INVALID);
        }
        List<String> aliases = new ArrayList<>();
        for (Object item : list) {
            aliases.add(requiredMatch(item, ALIAS_PATTERN));
        }
        return aliases;
    }

    private static List<Metric> parseMetrics(Object value, List<Field> fields) {
        if (value == null) {
            return List.of();
        }
        List<Map<String, Object>> items = objectList(value, MAX_METRICS, 0);
        List<Metric> metrics = new ArrayList<>();
        for (Map<String, Object> item : items) {
            requireKeys(item, METRIC_KEYS);
            String name = name(item.get("name"));
            String fieldName = name(item.get("field"));
            Field field = fields.stream()
                    .filter(candidate -> candidate.name().equals(fieldName))
                    .findFirst()
                    .orElseThrow(() -> exception(AI_DATASET_DEFINITION_INVALID));
            String aggregation = enumValue(item.get("aggregation"), AGGREGATIONS);
            if (("SUM".equals(aggregation) || "AVG".equals(aggregation))
                    && !AiSemanticTypes.AGGREGATABLE.contains(field.type())) {
                throw exception(AI_DATASET_DEFINITION_INVALID);
            }
            String unit = enumValue(item.get("unit") == null ? "NONE" : item.get("unit"), UNITS);
            String visibility = enumValue(item.get("visibility"), VISIBILITIES);
            String permission = optionalString(item.get("permission"), 64);
            if (!hasPermissionPolicy(visibility, permission)) {
                throw exception(AI_DATASET_DEFINITION_INVALID);
            }
            metrics.add(new Metric(name, fieldName, aggregation, unit, visibility, permission));
        }
        return metrics;
    }

    private static List<Dimension> parseDimensions(Object value, List<Field> fields) {
        if (value == null) {
            return List.of();
        }
        List<Map<String, Object>> items = objectList(value, MAX_DIMENSIONS, 0);
        List<Dimension> dimensions = new ArrayList<>();
        for (Map<String, Object> item : items) {
            requireKeys(item, DIMENSION_KEYS);
            String name = name(item.get("name"));
            String fieldName = name(item.get("field"));
            if (fields.stream().noneMatch(candidate -> candidate.name().equals(fieldName))) {
                throw exception(AI_DATASET_DEFINITION_INVALID);
            }
            dimensions.add(new Dimension(name, fieldName));
        }
        return dimensions;
    }

    private static TimeSemantics parseTime(Object value, List<Field> fields) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof Map<?, ?> raw)) {
            throw exception(AI_DATASET_DEFINITION_INVALID);
        }
        Map<String, Object> item = new LinkedHashMap<>();
        raw.forEach((key, entry) -> item.put(String.valueOf(key), entry));
        requireKeys(item, TIME_KEYS);
        String fieldName = name(item.get("field"));
        Field field = fields.stream()
                .filter(candidate -> candidate.name().equals(fieldName))
                .findFirst()
                .orElseThrow(() -> exception(AI_DATASET_DEFINITION_INVALID));
        if (!AiSemanticTypes.TIME_TYPES.contains(field.type())) {
            throw exception(AI_DATASET_DEFINITION_INVALID);
        }
        String granularity = enumValue(item.get("granularity"), GRANULARITIES);
        String timezone = requiredMatch(item.get("timezone"), TIMEZONE_PATTERN);
        return new TimeSemantics(fieldName, granularity, timezone);
    }

    private static List<Map<String, Object>> objectList(Object value, int max, int min) {
        if (!(value instanceof List<?> list) || list.size() > max || list.size() < min) {
            throw exception(AI_DATASET_DEFINITION_INVALID);
        }
        List<Map<String, Object>> items = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> raw)) {
                throw exception(AI_DATASET_DEFINITION_INVALID);
            }
            Map<String, Object> mapped = new LinkedHashMap<>();
            raw.forEach((key, entry) -> mapped.put(String.valueOf(key), entry));
            items.add(mapped);
        }
        return items;
    }

    private static void requireKeys(Map<String, Object> values, Set<String> allowed) {
        for (String key : values.keySet()) {
            if (!allowed.contains(key)) {
                throw exception(AI_DATASET_DEFINITION_INVALID);
            }
        }
    }

    private static String name(Object value) {
        return requiredMatch(value, NAME_PATTERN);
    }

    private static String requiredMatch(Object value, Pattern pattern) {
        String text = optionalString(value, 128);
        if (text == null || !pattern.matcher(text).matches()) {
            throw exception(AI_DATASET_DEFINITION_INVALID);
        }
        return text;
    }

    private static String stringValue(Object value, int maxLength) {
        String text = optionalString(value, maxLength);
        if (text == null) {
            throw exception(AI_DATASET_DEFINITION_INVALID);
        }
        return text;
    }

    private static String optionalString(Object value, int maxLength) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        if (text.isEmpty() || text.length() > maxLength) {
            return null;
        }
        return text;
    }

    private static String enumValue(Object value, Set<String> allowed) {
        String text = optionalString(value, 32);
        String normalized = text == null ? null : text.toUpperCase(Locale.ROOT);
        if (normalized == null || !allowed.contains(normalized)) {
            throw exception(AI_DATASET_DEFINITION_INVALID);
        }
        return normalized;
    }

    /** 按固定键序重排（值允许为 null，例如 PUBLIC 字段没有权限码）。 */
    private static Map<String, Object> ordered(List<String> keys, List<Object> values) {
        Map<String, Object> ordered = new LinkedHashMap<>();
        for (int index = 0; index < keys.size(); index++) {
            ordered.put(keys.get(index), values.get(index));
        }
        return ordered;
    }

    /** 字段：逻辑名 + 上游列 + 语义类型/单位/枚举 + 别名 + 权限策略。 */
    public record Field(
            String name,
            String sourceColumn,
            String type,
            String unit,
            List<String> enumValues,
            List<String> aliases,
            String visibility,
            String permission) {

        public Field {
            enumValues = enumValues == null ? List.of() : List.copyOf(enumValues);
            aliases = aliases == null ? List.of() : List.copyOf(aliases);
        }
    }

    /** 指标：逻辑名 + 引用字段 + 聚合方式 + 单位 + 权限策略。 */
    public record Metric(
            String name, String field, String aggregation, String unit, String visibility, String permission) {}

    /** 维度：逻辑名 + 引用字段。 */
    public record Dimension(String name, String field) {}

    /** 时间语义：时间字段 + 粒度 + 时区。 */
    public record TimeSemantics(String field, String granularity, String timezone) {}
}

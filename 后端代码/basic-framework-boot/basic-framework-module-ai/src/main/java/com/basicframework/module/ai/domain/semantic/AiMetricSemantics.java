package com.basicframework.module.ai.domain.semantic;

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
 * 跨源指标口径定义（Y03）：币种 / 单位 / 时区 / 时间窗口 / 主键粒度 / 聚合顺序的**唯一**登记入口。
 *
 * <p>为什么口径要独立于数据集定义：单数据集定义（D04）回答"这个数据集里有哪些字段和指标"，
 * 而本类回答的是**跨系统相加是否成立**——币种能否相加、时区窗口是否一致、各来源的主键粒度是否
 * 会让同一事实被数两次。把这两件事混在一起，就只能靠"人知道这两个数能不能加"，而那正是
 * 模型最容易猜错的地方。
 *
 * <p>六项口径缺一不可，且**都不允许推断补全**：
 * <ul>
 *   <li><b>币种 currency</b>：非 {@code NONE} 时必须与换算规则的目标币种一致（无规则即禁止跨币种求和）；</li>
 *   <li><b>单位 unit</b>：跨源相加的各方必须同单位（百分比不能与金额相加）；</li>
 *   <li><b>时区 timezone</b>：时间窗口的边界按哪个时区切，"上个月"才有确定含义；</li>
 *   <li><b>时间窗口 timeWindow</b>：口径级的窗口策略（各来源必须落在同一窗口内）；</li>
 *   <li><b>主键粒度 primaryKeyGrain</b>：每个来源各自的主键字段，扇出判定的事实基础；</li>
 *   <li><b>聚合顺序 aggregationOrder</b>：必须先按各自主键粒度聚合、再关联。</li>
 * </ul>
 *
 * <p>与 Y02 的一致性：定义内容哈希（{@link #definitionHash()}）是版本冻结与读取时重算比对的依据，
 * 键序固定、登记顺序不影响哈希，因此"内容相同但顺序不同"的两个版本不会互相判定为已改动。
 */
public final class AiMetricSemantics {

    /** 来源数上限（跨源聚合的来源是有界的，超出即拒绝而不是截断）。 */
    public static final int MAX_SOURCES = 16;

    /** 逻辑名（来源角色 / 粒度键）：与 QueryPlan 契约同一模式。 */
    private static final Pattern NAME_PATTERN = Pattern.compile("^[a-z][a-z0-9_]{0,63}$");

    /** 币种：ISO 4217 三字母码或 {@code NONE}（非金额口径）。 */
    private static final Pattern CURRENCY_PATTERN = Pattern.compile("^(NONE|[A-Z]{3})$");

    /** 时区：与 DatasetDefinition/QueryPlan 契约同一模式。 */
    private static final Pattern TIMEZONE_PATTERN = Pattern.compile("^[A-Za-z_]+(?:/[A-Za-z0-9_+.-]+)*$");

    /** 换算规则标识。 */
    private static final Pattern RULE_PATTERN = Pattern.compile("^[a-z][a-z0-9_]{0,63}$");

    private static final Set<String> TOP_LEVEL_KEYS = Set.of(
            "metricCode", "unit", "currency", "timezone", "timeWindow", "sources", "aggregationOrder", "conversion");

    private static final Set<String> SOURCE_KEYS = Set.of(
            "role",
            "datasetCode",
            "datasetVersion",
            "mappingRevision",
            "unit",
            "currency",
            "timezone",
            "primaryKey",
            "optional");

    private static final Set<String> CONVERSION_KEYS = Set.of("rule", "targetCurrency");

    /** 单位词表：与 D04 数据集定义的 UNITS 同一集合（跨源相加只认这些）。 */
    private static final Set<String> UNITS = Set.of("NONE", "CURRENCY", "PERCENT", "COUNT", "DURATION");

    /** 时间窗口策略：闭开区间 + 各来源必须同窗。 */
    private static final Set<String> TIME_WINDOWS =
            Set.of("CALENDAR_MONTH", "CALENDAR_QUARTER", "CALENDAR_YEAR", "ROLLING_30D");

    private final String metricCode;

    private final String unit;

    private final String currency;

    private final String timezone;

    private final String timeWindow;

    private final List<Source> sources;

    private final List<String> aggregationOrder;

    private final Conversion conversion;

    private AiMetricSemantics(
            String metricCode,
            String unit,
            String currency,
            String timezone,
            String timeWindow,
            List<Source> sources,
            List<String> aggregationOrder,
            Conversion conversion) {
        this.metricCode = metricCode;
        this.unit = unit;
        this.currency = currency;
        this.timezone = timezone;
        this.timeWindow = timeWindow;
        this.sources = List.copyOf(sources);
        this.aggregationOrder = List.copyOf(aggregationOrder);
        this.conversion = conversion;
    }

    /** 解析并校验跨源口径定义（不合规一律抛稳定错误码）。 */
    public static AiMetricSemantics parse(String definitionJson) {
        Map<String, Object> values = AiMetricSemanticsJson.readObject(definitionJson);
        AiMetricSemanticsJson.requireKeys(values, TOP_LEVEL_KEYS);
        String metricCode = AiMetricSemanticsJson.name(values.get("metricCode"));
        String unit = AiMetricSemanticsJson.enumValue(values.get("unit"), UNITS);
        String currency = AiMetricSemanticsJson.currency(values.get("currency"));
        String timezone = AiMetricSemanticsJson.timezone(values.get("timezone"));
        String timeWindow = AiMetricSemanticsJson.enumValue(values.get("timeWindow"), TIME_WINDOWS);
        List<Source> sources = parseSources(values.get("sources"), unit, currency, timezone);
        List<String> aggregationOrder = parseAggregationOrder(values.get("aggregationOrder"), sources);
        Conversion conversion = parseConversion(values.get("conversion"), currency);
        AiMetricSemantics semantics = new AiMetricSemantics(
                metricCode, unit, currency, timezone, timeWindow, sources, aggregationOrder, conversion);
        semantics.requireConsistentConversion();
        return semantics;
    }

    private static List<Source> parseSources(Object value, String unit, String currency, String timezone) {
        List<Map<String, Object>> items = AiMetricSemanticsJson.objectList(value, MAX_SOURCES, 1);
        List<Source> sources = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Map<String, Object> item : items) {
            AiMetricSemanticsJson.requireKeys(item, SOURCE_KEYS);
            String role = AiMetricSemanticsJson.name(item.get("role"));
            String datasetCode = AiMetricSemanticsJson.name(item.get("datasetCode"));
            Integer datasetVersion = AiMetricSemanticsJson.positiveInt(item.get("datasetVersion"));
            Long mappingRevision = AiMetricSemanticsJson.positiveLong(item.get("mappingRevision"));
            String sourceUnit = AiMetricSemanticsJson.enumValue(item.get("unit"), UNITS);
            String sourceCurrency = AiMetricSemanticsJson.currency(item.get("currency"));
            String sourceTimezone = AiMetricSemanticsJson.timezone(item.get("timezone"));
            List<String> primaryKey = AiMetricSemanticsJson.nameList(item.get("primaryKey"), 4, 1);
            boolean optional = AiMetricSemanticsJson.boolValue(item.get("optional"));
            if (!seen.add(datasetCode + "#" + datasetVersion)) {
                throw AiMetricSemanticsErrors.duplicateSource();
            }
            // 币种/单位/时区允许与口径不同（这正是需要被判定的差异），但必须显式给出，
            // 不允许缺省成口径值——缺省会让"来源其实没登记币种"看起来像"币种一致"。
            if (sourceUnit == null || sourceCurrency == null || sourceTimezone == null) {
                throw AiMetricSemanticsErrors.missingCaliber();
            }
            sources.add(new Source(
                    role,
                    datasetCode,
                    datasetVersion,
                    mappingRevision,
                    sourceUnit,
                    sourceCurrency,
                    sourceTimezone,
                    primaryKey,
                    optional));
        }
        return sources;
    }

    private static List<String> parseAggregationOrder(Object value, List<Source> sources) {
        List<String> order = AiMetricSemanticsJson.nameList(value, MAX_SOURCES, 1);
        Set<String> roles = new LinkedHashSet<>();
        sources.forEach(source -> roles.add(source.role()));
        if (!new LinkedHashSet<>(order).equals(roles)) {
            // 聚合顺序必须恰好覆盖每个来源角色：多一个少一个都说明有人被漏掉或凭空多出
            throw AiMetricSemanticsErrors.aggregationOrderConflict();
        }
        return order;
    }

    private static Conversion parseConversion(Object value, String currency) {
        if (value == null) {
            return null;
        }
        Map<String, Object> item = AiMetricSemanticsJson.readObject(value);
        AiMetricSemanticsJson.requireKeys(item, CONVERSION_KEYS);
        String rule = AiMetricSemanticsJson.rule(item.get("rule"));
        String targetCurrency = AiMetricSemanticsJson.currency(item.get("targetCurrency"));
        if (targetCurrency == null) {
            throw AiMetricSemanticsErrors.conversionRuleConflict();
        }
        return new Conversion(rule, targetCurrency);
    }

    /**
     * 换算规则与口径的一致性：有换算规则时目标币种必须与口径币种一致；
     * 没有换算规则时口径不得声明金额币种之外的多币种求和（由 Facts 在聚合时逐来源判定）。
     */
    private void requireConsistentConversion() {
        if (conversion == null) {
            return;
        }
        if (!conversion.targetCurrency().equals(currency)) {
            throw AiMetricSemanticsErrors.conversionRuleConflict();
        }
    }

    /** 规范化 JSON（键序固定；definitionHash 与落库内容都以它为准）。 */
    public String canonicalJson() {
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("metricCode", metricCode);
        canonical.put("unit", unit);
        canonical.put("currency", currency);
        canonical.put("timezone", timezone);
        canonical.put("timeWindow", timeWindow);
        List<Object> sourceList = new ArrayList<>();
        for (Source source : sources) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("role", source.role());
            item.put("datasetCode", source.datasetCode());
            item.put("datasetVersion", source.datasetVersion());
            item.put("mappingRevision", source.mappingRevision());
            item.put("unit", source.unit());
            item.put("currency", source.currency());
            item.put("timezone", source.timezone());
            item.put("primaryKey", source.primaryKey());
            item.put("optional", source.optional());
            sourceList.add(item);
        }
        canonical.put("sources", sourceList);
        canonical.put("aggregationOrder", aggregationOrder);
        if (conversion == null) {
            canonical.put("conversion", null);
        } else {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("rule", conversion.rule());
            item.put("targetCurrency", conversion.targetCurrency());
            canonical.put("conversion", item);
        }
        return JsonUtils.toJsonString(canonical);
    }

    /** 口径内容哈希（sha256 hex）：同一份口径永远得到同一个哈希，登记顺序不影响。 */
    public String definitionHash() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonicalJson().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 不可用", impossible);
        }
    }

    public String metricCode() {
        return metricCode;
    }

    public String unit() {
        return unit;
    }

    public String currency() {
        return currency;
    }

    public String timezone() {
        return timezone;
    }

    public String timeWindow() {
        return timeWindow;
    }

    public List<Source> sources() {
        return sources;
    }

    public List<String> aggregationOrder() {
        return aggregationOrder;
    }

    public Conversion conversion() {
        return conversion;
    }

    /** 按数据集标识 + 版本定位来源声明（未声明返回 null：绝不"就近取一个版本"）。 */
    public Source sourceOf(String datasetCode, Integer datasetVersion) {
        for (Source source : sources) {
            if (source.datasetCode().equals(datasetCode)
                    && source.datasetVersion().equals(datasetVersion)) {
                return source;
            }
        }
        return null;
    }

    public Source sourceByRole(String role) {
        for (Source source : sources) {
            if (source.role().equals(role)) {
                return source;
            }
        }
        return null;
    }

    /** 口径要求金额币种（决定是否需要换算规则才能跨币种求和）。 */
    public boolean requiresCurrency() {
        return "CURRENCY".equals(unit);
    }

    /** 一个来源的登记事实（不可变 record）。 */
    public record Source(
            String role,
            String datasetCode,
            Integer datasetVersion,
            Long mappingRevision,
            String unit,
            String currency,
            String timezone,
            List<String> primaryKey,
            boolean optional) {

        public Source {
            primaryKey = primaryKey == null ? List.of() : List.copyOf(primaryKey);
        }

        /** 主键粒度的稳定描述（错误上下文与页面展示用）。 */
        public String grainText() {
            return String.join("+", primaryKey);
        }
    }

    /** 币种换算规则（只登记规则标识与目标币种，**不**登记汇率：汇率是数据不是语义）。 */
    public record Conversion(String rule, String targetCurrency) {}

    /** 口径是否声明了金额单位（供 Facts 判定"币种缺失"用）。 */
    public static boolean isCurrencyUnit(String unit) {
        return unit != null && "CURRENCY".equals(unit.toUpperCase(Locale.ROOT));
    }
}

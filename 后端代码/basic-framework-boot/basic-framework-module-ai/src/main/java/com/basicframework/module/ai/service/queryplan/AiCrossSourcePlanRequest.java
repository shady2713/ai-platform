package com.basicframework.module.ai.service.queryplan;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_METRIC_PLAN_SELECTION_REQUIRED;

import com.basicframework.framework.common.util.json.JsonUtils;
import com.basicframework.module.ai.domain.semantic.AiMasterMappingFacts;
import com.basicframework.module.ai.domain.semantic.AiMetricSemantics;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 跨源查询计划的结构化选择（Y03）。
 *
 * <p>与 D05 的 {@code AiQueryPlanValidator} 一样，这里只接受**结构化选择**，
 * 不接受任何 SQL 片段：模型能表达的是"选哪些来源、按什么粒度聚合"，
 * 而不是"怎么写查询"。拼接语句的能力不进入这条路径，就不存在"提示词没压住"的窗口。
 *
 * <p>两条承重规则在**形状层**就固定下来，而不是等到语义层再判：
 * <ul>
 *   <li><b>每个来源都必须显式给出数据集版本与映射版本</b>：缺任何一个即抛
 *       {@code AI_METRIC_PLAN_SELECTION_REQUIRED}，不接受 {@code null}、
 *       {@code 0} 或"取当前版本"这类省略写法；</li>
 *   <li><b>口径版本必须显式给出</b>：否则无法确定"这条数字按哪份口径算出来的"。</li>
 * </ul>
 */
public final class AiCrossSourcePlanRequest {

    /** 逻辑名：与口径定义、QueryPlan 契约同一模式。 */
    private static final Pattern NAME_PATTERN = Pattern.compile("^[a-z][a-z0-9_]{0,63}$");

    private static final Set<String> TOP_LEVEL_KEYS = Set.of("semanticsRevision", "sources", "aggregationOrder");

    private static final Set<String> SOURCE_KEYS =
            Set.of("role", "datasetCode", "datasetVersion", "mappingRevision", "primaryKey", "preAggregated");

    private final Integer semanticsRevision;

    private final List<Selection> selections;

    private final List<String> aggregationOrder;

    private AiCrossSourcePlanRequest(
            Integer semanticsRevision, List<Selection> selections, List<String> aggregationOrder) {
        this.semanticsRevision = semanticsRevision;
        this.selections = List.copyOf(selections);
        this.aggregationOrder = List.copyOf(aggregationOrder);
    }

    /** 解析跨源计划（结构不合规一律抛稳定错误码，绝不"尽力修复"）。 */
    public static AiCrossSourcePlanRequest parse(String planJson) {
        if (planJson == null || planJson.isBlank() || planJson.length() > 7_000) {
            throw exception(AI_METRIC_PLAN_SELECTION_REQUIRED);
        }
        Map<?, ?> raw;
        try {
            raw = JsonUtils.parseObject(planJson, Map.class);
        } catch (IllegalArgumentException notAnObject) {
            throw exception(AI_METRIC_PLAN_SELECTION_REQUIRED);
        }
        if (raw == null) {
            throw exception(AI_METRIC_PLAN_SELECTION_REQUIRED);
        }
        Map<String, Object> plan = new LinkedHashMap<>();
        raw.forEach((key, value) -> plan.put(String.valueOf(key), value));
        for (String key : plan.keySet()) {
            if (!TOP_LEVEL_KEYS.contains(key)) {
                throw exception(AI_METRIC_PLAN_SELECTION_REQUIRED);
            }
        }
        // 口径版本必须显式：没有它就无法复现"按哪份口径算出来的"
        Integer revision = positiveInt(plan.get("semanticsRevision"));
        if (revision == null) {
            throw exception(AI_METRIC_PLAN_SELECTION_REQUIRED);
        }
        List<Selection> selections = parseSources(plan.get("sources"));
        // 聚合顺序不在这里判重：重复的顺序项是"顺序写错了"，应由校验器给出
        // AI_METRIC_AGGREGATION_ORDER_CONFLICT，而不是更笼统的"选择不合法"
        List<String> order = orderedNameList(plan.get("aggregationOrder"), AiCrossSourceQueryPlanValidator.MAX_SOURCES);
        if (selections.isEmpty() || order.isEmpty()) {
            throw exception(AI_METRIC_PLAN_SELECTION_REQUIRED);
        }
        return new AiCrossSourcePlanRequest(revision, selections, order);
    }

    private static List<Selection> parseSources(Object value) {
        if (!(value instanceof List<?> list)
                || list.isEmpty()
                || list.size() > AiCrossSourceQueryPlanValidator.MAX_SOURCES) {
            throw exception(AI_METRIC_PLAN_SELECTION_REQUIRED);
        }
        List<Selection> selections = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) {
                throw exception(AI_METRIC_PLAN_SELECTION_REQUIRED);
            }
            Map<String, Object> source = new LinkedHashMap<>();
            map.forEach((key, entry) -> source.put(String.valueOf(key), entry));
            for (String key : source.keySet()) {
                if (!SOURCE_KEYS.contains(key)) {
                    throw exception(AI_METRIC_PLAN_SELECTION_REQUIRED);
                }
            }
            String role = name(source.get("role"));
            String datasetCode = name(source.get("datasetCode"));
            Integer datasetVersion = positiveInt(source.get("datasetVersion"));
            Long mappingRevision = positiveLong(source.get("mappingRevision"));
            List<String> primaryKey = nameList(source.get("primaryKey"), 4);
            boolean preAggregated = boolValue(source.get("preAggregated"));
            // 显式性检查：数据集版本与映射版本缺任一即拒绝，不允许推断
            if (datasetVersion == null || mappingRevision == null) {
                throw exception(AI_METRIC_PLAN_SELECTION_REQUIRED);
            }
            if (!seen.add(datasetCode + "#" + datasetVersion)) {
                throw exception(AI_METRIC_PLAN_SELECTION_REQUIRED);
            }
            selections.add(
                    new Selection(role, datasetCode, datasetVersion, mappingRevision, primaryKey, preAggregated));
        }
        return selections;
    }

    public Integer semanticsRevision() {
        return semanticsRevision;
    }

    public List<Selection> selections() {
        return selections;
    }

    public List<String> aggregationOrder() {
        return aggregationOrder;
    }

    /**
     * 按角色定位已选来源（未选中返回 null）。
     *
     * <p>刻意**不**在这里把选择还原成 {@link AiMetricSemantics.Source}：那样得到的粒度来自计划
     * 自己，"计划自称的粒度是否等于口径登记的粒度"这道检查就会恒真。口径事实只能由
     * 校验器从已解析的口径定义里取。
     */
    public Selection selectionByRole(String role) {
        for (Selection selection : selections) {
            if (selection.role().equals(role)) {
                return selection;
            }
        }
        return null;
    }

    /** 计划哈希：钉住"这次选择与顺序"，同一份计划永远得到同一个哈希。 */
    public static String planHash(AiCrossSourcePlanRequest request, AiMetricSemantics semantics) {
        StringBuilder builder = new StringBuilder();
        builder.append("rev=").append(request.semanticsRevision()).append('\n');
        if (semantics != null) {
            builder.append("semantics=").append(semantics.definitionHash()).append('\n');
        }
        request.selections()
                .forEach(selection -> builder.append(selection.canonicalText()).append('\n'));
        builder.append("order=").append(String.join(",", request.aggregationOrder()));
        return AiMasterMappingFacts.digest(builder.toString());
    }

    /** 一个来源的显式选择。 */
    public record Selection(
            String role,
            String datasetCode,
            Integer datasetVersion,
            Long mappingRevision,
            List<String> primaryKey,
            boolean preAggregated) {

        public Selection {
            primaryKey = primaryKey == null ? List.of() : List.copyOf(primaryKey);
        }

        /** 稳定文本（哈希输入与错误上下文共用）。 */
        public String canonicalText() {
            return role + "|" + datasetCode + "|" + datasetVersion + "|" + mappingRevision + "|"
                    + String.join("+", primaryKey) + "|" + preAggregated;
        }
    }

    private static String name(Object value) {
        if (!(value instanceof String text)) {
            throw exception(AI_METRIC_PLAN_SELECTION_REQUIRED);
        }
        String trimmed = text.trim();
        if (!NAME_PATTERN.matcher(trimmed).matches()) {
            throw exception(AI_METRIC_PLAN_SELECTION_REQUIRED);
        }
        return trimmed;
    }

    /** 名称数组且**不允许重复**（粒度键用：同一粒度写两遍说明登记有误）。 */
    private static List<String> nameList(Object value, int max) {
        List<String> names = orderedNameList(value, max);
        if (new LinkedHashSet<>(names).size() != names.size()) {
            throw exception(AI_METRIC_PLAN_SELECTION_REQUIRED);
        }
        return names;
    }

    /** 名称数组（保序，重复交由语义层判定）。 */
    private static List<String> orderedNameList(Object value, int max) {
        if (!(value instanceof List<?> list) || list.isEmpty() || list.size() > max) {
            throw exception(AI_METRIC_PLAN_SELECTION_REQUIRED);
        }
        List<String> names = new ArrayList<>();
        for (Object item : list) {
            names.add(name(item));
        }
        return names;
    }

    private static Integer positiveInt(Object value) {
        if (!(value instanceof Number number) || number.intValue() < 1) {
            return null;
        }
        return number.intValue();
    }

    private static Long positiveLong(Object value) {
        if (!(value instanceof Number number) || number.longValue() < 1) {
            return null;
        }
        return number.longValue();
    }

    private static boolean boolValue(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean flag) {
            return flag;
        }
        throw exception(AI_METRIC_PLAN_SELECTION_REQUIRED);
    }

    /** 计划字节长度上限（与口径定义同一量级，防止超长 JSON 拖垮解析）。 */
    static int maxPlanLength() {
        return 7_000;
    }
}

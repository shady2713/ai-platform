package com.basicframework.module.ai.domain.query;

import com.basicframework.framework.common.util.json.JsonUtils;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 已校验查询计划（D05）：**只有校验器能建立**的计划，也是执行器唯一接受的输入。
 *
 * <p>为什么用"包内构造 + 不可变"而不是一个普通 DTO：计划一旦可被任意构造，
 * 模型输出、调用方拼装或修复重试都可能绕过校验直接进执行器。
 * 这里把构造器收进包内（只有 {@link AiQueryPlanValidator} 能 new），
 * 并且不提供"接受任意 String SQL"的方法，从类型层面关掉这条路径。
 *
 * <p>{@link #planHash()} 是计划内容的稳定哈希（规范化 JSON 的 sha256），用于审计、
 * 去重与"同问题同计划"的回归比对。
 */
public final class ValidatedQueryPlan {

    private final Long datasetId;

    private final String datasetCode;

    private final String planDatasetId;

    private final Long datasetVersionId;

    private final Integer datasetVersionNo;

    private final String schemaHash;

    private final String sourceObject;

    private final List<Metric> metrics;

    private final List<Dimension> dimensions;

    private final List<Filter> filters;

    private final TimeWindow timeWindow;

    private final List<Order> orderBy;

    private final int limit;

    ValidatedQueryPlan(
            Long datasetId,
            String datasetCode,
            Long datasetVersionId,
            Integer datasetVersionNo,
            String schemaHash,
            String sourceObject,
            List<Metric> metrics,
            List<Dimension> dimensions,
            List<Filter> filters,
            TimeWindow timeWindow,
            List<Order> orderBy,
            int limit) {
        this.datasetId = datasetId;
        this.datasetCode = datasetCode;
        this.planDatasetId = "dset_" + datasetCode;
        this.datasetVersionId = datasetVersionId;
        this.datasetVersionNo = datasetVersionNo;
        this.schemaHash = schemaHash;
        this.sourceObject = sourceObject;
        this.metrics = List.copyOf(metrics);
        this.dimensions = List.copyOf(dimensions);
        this.filters = List.copyOf(filters);
        this.timeWindow = timeWindow;
        this.orderBy = List.copyOf(orderBy);
        this.limit = limit;
    }

    public Long datasetId() {
        return datasetId;
    }

    public String datasetCode() {
        return datasetCode;
    }

    public String planDatasetId() {
        return planDatasetId;
    }

    public Long datasetVersionId() {
        return datasetVersionId;
    }

    public Integer datasetVersionNo() {
        return datasetVersionNo;
    }

    public String schemaHash() {
        return schemaHash;
    }

    public String sourceObject() {
        return sourceObject;
    }

    public List<Metric> metrics() {
        return metrics;
    }

    public List<Dimension> dimensions() {
        return dimensions;
    }

    public List<Filter> filters() {
        return filters;
    }

    public TimeWindow timeWindow() {
        return timeWindow;
    }

    public List<Order> orderBy() {
        return orderBy;
    }

    public int limit() {
        return limit;
    }

    /** 计划内容哈希（规范化 JSON 的 sha256；同一份计划永远同一个哈希）。 */
    public String planHash() {
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("datasetId", planDatasetId);
        canonical.put("datasetVersion", datasetVersionNo);
        canonical.put("schemaHash", schemaHash);
        List<Object> metricList = new ArrayList<>();
        metrics.forEach(metric -> metricList.add(ordered(
                List.of("code", "sourceColumn", "aggregation", "unit"),
                List.of(metric.code(), metric.sourceColumn(), metric.aggregation(), metric.unit()))));
        canonical.put("metrics", metricList);
        List<Object> dimensionList = new ArrayList<>();
        dimensions.forEach(dimension -> dimensionList.add(
                ordered(List.of("code", "sourceColumn"), List.of(dimension.code(), dimension.sourceColumn()))));
        canonical.put("dimensions", dimensionList);
        List<Object> filterList = new ArrayList<>();
        filters.forEach(filter -> filterList.add(ordered(
                List.of("code", "sourceColumn", "type", "operator", "values"),
                java.util.Arrays.asList(
                        filter.code(), filter.sourceColumn(), filter.type(), filter.operator(), filter.values()))));
        canonical.put("filters", filterList);
        canonical.put(
                "timeRange",
                timeWindow == null
                        ? null
                        : ordered(
                                List.of(
                                        "code",
                                        "sourceColumn",
                                        "startInclusive",
                                        "endExclusive",
                                        "timezone",
                                        "granularity"),
                                List.of(
                                        timeWindow.code(),
                                        timeWindow.sourceColumn(),
                                        timeWindow.startInclusive(),
                                        timeWindow.endExclusive(),
                                        timeWindow.timezone(),
                                        timeWindow.granularity())));
        List<Object> orderList = new ArrayList<>();
        orderBy.forEach(order -> orderList.add(
                ordered(List.of("code", "kind", "direction"), List.of(order.code(), order.kind(), order.direction()))));
        canonical.put("orderBy", orderList);
        canonical.put("limit", limit);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of()
                    .formatHex(digest.digest(JsonUtils.toJsonString(canonical).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 不可用", impossible);
        }
    }

    private static Map<String, Object> ordered(List<String> keys, List<Object> values) {
        Map<String, Object> ordered = new LinkedHashMap<>();
        for (int index = 0; index < keys.size(); index++) {
            ordered.put(keys.get(index), values.get(index));
        }
        return ordered;
    }

    /** 指标选择：逻辑码 + 物理列 + 聚合方式 + 单位。 */
    public record Metric(String code, String sourceColumn, String aggregation, String unit) {}

    /** 维度选择：逻辑码 + 物理列。 */
    public record Dimension(String code, String sourceColumn) {}

    /** 过滤条件：逻辑码 + 物理列 + 语义类型 + 操作符 + 已绑定取值。 */
    public record Filter(String code, String sourceColumn, String type, String operator, List<Object> values) {

        public Filter {
            values = values == null ? List.of() : List.copyOf(values);
        }
    }

    /** 时间窗口：逻辑码 + 物理列 + 半开区间 [start, end) + 时区 + 粒度。 */
    public record TimeWindow(
            String code,
            String sourceColumn,
            String startInclusive,
            String endExclusive,
            String timezone,
            String granularity) {}

    /** 排序：逻辑码 + 类型（METRIC/DIMENSION）+ 方向。 */
    public record Order(String code, String kind, String direction) {}
}

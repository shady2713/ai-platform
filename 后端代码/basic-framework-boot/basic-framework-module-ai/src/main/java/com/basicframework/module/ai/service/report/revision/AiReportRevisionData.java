package com.basicframework.module.ai.service.report.revision;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_REPORT_REVISION_PLAN_INVALID;

import com.basicframework.framework.common.util.json.JsonUtils;
import com.basicframework.module.ai.domain.report.AiReportSpec;
import com.basicframework.module.ai.domain.result.AiReportResultBlock;
import com.basicframework.module.ai.service.report.validation.AiReportDataBinder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 报表版本数据（R05）：版本里 {@code data_json} 的读写口径。
 *
 * <p>形状与 R03 的结果块一致（{@code kind/title/specJson/data/sources/notes}），并额外带一份
 * {@code datasets}：每个数据集引用的**结果行**（列 + 行 + 完整性）。为什么要多存这一份：
 * 展示类修订（换图类型、换绑定字段、改表格列）必须在**不查库**的前提下重新绑定出块数据，
 * 没有原始结果行就只能"猜"或被迫查库——两份都不是可接受的选项。
 *
 * <p>R04 把 {@code data_json} 定义为不透明字符串，本类是它**唯一**的读写口径；
 * 解析失败一律拒绝（不做"尽力而为"的兼容），因为读错形状会把数据展示错。
 */
public final class AiReportRevisionData {

    /** 结果块类型（与 R03 一致）。 */
    private static final String KIND_REPORT = AiReportResultBlock.KIND_REPORT;

    private AiReportRevisionData() {}

    /** 从已绑定结果与结果行组装版本数据。 */
    public static String build(
            AiReportSpec spec,
            AiReportDataBinder.BoundReport bound,
            Map<String, AiReportDataBinder.ExecutionResult> datasets) {
        List<Map<String, Object>> data = new ArrayList<>();
        for (AiReportDataBinder.BoundBlock block : bound.blocks()) {
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("blockId", block.blockId());
            node.put("type", block.type());
            node.put("verified", block.verified());
            node.put("value", block.value());
            node.put("rows", block.rows());
            node.put("points", block.points());
            data.add(node);
        }
        List<Map<String, Object>> datasetNodes = new ArrayList<>();
        for (AiReportSpec.DatasetRef ref : spec.datasetRefs()) {
            AiReportDataBinder.ExecutionResult result = datasets.get(ref.id());
            if (result == null) {
                continue;
            }
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("datasetRef", ref.id());
            node.put(
                    "columns",
                    ref.columns().stream().map(AiReportRevisionData::columnJson).toList());
            node.put("rows", result.rows());
            node.put("completeness", result.completeness());
            datasetNodes.add(node);
        }
        List<Map<String, Object>> sources = new ArrayList<>();
        for (AiReportSpec.DatasetRef ref : spec.datasetRefs()) {
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("datasetRef", ref.id());
            node.put("queryRef", ref.queryRef());
            node.put("resultRef", ref.resultRef());
            node.put("rowCount", ref.rowCount());
            node.put("completeness", ref.completeness());
            sources.add(node);
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("kind", KIND_REPORT);
        root.put("title", spec.title());
        root.put("specJson", JsonUtils.toJsonString(specJsonTree(spec)));
        root.put("datasets", datasetNodes);
        root.put("data", data);
        root.put("sources", sources);
        root.put("notes", List.of());
        return JsonUtils.toJsonString(root);
    }

    /**
     * 从基础版本数据里取出结果行（用于展示类修订重新绑定，**不查库**）。
     *
     * <p>缺 {@code datasets} 时返回空：调用方据此拒绝"需要重新投影的展示类操作"，
     * 而不是拿旧块数据凑一份看起来对的界面。
     */
    public static Map<String, AiReportDataBinder.ExecutionResult> executionResults(String dataJson) {
        Map<String, Object> root = parse(dataJson).orElse(null);
        if (root == null) {
            return Map.of();
        }
        Map<String, AiReportDataBinder.ExecutionResult> results = new LinkedHashMap<>();
        for (Map<String, Object> node : nodes(root, "datasets")) {
            String datasetRef = String.valueOf(node.get("datasetRef"));
            results.put(
                    datasetRef,
                    new AiReportDataBinder.ExecutionResult(
                            null,
                            strings(node.get("columns")),
                            rows(node.get("rows")),
                            String.valueOf(node.get("completeness"))));
        }
        return results;
    }

    /** 展示类修订：沿用基础版本数据，只保留候选规格里仍存在的块（被删块的数据一并移除）。 */
    public static String reuse(String baseDataJson, AiReportSpec candidate) {
        Map<String, Object> root = parse(baseDataJson).orElse(null);
        if (root == null) {
            // 基础版本没有可复用数据（如可刷新模式）：候选版本同样不带数据
            return null;
        }
        Set<String> blockIds = candidate.blocksById().keySet();
        List<Map<String, Object>> kept = new ArrayList<>();
        for (Map<String, Object> node : nodes(root, "data")) {
            if (blockIds.contains(String.valueOf(node.get("blockId")))) {
                kept.add(node);
            }
        }
        Map<String, Object> reused = new LinkedHashMap<>(root);
        reused.put("title", candidate.title());
        reused.put("specJson", JsonUtils.toJsonString(specJsonTree(candidate)));
        reused.put("data", kept);
        reused.put("notes", root.getOrDefault("notes", List.of()));
        return JsonUtils.toJsonString(reused);
    }

    /**
     * 展示类操作是否改动了块的**数据投影**（图表字段、表格列、指标字段/行号）。
     *
     * <p>改动投影就必须重新绑定，而重新绑定需要原始结果行：基础版本没存结果行时明确拒绝。
     */
    public static boolean projectionChanged(AiReportSpec base, AiReportSpec candidate) {
        for (AiReportSpec.Block block : candidate.blocks()) {
            AiReportSpec.Block previous = base.blocksById().get(block.id());
            if (previous == null) {
                continue;
            }
            if (!projection(block).equals(projection(previous))) {
                return true;
            }
        }
        return false;
    }

    /** 规格的规范化 JSON 树（与 R03 产物同形，渲染方不需要再解析模型输出）。 */
    private static Map<String, Object> specJsonTree(AiReportSpec spec) {
        Map<String, Object> tree = new LinkedHashMap<>();
        tree.put("schemaVersion", AiReportSpec.SCHEMA_VERSION);
        tree.put("title", spec.title());
        tree.put("themeRef", Map.of("themeId", spec.themeId(), "revision", spec.themeRevision()));
        tree.put("layout", Map.of("columns", AiReportSpec.LAYOUT_COLUMNS, "gap", spec.gap(), "items", spec.layout()));
        tree.put("blocks", JsonUtils.parseObject(JsonUtils.toJsonString(spec.blocks()), List.class));
        tree.put("datasetRefs", JsonUtils.parseObject(JsonUtils.toJsonString(spec.datasetRefs()), List.class));
        tree.put(
                "queryRefs",
                spec.queryRefs().stream()
                        .map(ref -> Map.of("id", ref.id(), "plan", JsonUtils.parseObject(ref.planJson(), Map.class)))
                        .toList());
        tree.put("sources", JsonUtils.parseObject(JsonUtils.toJsonString(spec.sources()), List.class));
        return tree;
    }

    private static Map<String, Object> columnJson(AiReportSpec.ResultColumn column) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("field", column.field());
        node.put("label", column.label());
        node.put("dataType", column.dataType());
        if (column.unit() != null) {
            node.put("unit", column.unit());
        }
        return node;
    }

    /** 块的投影描述（决定这份块需要哪些结果列）。 */
    private static String projection(AiReportSpec.Block block) {
        // 图表类型不在投影里：换图类型不需要新数据（"换图不重复查库"）
        String chartFields = block.chart() == null
                ? ""
                : block.chart().categoryField() + "," + block.chart().valueField() + ","
                        + block.chart().seriesField();
        return block.type()
                + "|"
                + block.datasetRef()
                + "|"
                + block.metricField()
                + "|"
                + block.rowIndex()
                + "|"
                + block.columns()
                + "|"
                + chartFields;
    }

    private static Optional<Map<String, Object>> parse(String dataJson) {
        if (dataJson == null || dataJson.isBlank()) {
            return Optional.empty();
        }
        Map<?, ?> parsed;
        try {
            parsed = JsonUtils.parseObject(dataJson, Map.class);
        } catch (IllegalArgumentException notAnObject) {
            throw exception(AI_REPORT_REVISION_PLAN_INVALID);
        }
        if (parsed == null) {
            return Optional.empty();
        }
        Map<String, Object> root = new LinkedHashMap<>();
        parsed.forEach((key, value) -> root.put(String.valueOf(key), value));
        return Optional.of(root);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> nodes(Map<String, Object> root, String key) {
        if (!(root.get(key) instanceof List<?> items)) {
            return List.of();
        }
        List<Map<String, Object>> nodes = new ArrayList<>();
        for (Object item : items) {
            if (item instanceof Map<?, ?> map) {
                nodes.add((Map<String, Object>) map);
            }
        }
        return nodes;
    }

    private static List<String> strings(Object value) {
        if (!(value instanceof List<?> items)) {
            return List.of();
        }
        Set<String> values = new LinkedHashSet<>();
        for (Object item : items) {
            if (item instanceof Map<?, ?> map) {
                Object field = map.get("field");
                if (field != null) {
                    values.add(String.valueOf(field));
                }
            } else if (item != null) {
                values.add(String.valueOf(item));
            }
        }
        return List.copyOf(values);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(Object value) {
        if (!(value instanceof List<?> items)) {
            return List.of();
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Object item : items) {
            if (item instanceof Map<?, ?> map) {
                rows.add((Map<String, Object>) map);
            }
        }
        return rows;
    }
}

package com.basicframework.module.ai.domain.report;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_REPORT_SCRIPT_REJECTED;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_REPORT_SPEC_INVALID;

import com.basicframework.framework.common.util.json.JsonUtils;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 报表结构（R01）：ReportSpec v1 的**服务端模型**。
 *
 * <p>为什么先"结构"后"语义"：结构校验回答"这份 JSON 是不是 ReportSpec"（键白名单、取值域、上限、
 * 禁止脚本片段），语义校验（{@link com.basicframework.module.ai.service.report.validation.AiReportSpecValidator}）
 * 回答"它引用的东西是否存在、类型是否对得上、布局是否合法"。两类错误必须分开报，
 * 否则"模型写错字段名"和"引用不存在的数据集"会混成同一个错误，界面无法给出可修复的提示。
 *
 * <p>结构性拒绝（与 AT-043 一致）：
 * <ul>
 *   <li><b>键白名单</b>：任何未登记的键（{@code script}/{@code style}/{@code html}/{@code onClick}/{@code href}…）一律拒绝，
 *       不做"忽略未知键"的宽容处理；</li>
 *   <li><b>脚本片段标记</b>：文本字段里出现 {@code <script}/{@code </script}/{@code javascript:}/{@code <style}/
 *       {@code <iframe}/{@code on*=} 一律拒绝（HTML/JS/CSS 不进报表）；</li>
 *   <li><b>上限</b>：块 ≤ 40、布局项 ≤ 40、数据集引用 ≤ 20、查询引用 ≤ 20、来源 ≤ 40、文本 ≤ 5000 字符。</li>
 * </ul>
 */
public final class AiReportSpec {

    /** 契约版本。 */
    public static final String SCHEMA_VERSION = "1.0";

    /** 布局栅格列数（固定 12 列）。 */
    public static final int LAYOUT_COLUMNS = 12;

    /** 布局间距白名单。 */
    public static final Set<Integer> GAP_VALUES = Set.of(8, 12, 16, 24);

    /** 块数上限。 */
    public static final int MAX_BLOCKS = 40;

    /** 布局项上限。 */
    public static final int MAX_LAYOUT_ITEMS = 40;

    /** 数据集引用上限。 */
    public static final int MAX_DATASET_REFS = 20;

    /** 查询引用上限。 */
    public static final int MAX_QUERY_REFS = 20;

    /** 来源上限。 */
    public static final int MAX_SOURCES = 40;

    /** 文本块长度上限。 */
    public static final int MAX_TEXT_LENGTH = 5_000;

    /** 逻辑标识（块/数据集/查询/来源）。 */
    public static final Pattern ID_PATTERN = Pattern.compile("^[a-z][a-z0-9_]{0,63}$");

    /** 脚本/样式/框架标记：出现即拒绝。 */
    private static final Pattern SCRIPT_MARKER =
            Pattern.compile("(?i)(<\\s*script|</\\s*script|javascript:|<\\s*style|<\\s*iframe|\\bon[a-z]+\\s*=)");

    private static final Set<String> TOP_LEVEL_KEYS =
            Set.of("schemaVersion", "title", "themeRef", "layout", "blocks", "datasetRefs", "queryRefs", "sources");

    private final String title;

    private final String themeId;

    private final int themeRevision;

    private final List<LayoutItem> layout;

    private final int gap;

    private final List<Block> blocks;

    private final List<DatasetRef> datasetRefs;

    private final List<QueryRef> queryRefs;

    private final List<Source> sources;

    private AiReportSpec(
            String title,
            String themeId,
            int themeRevision,
            int gap,
            List<LayoutItem> layout,
            List<Block> blocks,
            List<DatasetRef> datasetRefs,
            List<QueryRef> queryRefs,
            List<Source> sources) {
        this.title = title;
        this.themeId = themeId;
        this.themeRevision = themeRevision;
        this.gap = gap;
        this.layout = List.copyOf(layout);
        this.blocks = List.copyOf(blocks);
        this.datasetRefs = List.copyOf(datasetRefs);
        this.queryRefs = List.copyOf(queryRefs);
        this.sources = List.copyOf(sources);
    }

    /** 布局项：块在 12 列栅格中的位置（行/列/跨度）。 */
    public record LayoutItem(String blockId, int row, int column, int span) {}

    /** 块：metric（绑定结果列）/ text（说明，非确定性核验）/ table（结果表）/ chart（结果图表）。 */
    public record Block(
            String id,
            String title,
            String type,
            String text,
            String datasetRef,
            String metricField,
            Integer rowIndex,
            String format,
            String unit,
            List<TableColumn> columns,
            Integer pageSize,
            Chart chart) {}

    /** 表格列。 */
    public record TableColumn(String field, String label, String format) {}

    /** 图表声明：类型 + 字段 + 图例。 */
    public record Chart(
            String chartType, String categoryField, String valueField, String seriesField, boolean legend) {}

    /** 数据集引用：声明它来自哪次运行结果与哪条查询（模型不能自造来源）。 */
    public record DatasetRef(
            String id,
            String resultRef,
            String queryRef,
            List<ResultColumn> columns,
            int rowCount,
            String completeness) {}

    /** 结果列（来自执行结果）。 */
    public record ResultColumn(String field, String label, String dataType, String unit) {}

    /** 查询引用：逻辑标识 + 查询计划（计划本身由 D05 校验）。 */
    public record QueryRef(String id, String planJson) {}

    /** 来源：数据集/文档/接口 + 资源标识与版本。 */
    public record Source(
            String id, String kind, String resourceId, int resourceVersion, String queryRef, String description) {}

    /** 解析并做**结构**校验（语义校验在服务层）。 */
    public static AiReportSpec parse(String specJson) {
        if (specJson == null || specJson.isBlank()) {
            throw exception(AI_REPORT_SPEC_INVALID);
        }
        if (SCRIPT_MARKER.matcher(specJson).find()) {
            // HTML/JS/CSS 片段：直接拒绝，不做"清理后使用"
            throw exception(AI_REPORT_SCRIPT_REJECTED);
        }
        Map<String, Object> raw = object(specJson);
        requireKeys(raw, TOP_LEVEL_KEYS);
        if (!SCHEMA_VERSION.equals(String.valueOf(raw.get("schemaVersion")))) {
            throw exception(AI_REPORT_SPEC_INVALID);
        }
        String title = text(raw.get("title"), 200, true);
        Map<String, Object> theme = raw.get("themeRef") instanceof Map<?, ?> map ? stringMap(map) : null;
        if (theme == null) {
            throw exception(AI_REPORT_SPEC_INVALID);
        }
        requireKeys(theme, Set.of("themeId", "revision"));
        String themeId = text(theme.get("themeId"), 40, true);
        if (!themeId.matches("^thm_[A-Za-z0-9_-]{3,35}$")) {
            throw exception(AI_REPORT_SPEC_INVALID);
        }
        int themeRevision = positiveInt(theme.get("revision"));
        Map<String, Object> layoutNode = raw.get("layout") instanceof Map<?, ?> map ? stringMap(map) : null;
        if (layoutNode == null) {
            throw exception(AI_REPORT_SPEC_INVALID);
        }
        requireKeys(layoutNode, Set.of("columns", "gap", "items"));
        if (!(layoutNode.get("columns") instanceof Number columns) || columns.intValue() != LAYOUT_COLUMNS) {
            throw exception(AI_REPORT_SPEC_INVALID);
        }
        int gap = intValue(layoutNode.get("gap"));
        if (!GAP_VALUES.contains(gap)) {
            throw exception(AI_REPORT_SPEC_INVALID);
        }
        List<LayoutItem> layout = parseLayout(layoutNode.get("items"));
        List<Block> blocks = parseBlocks(raw.get("blocks"));
        List<DatasetRef> datasetRefs = parseDatasetRefs(raw.get("datasetRefs"));
        List<QueryRef> queryRefs = parseQueryRefs(raw.get("queryRefs"));
        List<Source> sources = parseSources(raw.get("sources"));
        return new AiReportSpec(title, themeId, themeRevision, gap, layout, blocks, datasetRefs, queryRefs, sources);
    }

    private static List<LayoutItem> parseLayout(Object value) {
        List<Map<String, Object>> items = objectList(value, MAX_LAYOUT_ITEMS);
        List<LayoutItem> layout = new java.util.ArrayList<>();
        for (Map<String, Object> item : items) {
            requireKeys(item, Set.of("blockId", "row", "column", "span"));
            String blockId = identifier(item.get("blockId"));
            int row = intValue(item.get("row"));
            int column = intValue(item.get("column"));
            int span = intValue(item.get("span"));
            if (row < 0
                    || row > 100
                    || column < 0
                    || column > LAYOUT_COLUMNS - 1
                    || span < 1
                    || span > LAYOUT_COLUMNS
                    || column + span > LAYOUT_COLUMNS) {
                // 越界由语义校验统一报 AI_REPORT_LAYOUT_INVALID；这里只做形状校验
                throw exception(AI_REPORT_SPEC_INVALID);
            }
            layout.add(new LayoutItem(blockId, row, column, span));
        }
        if (layout.isEmpty()) {
            throw exception(AI_REPORT_SPEC_INVALID);
        }
        return layout;
    }

    private static List<Block> parseBlocks(Object value) {
        List<Map<String, Object>> items = objectList(value, MAX_BLOCKS);
        List<Block> blocks = new java.util.ArrayList<>();
        for (Map<String, Object> item : items) {
            String type = String.valueOf(item.get("type"));
            switch (type) {
                case "metric" -> {
                    requireKeys(item, Set.of("id", "title", "type", "binding", "rowIndex", "format", "unit"));
                    Map<String, Object> binding = item.get("binding") instanceof Map<?, ?> map ? stringMap(map) : null;
                    if (binding == null) {
                        throw exception(AI_REPORT_SPEC_INVALID);
                    }
                    requireKeys(binding, Set.of("datasetRef", "field"));
                    blocks.add(new Block(
                            identifier(item.get("id")),
                            text(item.get("title"), 100, true),
                            type,
                            null,
                            identifier(binding.get("datasetRef")),
                            identifier(binding.get("field")),
                            intValue(item.get("rowIndex")),
                            format(item.get("format")),
                            item.get("unit") == null ? null : text(item.get("unit"), 32, true),
                            null,
                            null,
                            null));
                }
                case "text" -> {
                    requireKeys(item, Set.of("id", "title", "type", "text"));
                    blocks.add(new Block(
                            identifier(item.get("id")),
                            text(item.get("title"), 100, true),
                            type,
                            text(item.get("text"), MAX_TEXT_LENGTH, false),
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null));
                }
                case "table" -> {
                    requireKeys(item, Set.of("id", "title", "type", "datasetRef", "columns", "pageSize"));
                    blocks.add(new Block(
                            identifier(item.get("id")),
                            text(item.get("title"), 100, true),
                            type,
                            null,
                            identifier(item.get("datasetRef")),
                            null,
                            null,
                            null,
                            null,
                            parseTableColumns(item.get("columns")),
                            intValue(item.get("pageSize")),
                            null));
                }
                case "chart" -> {
                    requireKeys(item, Set.of("id", "title", "type", "datasetRef", "chart"));
                    Map<String, Object> chart = item.get("chart") instanceof Map<?, ?> map ? stringMap(map) : null;
                    if (chart == null) {
                        throw exception(AI_REPORT_SPEC_INVALID);
                    }
                    requireKeys(chart, Set.of("chartType", "categoryField", "valueField", "seriesField", "legend"));
                    String chartType = String.valueOf(chart.get("chartType"));
                    if (!Set.of("column", "line", "pie").contains(chartType)) {
                        throw exception(AI_REPORT_SPEC_INVALID);
                    }
                    blocks.add(new Block(
                            identifier(item.get("id")),
                            text(item.get("title"), 100, true),
                            type,
                            null,
                            identifier(item.get("datasetRef")),
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            new Chart(
                                    chartType,
                                    identifier(chart.get("categoryField")),
                                    identifier(chart.get("valueField")),
                                    chart.get("seriesField") == null ? null : identifier(chart.get("seriesField")),
                                    Boolean.TRUE.equals(chart.get("legend")))));
                }
                default -> throw exception(AI_REPORT_SPEC_INVALID);
            }
        }
        if (blocks.isEmpty()) {
            throw exception(AI_REPORT_SPEC_INVALID);
        }
        return blocks;
    }

    private static List<TableColumn> parseTableColumns(Object value) {
        List<Map<String, Object>> items = objectList(value, 50);
        List<TableColumn> columns = new java.util.ArrayList<>();
        for (Map<String, Object> item : items) {
            requireKeys(item, Set.of("field", "label", "format"));
            columns.add(new TableColumn(
                    identifier(item.get("field")), text(item.get("label"), 100, true), format(item.get("format"))));
        }
        if (columns.isEmpty()) {
            throw exception(AI_REPORT_SPEC_INVALID);
        }
        return columns;
    }

    private static List<DatasetRef> parseDatasetRefs(Object value) {
        List<Map<String, Object>> items = objectList(value, MAX_DATASET_REFS);
        List<DatasetRef> refs = new java.util.ArrayList<>();
        for (Map<String, Object> item : items) {
            requireKeys(item, Set.of("id", "resultRef", "queryRef", "columns", "rowCount", "asOf", "completeness"));
            String completeness = String.valueOf(item.get("completeness"));
            if (!Set.of("COMPLETE", "PARTIAL", "FAILED").contains(completeness)) {
                throw exception(AI_REPORT_SPEC_INVALID);
            }
            refs.add(new DatasetRef(
                    identifier(item.get("id")),
                    text(item.get("resultRef"), 120, true),
                    identifier(item.get("queryRef")),
                    parseResultColumns(item.get("columns")),
                    intValue(item.get("rowCount")),
                    completeness));
        }
        if (refs.isEmpty()) {
            throw exception(AI_REPORT_SPEC_INVALID);
        }
        return refs;
    }

    private static List<ResultColumn> parseResultColumns(Object value) {
        List<Map<String, Object>> items = objectList(value, 50);
        List<ResultColumn> columns = new java.util.ArrayList<>();
        for (Map<String, Object> item : items) {
            requireKeys(item, Set.of("field", "label", "dataType", "unit"));
            String dataType = String.valueOf(item.get("dataType"));
            if (!Set.of("STRING", "INTEGER", "DECIMAL", "BOOLEAN", "DATE", "DATETIME")
                    .contains(dataType)) {
                throw exception(AI_REPORT_SPEC_INVALID);
            }
            columns.add(new ResultColumn(
                    identifier(item.get("field")),
                    text(item.get("label"), 100, true),
                    dataType,
                    item.get("unit") == null ? null : text(item.get("unit"), 32, true)));
        }
        if (columns.isEmpty()) {
            throw exception(AI_REPORT_SPEC_INVALID);
        }
        return columns;
    }

    private static List<QueryRef> parseQueryRefs(Object value) {
        List<Map<String, Object>> items = objectList(value, MAX_QUERY_REFS);
        List<QueryRef> refs = new java.util.ArrayList<>();
        for (Map<String, Object> item : items) {
            requireKeys(item, Set.of("id", "plan"));
            Object plan = item.get("plan");
            if (!(plan instanceof Map<?, ?>)) {
                throw exception(AI_REPORT_SPEC_INVALID);
            }
            refs.add(new QueryRef(identifier(item.get("id")), JsonUtils.toJsonString(plan)));
        }
        if (refs.isEmpty()) {
            throw exception(AI_REPORT_SPEC_INVALID);
        }
        return refs;
    }

    private static List<Source> parseSources(Object value) {
        List<Map<String, Object>> items = objectList(value, MAX_SOURCES);
        List<Source> sources = new java.util.ArrayList<>();
        for (Map<String, Object> item : items) {
            requireKeys(item, Set.of("id", "kind", "resourceId", "resourceVersion", "queryRef", "description"));
            String kind = String.valueOf(item.get("kind"));
            if (!Set.of("DATASET", "DOCUMENT", "API").contains(kind)) {
                throw exception(AI_REPORT_SPEC_INVALID);
            }
            sources.add(new Source(
                    identifier(item.get("id")),
                    kind,
                    text(item.get("resourceId"), 40, true),
                    positiveInt(item.get("resourceVersion")),
                    item.get("queryRef") == null ? null : identifier(item.get("queryRef")),
                    text(item.get("description"), 500, true)));
        }
        if (sources.isEmpty()) {
            throw exception(AI_REPORT_SPEC_INVALID);
        }
        return sources;
    }

    /** 块按逻辑标识索引。 */
    public Map<String, Block> blocksById() {
        Map<String, Block> byId = new LinkedHashMap<>();
        blocks.forEach(block -> byId.put(block.id(), block));
        return byId;
    }

    /** 数据集引用按逻辑标识索引。 */
    public Map<String, DatasetRef> datasetRefsById() {
        Map<String, DatasetRef> byId = new LinkedHashMap<>();
        datasetRefs.forEach(ref -> byId.put(ref.id(), ref));
        return byId;
    }

    /** 查询引用按逻辑标识索引。 */
    public Map<String, QueryRef> queryRefsById() {
        Map<String, QueryRef> byId = new LinkedHashMap<>();
        queryRefs.forEach(ref -> byId.put(ref.id(), ref));
        return byId;
    }

    public String title() {
        return title;
    }

    public String themeId() {
        return themeId;
    }

    public int themeRevision() {
        return themeRevision;
    }

    public int gap() {
        return gap;
    }

    public List<LayoutItem> layout() {
        return layout;
    }

    public List<Block> blocks() {
        return blocks;
    }

    public List<DatasetRef> datasetRefs() {
        return datasetRefs;
    }

    public List<QueryRef> queryRefs() {
        return queryRefs;
    }

    public List<Source> sources() {
        return sources;
    }

    private static Map<String, Object> object(String json) {
        try {
            Map<?, ?> parsed = JsonUtils.parseObject(json, Map.class);
            if (parsed == null) {
                throw exception(AI_REPORT_SPEC_INVALID);
            }
            return stringMap(parsed);
        } catch (IllegalArgumentException notAnObject) {
            throw exception(AI_REPORT_SPEC_INVALID);
        }
    }

    private static Map<String, Object> stringMap(Map<?, ?> raw) {
        Map<String, Object> mapped = new LinkedHashMap<>();
        raw.forEach((key, value) -> mapped.put(String.valueOf(key), value));
        return mapped;
    }

    private static List<Map<String, Object>> objectList(Object value, int maxItems) {
        if (!(value instanceof List<?> list) || list.isEmpty() || list.size() > maxItems) {
            throw exception(AI_REPORT_SPEC_INVALID);
        }
        List<Map<String, Object>> items = new java.util.ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) {
                throw exception(AI_REPORT_SPEC_INVALID);
            }
            items.add(stringMap(map));
        }
        return items;
    }

    private static void requireKeys(Map<String, Object> values, Set<String> allowed) {
        for (String key : values.keySet()) {
            if (!allowed.contains(key)) {
                // 未知键一律拒绝（不"忽略"）：script/style/html/onClick 这类键在这里被挡掉
                throw exception(AI_REPORT_SPEC_INVALID);
            }
        }
        for (String required : allowed) {
            if (required.equals("unit")
                    || required.equals("asOf")
                    || required.equals("seriesField")
                    || required.equals("queryRef")) {
                continue;
            }
            if (!values.containsKey(required)) {
                throw exception(AI_REPORT_SPEC_INVALID);
            }
        }
    }

    private static String identifier(Object value) {
        String text = String.valueOf(value);
        if (!ID_PATTERN.matcher(text).matches()) {
            throw exception(AI_REPORT_SPEC_INVALID);
        }
        return text;
    }

    private static String text(Object value, int maxLength, boolean required) {
        if (value == null) {
            if (required) {
                throw exception(AI_REPORT_SPEC_INVALID);
            }
            return "";
        }
        String text = String.valueOf(value);
        if (required && text.isEmpty()) {
            throw exception(AI_REPORT_SPEC_INVALID);
        }
        if (text.length() > maxLength) {
            throw exception(AI_REPORT_SPEC_INVALID);
        }
        return text;
    }

    private static String format(Object value) {
        String text = String.valueOf(value);
        if (!Set.of("NUMBER", "DECIMAL", "PERCENT", "CURRENCY", "TEXT").contains(text)) {
            throw exception(AI_REPORT_SPEC_INVALID);
        }
        return text;
    }

    private static int intValue(Object value) {
        if (!(value instanceof Number number) || number.intValue() < 0) {
            throw exception(AI_REPORT_SPEC_INVALID);
        }
        return number.intValue();
    }

    private static int positiveInt(Object value) {
        int parsed = intValue(value);
        if (parsed < 1) {
            throw exception(AI_REPORT_SPEC_INVALID);
        }
        return parsed;
    }
}

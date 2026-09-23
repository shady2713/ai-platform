package com.basicframework.module.ai.service.report.revision;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_REPORT_REVISION_PLAN_INVALID;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 修订操作（R05 第 1 步）：对话修改被规范化成**操作白名单**里的一条操作。
 *
 * <p>为什么不让模型直接回一份新的 ReportSpec：那样"改了哪些东西"只能靠比对两份 JSON 猜，
 * 而且模型可以在用户没要求的地方顺手换掉数据来源。操作清单把"改什么"与"能怎么改"分开——
 * 模型只说改什么，操作码是否可用、要不要重新查数据由服务端判定。
 *
 * <p>两类操作的边界（分类由服务端按操作码判定，模型**不能**自己声明"这次不用查库"）：
 * <ul>
 *   <li><b>展示类</b>：只引用已存在的块与已存在的结果列（换图类型、换绑定字段、改标题、改布局、
 *       改格式、改分页…），数据本身不变 → <b>不查库</b>（AT-045 的"换图不重复查库"）；</li>
 *   <li><b>数据类</b>：新增聚合指标、改时间粒度、加过滤条件、换数据集 → 必须走受控查询
 *       （D05 计划 → D06 编译 → D03 只读执行），模型给不出数字。</li>
 * </ul>
 */
public record AiReportRevisionOp(
        String op, String blockId, String field, String value, String datasetId, String metric, String grain) {

    /** 改报表标题（展示类）。 */
    public static final String SET_TITLE = "SET_TITLE";

    /** 改主题（展示类）。 */
    public static final String SET_THEME = "SET_THEME";

    /** 改块标题（展示类）。 */
    public static final String SET_BLOCK_TITLE = "SET_BLOCK_TITLE";

    /** 改文本块正文（展示类）。 */
    public static final String SET_TEXT = "SET_TEXT";

    /** 改图表类型（展示类；类型域由 R01 结构校验限定）。 */
    public static final String SET_CHART_TYPE = "SET_CHART_TYPE";

    /** 改图表绑定字段（展示类；字段必须已在结果列里，否则校验器拒绝）。 */
    public static final String SET_CHART_FIELD = "SET_CHART_FIELD";

    /** 改表格列（展示类；字段必须已在结果列里）。 */
    public static final String SET_TABLE_COLUMNS = "SET_TABLE_COLUMNS";

    /** 改表格分页大小（展示类）。 */
    public static final String SET_PAGE_SIZE = "SET_PAGE_SIZE";

    /** 改指标块绑定字段（展示类；字段必须是已存在的数字列）。 */
    public static final String SET_METRIC_FIELD = "SET_METRIC_FIELD";

    /** 改布局（展示类）。 */
    public static final String SET_LAYOUT = "SET_LAYOUT";

    /** 删除块（展示类；数据里对应的块数据一并移除，不保留孤儿数据）。 */
    public static final String REMOVE_BLOCK = "REMOVE_BLOCK";

    /** 新增文本说明块（展示类；说明性文字不参与数字计算）。 */
    public static final String ADD_TEXT = "ADD_TEXT";

    /** 新增聚合指标（数据类）：新口径必须重新查询，不能拿旧结果凑。 */
    public static final String ADD_METRIC = "ADD_METRIC";

    /** 改时间粒度（数据类）。 */
    public static final String SET_GRAIN = "SET_GRAIN";

    /** 加过滤条件（数据类）。 */
    public static final String ADD_FILTER = "ADD_FILTER";

    /** 换/加数据集来源（数据类）。 */
    public static final String ADD_DATASET = "ADD_DATASET";

    /** 展示类操作（不查库）。 */
    public static final Set<String> PRESENTATION_OPS = Set.of(
            SET_TITLE,
            SET_THEME,
            SET_BLOCK_TITLE,
            SET_TEXT,
            SET_CHART_TYPE,
            SET_CHART_FIELD,
            SET_TABLE_COLUMNS,
            SET_PAGE_SIZE,
            SET_METRIC_FIELD,
            SET_LAYOUT,
            REMOVE_BLOCK,
            ADD_TEXT);

    /** 数据类操作（必须受控查询）。 */
    public static final Set<String> DATA_OPS = Set.of(ADD_METRIC, SET_GRAIN, ADD_FILTER, ADD_DATASET);

    /** 操作里允许出现的键（未登记键一律拒绝，不做"忽略未知键"的宽容处理）。 */
    private static final Set<String> OP_KEYS =
            Set.of("op", "blockId", "field", "value", "datasetId", "metric", "grain");

    /** 从模型输出里的一条操作解析（结构不合法即拒绝）。 */
    public static AiReportRevisionOp parse(Object raw) {
        if (!(raw instanceof Map<?, ?> node)) {
            throw exception(AI_REPORT_REVISION_PLAN_INVALID);
        }
        Map<String, Object> values = new LinkedHashMap<>();
        node.forEach((key, value) -> values.put(String.valueOf(key), value));
        for (String key : values.keySet()) {
            if (!OP_KEYS.contains(key)) {
                throw exception(AI_REPORT_REVISION_PLAN_INVALID);
            }
        }
        String op = text(values.get("op"));
        if (op == null || (!PRESENTATION_OPS.contains(op) && !DATA_OPS.contains(op))) {
            // 未知操作码：拒绝而不是"跳过它继续改别的"（跳过会让用户以为改成了）
            throw exception(AI_REPORT_REVISION_PLAN_INVALID);
        }
        AiReportRevisionOp parsed = new AiReportRevisionOp(
                op,
                text(values.get("blockId")),
                text(values.get("field")),
                text(values.get("value")),
                text(values.get("datasetId")),
                text(values.get("metric")),
                text(values.get("grain")));
        parsed.requireParameters();
        return parsed;
    }

    /** 该操作是否要求重新查询数据（服务端判定，与模型声明无关）。 */
    public boolean requiresQuery() {
        return DATA_OPS.contains(op);
    }

    /** 该操作是否只改展示。 */
    public boolean presentationOnly() {
        return PRESENTATION_OPS.contains(op);
    }

    /** 操作涉及的块（无块操作返回 null）。 */
    public boolean hasBlock() {
        return blockId != null && !blockId.isBlank();
    }

    /** 每个操作的必填参数（缺一不可；多给的参数由 {@link #OP_KEYS} 兜底）。 */
    private void requireParameters() {
        boolean valid =
                switch (op) {
                    case SET_TITLE, SET_LAYOUT, SET_THEME -> has(value);
                    case SET_BLOCK_TITLE, SET_TEXT, SET_CHART_TYPE, SET_METRIC_FIELD, REMOVE_BLOCK ->
                        has(blockId) && (REMOVE_BLOCK.equals(op) || has(value));
                    case SET_CHART_FIELD, ADD_FILTER -> has(blockId) && has(field) && has(value);
                    case SET_TABLE_COLUMNS, SET_PAGE_SIZE -> has(blockId) && has(value);
                    case ADD_TEXT -> has(blockId) && has(value);
                    case ADD_METRIC -> has(datasetId) && has(metric);
                    case SET_GRAIN -> has(datasetId) && has(grain);
                    case ADD_DATASET -> has(datasetId);
                    default -> false;
                };
        if (!valid) {
            throw exception(AI_REPORT_REVISION_PLAN_INVALID);
        }
    }

    /** 表格列操作的值是逗号分隔的逻辑字段名。 */
    public List<String> tableFields() {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(field -> !field.isEmpty())
                .toList();
    }

    /** 分页大小（非数字或越界由 R01 结构校验拒绝）。 */
    public int pageSize() {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException notANumber) {
            throw exception(AI_REPORT_REVISION_PLAN_INVALID);
        }
    }

    /** 主题修订号（缺省 1，非数字即拒绝）。 */
    public int themeRevision() {
        if (field == null || field.isBlank()) {
            return 1;
        }
        try {
            return Integer.parseInt(field.trim());
        } catch (NumberFormatException notANumber) {
            throw exception(AI_REPORT_REVISION_PLAN_INVALID);
        }
    }

    private static boolean has(String value) {
        return value != null && !value.isBlank();
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}

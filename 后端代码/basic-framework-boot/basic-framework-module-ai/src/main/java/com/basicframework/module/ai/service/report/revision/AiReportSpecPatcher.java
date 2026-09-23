package com.basicframework.module.ai.service.report.revision;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_REPORT_REVISION_PLAN_INVALID;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_REPORT_REVISION_UNSUPPORTED;

import com.basicframework.framework.common.util.json.JsonUtils;
import com.basicframework.module.ai.domain.report.AiReportSpec;
import com.basicframework.module.ai.service.run.dto.AiRunQueryExecutionResultDTO;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 规格补丁器（R05 第 2 步）：把操作清单**确定性地**落到基础版本的 ReportSpec 上。
 *
 * <p>为什么补丁要确定性：模型只给操作，落盘的结构由服务端拼装。这样"改了什么"完全可复现，
 * 也不存在"模型顺手改了别处"的窗口。
 *
 * <p>两条硬边界：
 * <ul>
 *   <li><b>展示类操作只改已存在的块</b>：块不存在、字段不属于图表绑定、指标块不是数字列，
 *       都在这里拒绝（{@code AI_REPORT_REVISION_PLAN_INVALID}），不新建块、不猜字段；</li>
 *   <li><b>数据类操作只用受控查询结果</b>：数据集引用/查询引用/来源三件套全部来自
 *       {@link AiRunQueryExecutionResultDTO}（真实执行结果），模型给不出数字；
 *       结果为空却要新增指标块时明确失败（{@code AI_REPORT_REVISION_UNSUPPORTED}），不编造行。</li>
 * </ul>
 *
 * <p>补丁只操作 JSON 树（保留基础版本里未被触碰的部分），出口再解析一次做结构校验。
 */
public class AiReportSpecPatcher {

    /** 修订新增的数据集引用/查询引用/来源标识前缀（与模型无关，服务端分配）。 */
    private static final String NEW_DATASET_REF_PREFIX = "rev_ds_";

    private static final String NEW_QUERY_REF_PREFIX = "rev_q_";

    private static final String NEW_SOURCE_PREFIX = "rev_src_";

    /** 修订新增块的标识前缀。 */
    private static final String NEW_BLOCK_PREFIX = "rev_block_";

    /** 数据类操作可用的图表/表格列格式映射（结果列类型 → 展示格式）。 */
    private static final Map<String, String> FORMAT_BY_TYPE =
            Map.of("DECIMAL", "DECIMAL", "INTEGER", "NUMBER", "STRING", "TEXT", "DATE", "TEXT", "DATETIME", "TEXT");

    /** 应用操作清单，返回候选规格与差异。 */
    public AiReportSpecPatch apply(
            String baseSpecJson, AiReportRevisionPlan plan, Map<Long, AiRunQueryExecutionResultDTO> queryResults) {
        AiReportSpec base = AiReportSpec.parse(baseSpecJson);
        Map<String, Object> root = tree(baseSpecJson);
        List<Map<String, Object>> blocks = list(root, "blocks");
        List<Map<String, Object>> layoutItems = list(layout(root), "items");
        List<Map<String, Object>> datasetRefs = list(root, "datasetRefs");
        List<Map<String, Object>> queryRefs = list(root, "queryRefs");
        List<Map<String, Object>> sources = list(root, "sources");
        Map<String, String> refIdByDatasetCode = datasetRefIdsByDatasetCode(queryRefs, datasetRefs);
        List<String> addedSources = new ArrayList<>();
        Map<Long, String> refsByDatasetId = new LinkedHashMap<>();
        int sequence = 0;
        for (AiReportRevisionOp op : plan.operations()) {
            sequence++;
            if (op.requiresQuery()) {
                AiRunQueryExecutionResultDTO result = requireQueryResult(op, queryResults);
                applyDataOp(
                        root,
                        op,
                        result,
                        blocks,
                        layoutItems,
                        datasetRefs,
                        queryRefs,
                        sources,
                        refIdByDatasetCode,
                        addedSources,
                        refsByDatasetId,
                        sequence);
            } else {
                applyPresentationOp(root, op, blocks, layoutItems);
            }
        }
        String candidateJson = JsonUtils.toJsonString(root);
        AiReportSpec candidate = AiReportSpec.parse(candidateJson);
        return new AiReportSpecPatch(
                candidateJson,
                AiReportRevisionDiff.between(base, candidate, plan.opCodes(), plan.requiresQuery(), addedSources),
                refsByDatasetId);
    }

    // ==================== 展示类操作（不查库） ====================

    private void applyPresentationOp(
            Map<String, Object> root,
            AiReportRevisionOp op,
            List<Map<String, Object>> blocks,
            List<Map<String, Object>> layoutItems) {
        switch (op.op()) {
            case AiReportRevisionOp.SET_TITLE -> root.put("title", op.value());
            case AiReportRevisionOp.SET_THEME ->
                root.put("themeRef", Map.of("themeId", op.value(), "revision", op.themeRevision()));
            case AiReportRevisionOp.SET_LAYOUT -> setLayout(root, op);
            case AiReportRevisionOp.ADD_TEXT -> addTextBlock(op, blocks, layoutItems);
            case AiReportRevisionOp.REMOVE_BLOCK -> removeBlock(op, blocks, layoutItems);
            case AiReportRevisionOp.SET_BLOCK_TITLE -> requireBlock(blocks, op).put("title", op.value());
            case AiReportRevisionOp.SET_TEXT -> {
                Map<String, Object> block = requireBlock(blocks, op);
                requireType(block, op, "text");
                block.put("text", op.value());
            }
            case AiReportRevisionOp.SET_CHART_TYPE -> {
                Map<String, Object> chart = requireChart(requireBlock(blocks, op), op);
                chart.put("chartType", op.value());
            }
            case AiReportRevisionOp.SET_CHART_FIELD -> {
                Map<String, Object> chart = requireChart(requireBlock(blocks, op), op);
                if (!Set.of("categoryField", "valueField", "seriesField").contains(op.field())) {
                    throw exception(AI_REPORT_REVISION_PLAN_INVALID);
                }
                chart.put(op.field(), op.value());
            }
            case AiReportRevisionOp.SET_TABLE_COLUMNS -> setTableColumns(requireBlock(blocks, op), op);
            case AiReportRevisionOp.SET_PAGE_SIZE -> {
                Map<String, Object> block = requireBlock(blocks, op);
                requireType(block, op, "table");
                block.put("pageSize", op.pageSize());
            }
            case AiReportRevisionOp.SET_METRIC_FIELD -> {
                Map<String, Object> block = requireBlock(blocks, op);
                requireType(block, op, "metric");
                binding(block).put("field", op.value());
            }
            default -> throw exception(AI_REPORT_REVISION_PLAN_INVALID);
        }
    }

    /** 布局：值是一段布局项数组 JSON（列/间距保持不变）。 */
    private void setLayout(Map<String, Object> root, AiReportRevisionOp op) {
        List<Map<String, Object>> items = parseItems(op.value());
        layout(root).put("items", items);
    }

    /** 新增文本块：服务端放在最后一行的整宽位置（模型不参与摆放）。 */
    private void addTextBlock(
            AiReportRevisionOp op, List<Map<String, Object>> blocks, List<Map<String, Object>> layoutItems) {
        if (blocks.stream().anyMatch(block -> op.blockId().equals(block.get("id")))) {
            // 标识重复：不覆盖已有块
            throw exception(AI_REPORT_REVISION_PLAN_INVALID);
        }
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("id", op.blockId());
        block.put("title", op.field() == null || op.field().isBlank() ? "补充说明" : op.field());
        block.put("type", "text");
        block.put("text", op.value());
        blocks.add(block);
        layoutItems.add(layoutItem(op.blockId(), layoutItems));
    }

    private void removeBlock(
            AiReportRevisionOp op, List<Map<String, Object>> blocks, List<Map<String, Object>> layoutItems) {
        Map<String, Object> block = requireBlock(blocks, op);
        blocks.remove(block);
        layoutItems.removeIf(item -> op.blockId().equals(item.get("blockId")));
    }

    /** 表格列：只允许结果列里已存在的字段，标签取结果列的标签（不新造列语义）。 */
    private void setTableColumns(Map<String, Object> block, AiReportRevisionOp op) {
        requireType(block, op, "table");
        List<String> fields = op.tableFields();
        if (fields.isEmpty()) {
            throw exception(AI_REPORT_REVISION_PLAN_INVALID);
        }
        List<Map<String, Object>> columns = new ArrayList<>();
        for (String field : fields) {
            Map<String, Object> column = new LinkedHashMap<>();
            column.put("field", field);
            column.put("label", field);
            column.put("format", "TEXT");
            columns.add(column);
        }
        block.put("columns", columns);
    }

    // ==================== 数据类操作（只用受控查询结果） ====================

    private void applyDataOp(
            Map<String, Object> root,
            AiReportRevisionOp op,
            AiRunQueryExecutionResultDTO result,
            List<Map<String, Object>> blocks,
            List<Map<String, Object>> layoutItems,
            List<Map<String, Object>> datasetRefs,
            List<Map<String, Object>> queryRefs,
            List<Map<String, Object>> sources,
            Map<String, String> refIdByDatasetCode,
            List<String> addedSources,
            Map<Long, String> refsByDatasetId,
            int sequence) {
        switch (op.op()) {
            case AiReportRevisionOp.ADD_METRIC ->
                addMetric(
                        op,
                        result,
                        blocks,
                        layoutItems,
                        datasetRefs,
                        queryRefs,
                        sources,
                        refIdByDatasetCode,
                        addedSources,
                        refsByDatasetId,
                        sequence);
            case AiReportRevisionOp.ADD_DATASET ->
                addDataset(
                        op,
                        result,
                        blocks,
                        layoutItems,
                        datasetRefs,
                        queryRefs,
                        sources,
                        refIdByDatasetCode,
                        addedSources,
                        refsByDatasetId,
                        sequence);
            case AiReportRevisionOp.SET_GRAIN, AiReportRevisionOp.ADD_FILTER ->
                replaceQueryResult(
                        op,
                        result,
                        datasetRefs,
                        queryRefs,
                        sources,
                        refIdByDatasetCode,
                        addedSources,
                        refsByDatasetId,
                        sequence);
            default -> throw exception(AI_REPORT_REVISION_PLAN_INVALID);
        }
    }

    /** 新增指标块：绑定到受控查询结果的数字列，取第 0 行的真实取值（空结果即拒绝）。 */
    private void addMetric(
            AiReportRevisionOp op,
            AiRunQueryExecutionResultDTO result,
            List<Map<String, Object>> blocks,
            List<Map<String, Object>> layoutItems,
            List<Map<String, Object>> datasetRefs,
            List<Map<String, Object>> queryRefs,
            List<Map<String, Object>> sources,
            Map<String, String> refIdByDatasetCode,
            List<String> addedSources,
            Map<Long, String> refsByDatasetId,
            int sequence) {
        AiRunQueryExecutionResultDTO.Column column = columnOf(result, op.metric());
        if (column == null) {
            // 指标不在本次受控查询的结果列里：模型编造了口径
            throw exception(AI_REPORT_REVISION_PLAN_INVALID);
        }
        if (result.getRowCount() < 1) {
            // 受控查询没有数据：新增指标块会得到一个没有数字的"指标"，明确失败而不是编造
            throw exception(AI_REPORT_REVISION_UNSUPPORTED, "受控查询结果为空，无法新增指标块");
        }
        String datasetRefId = ensureQueryResult(
                result,
                datasetRefs,
                queryRefs,
                sources,
                refIdByDatasetCode,
                addedSources,
                refsByDatasetId,
                datasetIdOf(op),
                sequence,
                false);
        String blockId = op.blockId() == null || op.blockId().isBlank() ? NEW_BLOCK_PREFIX + sequence : op.blockId();
        if (blocks.stream().anyMatch(block -> blockId.equals(block.get("id")))) {
            throw exception(AI_REPORT_REVISION_PLAN_INVALID);
        }
        Map<String, Object> binding = new LinkedHashMap<>();
        binding.put("datasetRef", datasetRefId);
        binding.put("field", column.code());
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("id", blockId);
        block.put("title", op.value() == null || op.value().isBlank() ? column.label() : op.value());
        block.put("type", "metric");
        block.put("binding", binding);
        block.put("rowIndex", 0);
        block.put("format", formatFor(column.type()));
        if (column.unit() != null) {
            block.put("unit", column.unit());
        }
        blocks.add(block);
        layoutItems.add(layoutItem(blockId, layoutItems));
    }

    /** 新增数据集来源：新增数据集引用 + 一张明细表（让用户看得到新来源的数据）。 */
    private void addDataset(
            AiReportRevisionOp op,
            AiRunQueryExecutionResultDTO result,
            List<Map<String, Object>> blocks,
            List<Map<String, Object>> layoutItems,
            List<Map<String, Object>> datasetRefs,
            List<Map<String, Object>> queryRefs,
            List<Map<String, Object>> sources,
            Map<String, String> refIdByDatasetCode,
            List<String> addedSources,
            Map<Long, String> refsByDatasetId,
            int sequence) {
        String datasetRefId = ensureQueryResult(
                result,
                datasetRefs,
                queryRefs,
                sources,
                refIdByDatasetCode,
                addedSources,
                refsByDatasetId,
                datasetIdOf(op),
                sequence,
                false);
        String blockId = op.blockId() == null || op.blockId().isBlank() ? NEW_BLOCK_PREFIX + sequence : op.blockId();
        if (blocks.stream().anyMatch(block -> blockId.equals(block.get("id")))) {
            throw exception(AI_REPORT_REVISION_PLAN_INVALID);
        }
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("id", blockId);
        block.put("title", op.value() == null || op.value().isBlank() ? "新增明细" : op.value());
        block.put("type", "table");
        block.put("datasetRef", datasetRefId);
        block.put("columns", tableColumns(result));
        block.put("pageSize", 10);
        blocks.add(block);
        layoutItems.add(layoutItem(blockId, layoutItems));
    }

    /** 同数据集重查（改粒度/加过滤）：沿用原数据集引用标识，块继续绑定同一引用。 */
    private void replaceQueryResult(
            AiReportRevisionOp op,
            AiRunQueryExecutionResultDTO result,
            List<Map<String, Object>> datasetRefs,
            List<Map<String, Object>> queryRefs,
            List<Map<String, Object>> sources,
            Map<String, String> refIdByDatasetCode,
            List<String> addedSources,
            Map<Long, String> refsByDatasetId,
            int sequence) {
        // 结果列变化后，原有块若引用了不存在的列，会在语义校验里被拒绝（fail-closed）
        ensureQueryResult(
                result,
                datasetRefs,
                queryRefs,
                sources,
                refIdByDatasetCode,
                addedSources,
                refsByDatasetId,
                datasetIdOf(op),
                sequence,
                true);
    }

    /**
     * 落库受控查询结果：数据集引用（列/行数/完整性）+ 查询引用（已校验计划）+ 来源。
     *
     * <p>{@code reuseExisting=true} 时同一数据集**原地替换**（改粒度/加过滤：块继续绑定同一引用）；
     * 新增口径（新增指标、新增数据集）必须新建引用，避免已有块被换到新查询的数据上。
     */
    private String ensureQueryResult(
            AiRunQueryExecutionResultDTO result,
            List<Map<String, Object>> datasetRefs,
            List<Map<String, Object>> queryRefs,
            List<Map<String, Object>> sources,
            Map<String, String> refIdByDatasetCode,
            List<String> addedSources,
            Map<Long, String> refsByDatasetId,
            Long datasetId,
            int sequence,
            boolean reuseExisting) {
        // 新增口径（ADD_METRIC/ADD_DATASET）必须新建引用：复用旧引用会让已有块悄悄换成新查询的数据
        String existing = reuseExisting ? refIdByDatasetCode.get(result.getDatasetCode()) : null;
        String datasetRefId = existing == null ? NEW_DATASET_REF_PREFIX + sequence : existing;
        String queryRefId = existing == null ? NEW_QUERY_REF_PREFIX + sequence : queryRefIdOf(datasetRefs, existing);
        Map<String, Object> datasetRef = new LinkedHashMap<>();
        datasetRef.put("id", datasetRefId);
        datasetRef.put("resultRef", result.getResultRef());
        datasetRef.put("queryRef", queryRefId);
        datasetRef.put("columns", resultColumns(result));
        datasetRef.put("rowCount", result.getRowCount());
        datasetRef.put("completeness", result.getCompleteness());
        replaceById(datasetRefs, datasetRef);

        Map<String, Object> queryRef = new LinkedHashMap<>();
        queryRef.put("id", queryRefId);
        queryRef.put("plan", JsonUtils.parseObject(result.getPlanJson(), Map.class));
        replaceById(queryRefs, queryRef);

        Map<String, Object> source = new LinkedHashMap<>();
        source.put("id", existing == null ? NEW_SOURCE_PREFIX + sequence : sourceIdOf(sources, queryRefId));
        source.put("kind", "DATASET");
        source.put("resourceId", result.getDatasetCode());
        source.put("resourceVersion", result.getDatasetVersionNo());
        source.put("queryRef", queryRefId);
        source.put("description", "受控查询结果（按当前权限重新执行，计划哈希 " + result.getPlanHash() + "）");
        replaceById(sources, source);
        addedSources.add(datasetRefId);
        if (datasetId != null) {
            // 记录"这次受控查询写进了哪个引用"：同一数据集在修订后可能同时存在新旧两个引用
            refsByDatasetId.put(datasetId, datasetRefId);
        }
        return datasetRefId;
    }

    // ==================== 树操作辅助 ====================

    private static Map<String, Object> tree(String specJson) {
        Map<?, ?> parsed;
        try {
            parsed = JsonUtils.parseObject(specJson, Map.class);
        } catch (IllegalArgumentException notAnObject) {
            throw exception(AI_REPORT_REVISION_PLAN_INVALID);
        }
        if (parsed == null) {
            throw exception(AI_REPORT_REVISION_PLAN_INVALID);
        }
        Map<String, Object> root = new LinkedHashMap<>();
        parsed.forEach((key, value) -> root.put(String.valueOf(key), value));
        return root;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> layout(Map<String, Object> root) {
        if (!(root.get("layout") instanceof Map<?, ?> node)) {
            throw exception(AI_REPORT_REVISION_PLAN_INVALID);
        }
        return (Map<String, Object>) node;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Map<String, Object> node, String key) {
        if (!(node.get(key) instanceof List<?> items)) {
            throw exception(AI_REPORT_REVISION_PLAN_INVALID);
        }
        for (Object item : items) {
            if (!(item instanceof Map<?, ?>)) {
                throw exception(AI_REPORT_REVISION_PLAN_INVALID);
            }
        }
        // 直接返回 JSON 树里的列表实例：补丁必须改到树上（返回副本会让新增/删除静默丢失）
        return (List<Map<String, Object>>) items;
    }

    private static List<Map<String, Object>> parseItems(String json) {
        if (json == null || json.isBlank()) {
            throw exception(AI_REPORT_REVISION_PLAN_INVALID);
        }
        List<?> parsed;
        try {
            parsed = JsonUtils.parseArray(json, Map.class);
        } catch (IllegalArgumentException notAnArray) {
            throw exception(AI_REPORT_REVISION_PLAN_INVALID);
        }
        if (parsed == null || parsed.isEmpty()) {
            throw exception(AI_REPORT_REVISION_PLAN_INVALID);
        }
        List<Map<String, Object>> items = new ArrayList<>();
        for (Object item : parsed) {
            if (!(item instanceof Map<?, ?> map)) {
                throw exception(AI_REPORT_REVISION_PLAN_INVALID);
            }
            Map<String, Object> mapped = new LinkedHashMap<>();
            map.forEach((key, value) -> mapped.put(String.valueOf(key), value));
            items.add(mapped);
        }
        return items;
    }

    private static Map<String, Object> requireBlock(List<Map<String, Object>> blocks, AiReportRevisionOp op) {
        return blocks.stream()
                .filter(block -> op.blockId().equals(block.get("id")))
                .findFirst()
                .orElseThrow(() -> exception(AI_REPORT_REVISION_PLAN_INVALID));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> binding(Map<String, Object> block) {
        if (!(block.get("binding") instanceof Map<?, ?> node)) {
            throw exception(AI_REPORT_REVISION_PLAN_INVALID);
        }
        return (Map<String, Object>) node;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> requireChart(Map<String, Object> block, AiReportRevisionOp op) {
        requireType(block, op, "chart");
        if (!(block.get("chart") instanceof Map<?, ?> node)) {
            throw exception(AI_REPORT_REVISION_PLAN_INVALID);
        }
        return (Map<String, Object>) node;
    }

    private static void requireType(Map<String, Object> block, AiReportRevisionOp op, String type) {
        if (!type.equals(String.valueOf(block.get("type")))) {
            // 操作类型与块类型不符：拒绝（例如对表格块执行"改图表类型"）
            throw exception(AI_REPORT_REVISION_PLAN_INVALID);
        }
    }

    /** 新块摆到最后一行（整宽）；模型不参与摆放，避免重叠与越界。 */
    private static Map<String, Object> layoutItem(String blockId, List<Map<String, Object>> layoutItems) {
        int row = 0;
        for (Map<String, Object> item : layoutItems) {
            if (item.get("row") instanceof Number number) {
                row = Math.max(row, number.intValue() + 1);
            }
        }
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("blockId", blockId);
        item.put("row", row);
        item.put("column", 0);
        item.put("span", AiReportSpec.LAYOUT_COLUMNS);
        return item;
    }

    /**
     * 数据集标识 → 数据集引用标识（从基础版本的查询计划与数据集引用反推）。
     *
     * <p>反推路径：查询计划的 {@code datasetId}（{@code dset_} + 数据集标识）→ 引用该计划的
     * 数据集引用标识。数据类操作重查同一数据集时沿用原引用标识，已有块不用改绑定。
     */
    private static Map<String, String> datasetRefIdsByDatasetCode(
            List<Map<String, Object>> queryRefs, List<Map<String, Object>> datasetRefs) {
        Map<String, String> queryRefIdByDatasetCode = new LinkedHashMap<>();
        for (Map<String, Object> queryRef : queryRefs) {
            if (!(queryRef.get("plan") instanceof Map<?, ?> plan)) {
                continue;
            }
            Object planDatasetId = plan.get("datasetId");
            if (planDatasetId == null) {
                continue;
            }
            String planId = String.valueOf(planDatasetId);
            String code = planId.startsWith("dset_") ? planId.substring("dset_".length()) : planId;
            queryRefIdByDatasetCode.putIfAbsent(code, String.valueOf(queryRef.get("id")));
        }
        Map<String, String> datasetRefIdByDatasetCode = new LinkedHashMap<>();
        queryRefIdByDatasetCode.forEach((code, queryRefId) -> datasetRefs.stream()
                .filter(ref -> queryRefId.equals(ref.get("queryRef")))
                .map(ref -> String.valueOf(ref.get("id")))
                .findFirst()
                .ifPresent(datasetRefId -> datasetRefIdByDatasetCode.put(code, datasetRefId)));
        return datasetRefIdByDatasetCode;
    }

    private static String queryRefIdOf(List<Map<String, Object>> datasetRefs, String datasetRefId) {
        return datasetRefs.stream()
                .filter(ref -> datasetRefId.equals(ref.get("id")))
                .map(ref -> String.valueOf(ref.get("queryRef")))
                .findFirst()
                .orElseThrow(() -> exception(AI_REPORT_REVISION_PLAN_INVALID));
    }

    private static String sourceIdOf(List<Map<String, Object>> sources, String queryRefId) {
        return sources.stream()
                .filter(source -> queryRefId.equals(source.get("queryRef")))
                .map(source -> String.valueOf(source.get("id")))
                .findFirst()
                .orElseThrow(() -> exception(AI_REPORT_REVISION_PLAN_INVALID));
    }

    private static void replaceById(List<Map<String, Object>> nodes, Map<String, Object> replacement) {
        for (int index = 0; index < nodes.size(); index++) {
            if (replacement.get("id").equals(nodes.get(index).get("id"))) {
                nodes.set(index, replacement);
                return;
            }
        }
        nodes.add(replacement);
    }

    /** 操作声明的数据集编号（结构已校验为数字）。 */
    private static Long datasetIdOf(AiReportRevisionOp op) {
        try {
            return Long.valueOf(op.datasetId());
        } catch (NumberFormatException notANumber) {
            throw exception(AI_REPORT_REVISION_PLAN_INVALID);
        }
    }

    private static AiRunQueryExecutionResultDTO requireQueryResult(
            AiReportRevisionOp op, Map<Long, AiRunQueryExecutionResultDTO> queryResults) {
        Long datasetId;
        try {
            datasetId = Long.valueOf(op.datasetId());
        } catch (NumberFormatException notANumber) {
            throw exception(AI_REPORT_REVISION_PLAN_INVALID);
        }
        AiRunQueryExecutionResultDTO result = queryResults == null ? null : queryResults.get(datasetId);
        if (result == null) {
            // 数据类操作没有受控查询结果：拒绝（不允许用旧数据凑新口径）
            throw exception(AI_REPORT_REVISION_UNSUPPORTED, "缺少受控查询结果");
        }
        return result;
    }

    private static AiRunQueryExecutionResultDTO.Column columnOf(AiRunQueryExecutionResultDTO result, String code) {
        for (AiRunQueryExecutionResultDTO.Column column :
                result.getColumns() == null ? List.<AiRunQueryExecutionResultDTO.Column>of() : result.getColumns()) {
            if (column.code().equals(code)) {
                return column;
            }
        }
        return null;
    }

    private static List<Map<String, Object>> resultColumns(AiRunQueryExecutionResultDTO result) {
        List<Map<String, Object>> columns = new ArrayList<>();
        for (AiRunQueryExecutionResultDTO.Column column :
                result.getColumns() == null ? List.<AiRunQueryExecutionResultDTO.Column>of() : result.getColumns()) {
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("field", column.code());
            node.put("label", column.label() == null || column.label().isBlank() ? column.code() : column.label());
            node.put("dataType", dataTypeFor(column.type()));
            columns.add(node);
        }
        if (columns.isEmpty()) {
            throw exception(AI_REPORT_REVISION_UNSUPPORTED, "受控查询没有返回结果列");
        }
        return columns;
    }

    /** 表格列：取前 10 个结果列（页面宽度有限，多余列由用户在页面上自行调整）。 */
    private static List<Map<String, Object>> tableColumns(AiRunQueryExecutionResultDTO result) {
        List<Map<String, Object>> columns = new ArrayList<>();
        for (Map<String, Object> node : resultColumns(result)) {
            if (columns.size() >= 10) {
                break;
            }
            Map<String, Object> column = new LinkedHashMap<>();
            column.put("field", node.get("field"));
            column.put("label", node.get("label"));
            column.put("format", formatFor(String.valueOf(node.get("dataType"))));
            columns.add(column);
        }
        return columns;
    }

    /** 语义类型 → ReportSpec 的数据类型域（R01 限定六种）。 */
    private static String dataTypeFor(String type) {
        return switch (type == null ? "" : type.toUpperCase(java.util.Locale.ROOT)) {
            case "DECIMAL" -> "DECIMAL";
            case "INTEGER", "NUMBER", "LONG" -> "INTEGER";
            case "BOOLEAN" -> "BOOLEAN";
            case "DATE" -> "DATE";
            case "DATETIME" -> "DATETIME";
            default -> "STRING";
        };
    }

    private static String formatFor(String dataType) {
        return FORMAT_BY_TYPE.getOrDefault(dataType == null ? "" : dataType.toUpperCase(java.util.Locale.ROOT), "TEXT");
    }
}

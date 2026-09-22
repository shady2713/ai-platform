package com.basicframework.module.ai.service.report.validation;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_REPORT_CHART_FIELD_INVALID;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_REPORT_LAYOUT_INVALID;
import static com.basicframework.module.ai.enums.AiErrorCodeConstants.AI_REPORT_REFERENCE_INVALID;

import com.basicframework.module.ai.domain.report.AiReportSpec;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 报表语义校验（R01）：结构合规之后，回答"引用是否存在、布局是否合法、类型是否对得上"。
 *
 * <p>五类检查（与卡片逐步实施一一对应）：
 * <ol>
 *   <li><b>引用唯一且存在</b>：块/数据集/查询/来源的标识不得重复；布局只能引用已声明的块，
 *       且每个块恰好出现一次；数据集引用必须指向已声明的查询；来源若声明 queryRef 也必须存在；</li>
 *   <li><b>布局不重叠、不越界</b>：列 + 跨度不超过 12；同一行上任意两个块的列区间不得相交；</li>
 *   <li><b>图表字段与结果 Schema 一致</b>：类目字段必须是文本/日期，数值字段必须是数字类型，
 *       系列字段必须是文本；字段必须在被引用数据集的结果列里存在（缺字段图表不可生成）；</li>
 *   <li><b>表格与指标字段存在且类型合理</b>：表格列、指标绑定的字段都必须在结果列里存在；
 *       指标必须是数字类型（金额与统计口径来自计算结果，模型不能自造数字）；</li>
 *   <li><b>行索引有界</b>：指标的 rowIndex 必须落在该数据集的 rowCount 之内（越界说明模型在编造行）。</li>
 * </ol>
 */
public class AiReportSpecValidator {

    /** 校验并返回原规格（校验失败抛稳定错误码）。 */
    public AiReportSpec validate(AiReportSpec spec) {
        if (spec == null) {
            throw exception(AI_REPORT_REFERENCE_INVALID);
        }
        Map<String, AiReportSpec.Block> blocks = uniqueBlocks(spec);
        Map<String, AiReportSpec.DatasetRef> datasets = uniqueDatasets(spec);
        Set<String> queryRefs = uniqueQueryRefs(spec);
        uniqueSources(spec, queryRefs);
        validateLayout(spec, blocks);
        validateDatasets(datasets, queryRefs);
        for (AiReportSpec.Block block : spec.blocks()) {
            switch (block.type()) {
                case "metric" -> validateMetric(block, datasets);
                case "table" -> validateTable(block, datasets);
                case "chart" -> validateChart(block, datasets);
                default -> {
                    // text：内容已在结构校验里做过脚本片段检查，语义上无引用
                }
            }
        }
        return spec;
    }

    private Map<String, AiReportSpec.Block> uniqueBlocks(AiReportSpec spec) {
        Map<String, AiReportSpec.Block> blocks = spec.blocksById();
        if (blocks.size() != spec.blocks().size()) {
            throw exception(AI_REPORT_REFERENCE_INVALID);
        }
        return blocks;
    }

    private Map<String, AiReportSpec.DatasetRef> uniqueDatasets(AiReportSpec spec) {
        Map<String, AiReportSpec.DatasetRef> datasets = spec.datasetRefsById();
        if (datasets.size() != spec.datasetRefs().size()) {
            throw exception(AI_REPORT_REFERENCE_INVALID);
        }
        return datasets;
    }

    private Set<String> uniqueQueryRefs(AiReportSpec spec) {
        Set<String> ids = new LinkedHashSet<>();
        for (AiReportSpec.QueryRef ref : spec.queryRefs()) {
            if (!ids.add(ref.id())) {
                throw exception(AI_REPORT_REFERENCE_INVALID);
            }
        }
        return ids;
    }

    private void uniqueSources(AiReportSpec spec, Set<String> queryRefs) {
        Set<String> ids = new HashSet<>();
        for (AiReportSpec.Source source : spec.sources()) {
            if (!ids.add(source.id())) {
                throw exception(AI_REPORT_REFERENCE_INVALID);
            }
            if (source.queryRef() != null && !queryRefs.contains(source.queryRef())) {
                // 来源声明了查询引用就必须存在：不允许"看起来有出处"的悬空引用
                throw exception(AI_REPORT_REFERENCE_INVALID);
            }
        }
    }

    private void validateLayout(AiReportSpec spec, Map<String, AiReportSpec.Block> blocks) {
        Set<String> placed = new HashSet<>();
        List<AiReportSpec.LayoutItem> items = spec.layout();
        for (AiReportSpec.LayoutItem item : items) {
            if (!blocks.containsKey(item.blockId())) {
                throw exception(AI_REPORT_REFERENCE_INVALID);
            }
            if (!placed.add(item.blockId())) {
                // 同一个块不能摆两次：重复摆放会让界面出现"两个一样的块"
                throw exception(AI_REPORT_LAYOUT_INVALID);
            }
            if (item.column() + item.span() > AiReportSpec.LAYOUT_COLUMNS) {
                throw exception(AI_REPORT_LAYOUT_INVALID);
            }
        }
        if (placed.size() != blocks.size()) {
            // 每个块都必须出现在布局里，否则块不可见（模型可能借此"藏"内容）
            throw exception(AI_REPORT_LAYOUT_INVALID);
        }
        // 同列区间相交即重叠（行按整数行号比较；行号相同的块不能列区间相交）
        for (int i = 0; i < items.size(); i++) {
            for (int j = i + 1; j < items.size(); j++) {
                AiReportSpec.LayoutItem left = items.get(i);
                AiReportSpec.LayoutItem right = items.get(j);
                if (left.row() != right.row()) {
                    continue;
                }
                int leftEnd = left.column() + left.span();
                int rightEnd = right.column() + right.span();
                if (left.column() < rightEnd && right.column() < leftEnd) {
                    throw exception(AI_REPORT_LAYOUT_INVALID);
                }
            }
        }
    }

    private void validateDatasets(Map<String, AiReportSpec.DatasetRef> datasets, Set<String> queryRefs) {
        for (AiReportSpec.DatasetRef dataset : datasets.values()) {
            if (!queryRefs.contains(dataset.queryRef())) {
                throw exception(AI_REPORT_REFERENCE_INVALID);
            }
            Set<String> fields = new HashSet<>();
            for (AiReportSpec.ResultColumn column : dataset.columns()) {
                if (!fields.add(column.field())) {
                    throw exception(AI_REPORT_REFERENCE_INVALID);
                }
            }
        }
    }

    private void validateMetric(AiReportSpec.Block block, Map<String, AiReportSpec.DatasetRef> datasets) {
        AiReportSpec.DatasetRef dataset = requireDataset(block.datasetRef(), datasets);
        AiReportSpec.ResultColumn column = requireColumn(dataset, block.metricField());
        if (!isNumeric(column.dataType())) {
            // 指标必须绑定数字列：文本列上的"指标"是模型编造的数字
            throw exception(AI_REPORT_CHART_FIELD_INVALID);
        }
        if (block.rowIndex() == null || block.rowIndex() >= dataset.rowCount()) {
            throw exception(AI_REPORT_REFERENCE_INVALID);
        }
    }

    private void validateTable(AiReportSpec.Block block, Map<String, AiReportSpec.DatasetRef> datasets) {
        AiReportSpec.DatasetRef dataset = requireDataset(block.datasetRef(), datasets);
        for (AiReportSpec.TableColumn column : block.columns()) {
            requireColumn(dataset, column.field());
        }
        if (block.pageSize() == null || block.pageSize() < 1 || block.pageSize() > 100) {
            throw exception(AI_REPORT_REFERENCE_INVALID);
        }
    }

    private void validateChart(AiReportSpec.Block block, Map<String, AiReportSpec.DatasetRef> datasets) {
        AiReportSpec.DatasetRef dataset = requireDataset(block.datasetRef(), datasets);
        AiReportSpec.Chart chart = block.chart();
        AiReportSpec.ResultColumn category = requireColumn(dataset, chart.categoryField());
        if (!Set.of("STRING", "DATE", "DATETIME", "BOOLEAN").contains(category.dataType())) {
            throw exception(AI_REPORT_CHART_FIELD_INVALID);
        }
        AiReportSpec.ResultColumn value = requireColumn(dataset, chart.valueField());
        if (!isNumeric(value.dataType())) {
            throw exception(AI_REPORT_CHART_FIELD_INVALID);
        }
        if (chart.seriesField() != null) {
            AiReportSpec.ResultColumn series = requireColumn(dataset, chart.seriesField());
            if (!"STRING".equals(series.dataType())) {
                throw exception(AI_REPORT_CHART_FIELD_INVALID);
            }
        }
    }

    private static AiReportSpec.DatasetRef requireDataset(
            String datasetRef, Map<String, AiReportSpec.DatasetRef> datasets) {
        AiReportSpec.DatasetRef dataset = datasets.get(datasetRef);
        if (dataset == null) {
            // 未知 dataset 一律拒绝（含"模型自己造了一个数据集"的情形）
            throw exception(AI_REPORT_REFERENCE_INVALID);
        }
        return dataset;
    }

    private static AiReportSpec.ResultColumn requireColumn(AiReportSpec.DatasetRef dataset, String field) {
        List<AiReportSpec.ResultColumn> columns = new ArrayList<>(dataset.columns());
        return columns.stream()
                .filter(column -> column.field().equals(field))
                .findFirst()
                .orElseThrow(() -> exception(AI_REPORT_CHART_FIELD_INVALID));
    }

    private static boolean isNumeric(String dataType) {
        return "INTEGER".equals(dataType) || "DECIMAL".equals(dataType);
    }
}

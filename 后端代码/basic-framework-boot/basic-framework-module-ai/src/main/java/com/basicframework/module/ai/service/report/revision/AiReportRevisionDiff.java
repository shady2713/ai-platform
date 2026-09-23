package com.basicframework.module.ai.service.report.revision;

import com.basicframework.module.ai.domain.report.AiReportSpec;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 修订差异（R05）：基础版本与候选版本之间**结构性**的改动清单（"计划差异处理"）。
 *
 * <p>为什么要把差异算出来再返回：对话修改必须能回答"到底改了什么"。
 * 只回一个"修订成功"会让用户在没察觉的情况下接受别处的改动（例如顺手换了数据集）；
 * 差异按块/数据集/来源逐项列出，前端可以只高亮真正变化的部分。
 *
 * <p>差异只描述结构，不含数据行：块的取值变化（数字）属于数据，不在这里回显。
 */
public record AiReportRevisionDiff(
        boolean queryRequired,
        List<String> operations,
        boolean titleChanged,
        boolean themeChanged,
        boolean layoutChanged,
        List<String> addedBlocks,
        List<String> removedBlocks,
        List<String> modifiedBlocks,
        List<String> addedDatasetRefs,
        List<String> removedDatasetRefs,
        List<String> addedSources) {

    public AiReportRevisionDiff {
        operations = operations == null ? List.of() : List.copyOf(operations);
        addedBlocks = addedBlocks == null ? List.of() : List.copyOf(addedBlocks);
        removedBlocks = removedBlocks == null ? List.of() : List.copyOf(removedBlocks);
        modifiedBlocks = modifiedBlocks == null ? List.of() : List.copyOf(modifiedBlocks);
        addedDatasetRefs = addedDatasetRefs == null ? List.of() : List.copyOf(addedDatasetRefs);
        removedDatasetRefs = removedDatasetRefs == null ? List.of() : List.copyOf(removedDatasetRefs);
        addedSources = addedSources == null ? List.of() : List.copyOf(addedSources);
    }

    /** 计算两份已校验规格之间的差异。 */
    public static AiReportRevisionDiff between(
            AiReportSpec base,
            AiReportSpec candidate,
            List<String> operations,
            boolean queryRequired,
            List<String> addedSources) {
        List<String> addedBlocks = new ArrayList<>();
        List<String> modifiedBlocks = new ArrayList<>();
        for (AiReportSpec.Block block : candidate.blocks()) {
            AiReportSpec.Block previous = base.blocksById().get(block.id());
            if (previous == null) {
                addedBlocks.add(block.id());
            } else if (!describe(block).equals(describe(previous))) {
                modifiedBlocks.add(block.id());
            }
        }
        List<String> removedBlocks = new ArrayList<>();
        for (AiReportSpec.Block block : base.blocks()) {
            if (!candidate.blocksById().containsKey(block.id())) {
                removedBlocks.add(block.id());
            }
        }
        List<String> addedDatasets = new ArrayList<>();
        for (AiReportSpec.DatasetRef ref : candidate.datasetRefs()) {
            if (!base.datasetRefsById().containsKey(ref.id())) {
                addedDatasets.add(ref.id());
            }
        }
        List<String> removedDatasets = new ArrayList<>();
        for (AiReportSpec.DatasetRef ref : base.datasetRefs()) {
            if (!candidate.datasetRefsById().containsKey(ref.id())) {
                removedDatasets.add(ref.id());
            }
        }
        return new AiReportRevisionDiff(
                queryRequired,
                operations,
                !base.title().equals(candidate.title()),
                !(base.themeId().equals(candidate.themeId()) && base.themeRevision() == candidate.themeRevision()),
                !describeLayout(base).equals(describeLayout(candidate)),
                addedBlocks,
                removedBlocks,
                modifiedBlocks,
                addedDatasets,
                removedDatasets,
                addedSources);
    }

    /** 是否有任何结构性改动（没有改动的"修订"要如实报告，而不是假装改了）。 */
    public boolean changed() {
        return titleChanged
                || themeChanged
                || layoutChanged
                || !addedBlocks.isEmpty()
                || !removedBlocks.isEmpty()
                || !modifiedBlocks.isEmpty()
                || !addedDatasetRefs.isEmpty()
                || !removedDatasetRefs.isEmpty();
    }

    /** 块的稳定描述（逐字段比较，含图表/表格/指标绑定）。 */
    private static String describe(AiReportSpec.Block block) {
        return block.type()
                + "|"
                + block.title()
                + "|"
                + block.text()
                + "|"
                + block.datasetRef()
                + "|"
                + block.metricField()
                + "|"
                + block.rowIndex()
                + "|"
                + block.format()
                + "|"
                + block.unit()
                + "|"
                + block.pageSize()
                + "|"
                + block.columns()
                + "|"
                + block.chart();
    }

    /** 布局的稳定描述（按块标识排序，避免摆放顺序造成假差异）。 */
    private static String describeLayout(AiReportSpec spec) {
        Set<String> items = new LinkedHashSet<>();
        spec.layout().stream()
                .map(item -> item.blockId() + "@" + item.row() + "," + item.column() + "+" + item.span())
                .sorted()
                .forEach(items::add);
        return String.join(";", items);
    }
}

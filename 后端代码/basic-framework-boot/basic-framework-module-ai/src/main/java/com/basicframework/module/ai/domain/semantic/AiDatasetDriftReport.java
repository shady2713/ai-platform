package com.basicframework.module.ai.domain.semantic;

import java.util.List;

/**
 * 结构漂移结论（D04）：定义与上游结构的差异。
 *
 * <p>三类的处置不同，必须分开表达：
 * <ul>
 *   <li><b>缺失列</b>（定义引用了上游没有的列）：定义不可执行，必须改定义或等上游恢复 → 阻塞发布；</li>
 *   <li><b>类型变化</b>（列在但类型不再兼容）：语义承诺失效 → 阻塞发布；</li>
 *   <li><b>新增列</b>（上游多了列）：不影响已声明范围 → 只记录，不阻塞（供人工决定是否纳入定义）。</li>
 * </ul>
 */
public record AiDatasetDriftReport(
        List<String> missingColumns,
        List<String> typeChangedColumns,
        List<String> addedColumns,
        String declaredHash,
        String upstreamHash) {

    public AiDatasetDriftReport {
        missingColumns = missingColumns == null ? List.of() : List.copyOf(missingColumns);
        typeChangedColumns = typeChangedColumns == null ? List.of() : List.copyOf(typeChangedColumns);
        addedColumns = addedColumns == null ? List.of() : List.copyOf(addedColumns);
    }

    /** 是否可发布：没有缺失列也没有类型变化。 */
    public boolean isPublishable() {
        return missingColumns.isEmpty() && typeChangedColumns.isEmpty();
    }

    /** 是否发生过任何漂移（含仅新增列）。 */
    public boolean hasDrift() {
        return !isPublishable() || !addedColumns.isEmpty();
    }

    /** 稳定摘要（落库 drift_json；不含上游数据，只含列名与结论）。 */
    public String summary() {
        return "missing=" + String.join(",", missingColumns)
                + ";typeChanged=" + String.join(",", typeChangedColumns)
                + ";added=" + String.join(",", addedColumns);
    }
}

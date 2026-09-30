package com.basicframework.module.ai.service.queryplan;

import java.util.List;

/**
 * 已校验的跨源查询计划（Y03）：**多来源**聚合的唯一可执行形态。
 *
 * <p>与 {@code ValidatedQueryPlan}（D05，单数据集）刻意分开而不是合并：
 * 单数据集计划是"一个数据集内选字段"，跨源计划是"若干个来源各自聚合后再关联"，
 * 两者的安全前提完全不同（后者必须钉住口径版本、显式选择每个来源的数据集版本与映射版本）。
 * 把它们塞进一个类里，最终一定会出现"单数据集路径悄悄放过多对多关联"的分支。
 *
 * <p>构造器收进包内：只有 {@link AiCrossSourceQueryPlanValidator} 能 new，
 * 因此"通过校验"是进入执行路径的**结构性**前提，而不是调用方的自觉。
 */
public record CrossSourceQueryPlan(
        String metricCode,
        Integer semanticsRevision,
        String semanticsDefinitionHash,
        List<SourceSelection> sources,
        List<String> aggregationOrder,
        String currency,
        String timezone,
        String timeWindow,
        String unit,
        String planHash) {

    public CrossSourceQueryPlan {
        sources = sources == null ? List.of() : List.copyOf(sources);
        aggregationOrder = aggregationOrder == null ? List.of() : List.copyOf(aggregationOrder);
    }

    /**
     * 一个来源的显式选择：数据集版本 + 映射版本 + 该来源的主键粒度。
     *
     * @param datasetCode     数据集标识
     * @param datasetVersion  数据集版本号（必须显式给出，不接受"取当前版本"）
     * @param mappingRevision Y02 主数据映射版本号（跨系统标识的判定依据）
     * @param role            来源角色（订单 / 发票 / 回款…）
     * @param primaryKey      该来源的主键粒度（扇出判定的事实基础）
     * @param preAggregated   是否已按该粒度预聚合
     */
    public record SourceSelection(
            String datasetCode,
            Integer datasetVersion,
            Long mappingRevision,
            String role,
            List<String> primaryKey,
            boolean preAggregated) {

        public SourceSelection {
            primaryKey = primaryKey == null ? List.of() : List.copyOf(primaryKey);
        }
    }
}

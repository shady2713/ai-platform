package com.basicframework.module.ai.domain.query;

import java.util.List;

/**
 * 计划构造测试助手（D05/D06）：与 {@link ValidatedQueryPlan} 同包，因此能调用其包内构造器。
 *
 * <p>存在意义：`ValidatedQueryPlan` 的构造器对包外不可见（只有校验器能建立），
 * 这正是不变式；要测试"编译期面对被篡改的计划仍然拒绝"，就需要同包的助手来伪造非法计划。
 * 生产代码无法这样构造——这就是本类只存在于测试源码里的原因。
 */
public final class ValidatedQueryPlanTestFactory {

    private ValidatedQueryPlanTestFactory() {}

    /** 复制一份计划但替换指标列表（用于模拟聚合/列被篡改）。 */
    public static ValidatedQueryPlan withMetrics(ValidatedQueryPlan plan, List<ValidatedQueryPlan.Metric> metrics) {
        return new ValidatedQueryPlan(
                plan.datasetId(),
                plan.datasetCode(),
                plan.datasetVersionId(),
                plan.datasetVersionNo(),
                plan.schemaHash(),
                plan.sourceObject(),
                metrics,
                plan.dimensions(),
                plan.filters(),
                plan.timeWindow(),
                plan.orderBy(),
                plan.limit());
    }

    /** 复制一份计划但替换过滤条件（用于模拟"绕过计划校验器的取值"）。 */
    public static ValidatedQueryPlan withFilters(ValidatedQueryPlan plan, List<ValidatedQueryPlan.Filter> filters) {
        return new ValidatedQueryPlan(
                plan.datasetId(),
                plan.datasetCode(),
                plan.datasetVersionId(),
                plan.datasetVersionNo(),
                plan.schemaHash(),
                plan.sourceObject(),
                plan.metrics(),
                plan.dimensions(),
                filters,
                plan.timeWindow(),
                plan.orderBy(),
                plan.limit());
    }

    /** 空选择项的计划（既无指标也无维度）。 */
    public static ValidatedQueryPlan empty(ValidatedQueryPlan plan) {
        return new ValidatedQueryPlan(
                plan.datasetId(),
                plan.datasetCode(),
                plan.datasetVersionId(),
                plan.datasetVersionNo(),
                plan.schemaHash(),
                plan.sourceObject(),
                List.of(),
                List.of(),
                List.of(),
                null,
                List.of(),
                plan.limit());
    }
}

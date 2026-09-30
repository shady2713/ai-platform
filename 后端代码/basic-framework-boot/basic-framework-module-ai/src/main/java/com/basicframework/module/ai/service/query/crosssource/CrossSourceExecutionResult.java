package com.basicframework.module.ai.service.query.crosssource;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

/**
 * 跨源执行的统一结果（Y04）：**各来源的时间点与预算用量是结果的一部分**，不是日志。
 *
 * <p>本类存在的理由是"跨源数字不能假装是同一时刻算出来的"。三个来源各自有数据时间，
 * 它们只能一起解释为**所有来源都成立的那个时刻**——也就是各源数据时间的最小值
 * （{@link #consistencyAsOf}）；任意一个来源更新得慢，跨源合计就只到那个时刻为止。
 *
 * <p>把 {@link #maxSkewMillis}、每源 {@code asOf}、预算用量、缺失来源都放进结果里，
 * 是为了让调用方能自己判断"这个数能不能用"，而不是让平台替它悄悄选一个时刻。
 * 偏移超限时执行被阻断（{@code AI_CROSS_SOURCE_CONSISTENCY_SKEW_CONFLICT}），而不是照常出数。
 *
 * <p>缺哪些来源也必须显式：{@link #missingRoles} 非空时，结果是**不完整**的，
 * 调用方据此决定是追问还是接受，绝不把"某个来源没取到"当成 0。
 */
public record CrossSourceExecutionResult(
        String executionKey,
        String planHash,
        String metricCode,
        Integer semanticsRevision,
        String currency,
        BigDecimal totalAmount,
        List<SourceResult> sources,
        LocalDateTime consistencyAsOf,
        long maxSkewMillis,
        List<String> missingRoles,
        BudgetUsage budgetUsage,
        boolean complete) {

    public CrossSourceExecutionResult {
        sources = sources == null ? List.of() : List.copyOf(sources);
        missingRoles = missingRoles == null ? List.of() : List.copyOf(missingRoles);
    }

    /**
     * 单个来源的执行结果。
     *
     * @param role           来源角色
     * @param datasetCode    数据集标识
     * @param datasetVersion 数据集版本号
     * @param mappingRevision 实体键映射版本（参与关联的版本）
     * @param amount         该来源预聚合后的金额（源内已按主键粒度聚合）
     * @param rowCount       该来源返回的中间结果行数
     * @param byteSize       该来源中间结果占用的字节（预算计量）
     * @param asOf           **该来源自己的**数据时间点（不是执行时刻）
     * @param elapsedMillis  该来源耗时
     * @param counted        是否已计入合计（重试幂等标记）
     */
    public record SourceResult(
            String role,
            String datasetCode,
            Integer datasetVersion,
            Long mappingRevision,
            BigDecimal amount,
            int rowCount,
            long byteSize,
            LocalDateTime asOf,
            long elapsedMillis,
            boolean counted) {

        public SourceResult {
            amount = amount == null ? BigDecimal.ZERO : amount;
        }
    }

    /**
     * 预算用量（与预算上限一起构成"这次执行到底跑了多大"的证据）。
     *
     * @param totalBytes        全部来源累计中间结果字节
     * @param totalRows         全部来源累计行数
     * @param maxConcurrentUsed 实际并发来源数峰值
     */
    public record BudgetUsage(long totalBytes, int totalRows, int maxConcurrentUsed) {

        public BudgetUsage {
            // 用量是计量值，任何负数都意味着计量被破坏；按 0 处理会让"超预算"看起来像"没超"
            if (totalBytes < 0) {
                totalBytes = 0;
            }
            if (totalRows < 0) {
                totalRows = 0;
            }
            if (maxConcurrentUsed < 0) {
                maxConcurrentUsed = 0;
            }
        }
    }

    /** 结果摘要（进日志与执行记录；只含口径与计量，不含任何来源数据行）。 */
    public String describe() {
        return String.format(
                Locale.ROOT,
                "metric=%s, rev=%s, total=%s %s, sources=%d, missing=%d, complete=%s, asOf=%s, skew=%dms, bytes=%d",
                metricCode,
                semanticsRevision,
                totalAmount == null ? "null" : totalAmount.toPlainString(),
                currency,
                sources.size(),
                missingRoles.size(),
                complete,
                consistencyAsOf,
                maxSkewMillis,
                budgetUsage == null ? 0 : budgetUsage.totalBytes());
    }

    /** 偏移量（毫秒）：便于调用方与断言直接比较。 */
    public Duration skew() {
        return Duration.ofMillis(maxSkewMillis);
    }

    /** 取某来源的结果（未执行返回 null；不抛异常，让调用方自己判断缺哪些来源）。 */
    public SourceResult sourceOf(String role) {
        for (SourceResult source : sources) {
            if (source.role().equals(role)) {
                return source;
            }
        }
        return null;
    }

    /** 结果是否可用：完整且无缺失来源。缺来源的结果不满足这个条件。 */
    public boolean usable() {
        return complete && missingRoles.isEmpty() && consistencyAsOf != null;
    }
}

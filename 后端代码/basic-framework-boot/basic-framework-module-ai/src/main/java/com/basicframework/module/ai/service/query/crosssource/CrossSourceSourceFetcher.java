package com.basicframework.module.ai.service.query.crosssource;

import java.util.List;

/**
 * 跨源取数端口（Y04）：执行一个来源的预聚合并返回有界中间结果。
 *
 * <p>刻意做成端口而不是直接调用 D06：跨源执行关心的是"这个来源取到没有、取到多少、
 * 数据时间点是什么"，而连接、超时与只读账号由 D03/D06 负责。两者的关注点不同，
 * 直接耦合会让跨源执行绕开 D03 的只读守卫去拿连接。
 *
 * <p>返回的 {@link FetchedRows} 必须如实报告 {@code truncated}：源内已预聚合的 SUM 被截断后
 * 仍然是"合法数字"但偏小，这是跨源聚合里最容易被当成正确结果的一类错误。
 * 因此实现方**不得**在截断时悄悄丢掉这个标记。
 */
public interface CrossSourceSourceFetcher {

    /**
     * 取一个来源的预聚合结果。
     *
     * <p>实现方必须在 {@code budget} 的行数与字节上限内结束；越界时抛
     * {@code AI_CROSS_SOURCE_RESULT_TOO_LARGE} 而不是截断。
     */
    FetchedRows fetch(CrossSourceSourceRequest request, CrossSourceBudget budget);

    /**
     * 一个来源的有界中间结果。
     *
     * @param rows        预聚合后的行（按实体键分组）
     * @param truncated   来源是否在自身行数上限内未能取全
     * @param byteSize    中间结果字节（预算计量）
     * @param asOf        **该来源自己的**数据时间点（由 {@code MAX(时间列)} 得出）
     * @param elapsedMillis 该来源耗时
     */
    record FetchedRows(
            List<PreAggregatedRow> rows,
            boolean truncated,
            long byteSize,
            java.time.LocalDateTime asOf,
            long elapsedMillis) {

        public FetchedRows {
            rows = rows == null ? List.of() : List.copyOf(rows);
            if (byteSize < 0) {
                byteSize = 0;
            }
            if (elapsedMillis < 0) {
                elapsedMillis = 0;
            }
        }
    }

    /**
     * 一行源内预聚合结果。
     *
     * @param entityKey 版本化实体键（参与跨源关联）
     * @param amount    该实体键下的预聚合金额
     */
    record PreAggregatedRow(CrossSourceEntityKey entityKey, java.math.BigDecimal amount) {}
}

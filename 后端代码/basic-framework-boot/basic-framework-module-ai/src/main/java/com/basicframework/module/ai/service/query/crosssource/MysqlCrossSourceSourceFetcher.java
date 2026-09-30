package com.basicframework.module.ai.service.query.crosssource;

import com.basicframework.module.ai.adapter.connector.mysql.AiMysqlQueryResultDTO;
import com.basicframework.module.ai.service.query.compiler.AiCompiledQueryExecutor;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * MySQL 跨源取数实现（Y04）：复用 D06 的编译结果与 D03 的只读执行链路。
 *
 * <p>本类**不生成 SQL**。来源侧的预聚合语句由 D06 编译（{@code GROUP BY 实体键 + SUM(金额) + MAX(时间列)}），
 * 这里只做"编译结果 → 只读执行 → 结构化中间结果"的翻译。这样做的收益是：
 * 跨源执行复用 D03 的 SQL 守卫、只读账号、连接池与超时语义，
 * 不会因为"跨源路径自己写一条 SQL"而绕过已经建立的防线。
 *
 * <p>数据时间点取 {@code MAX(时间列)}——**该来源自己的**时间点，而不是执行时刻。
 * 这一列是必需的：没有它，跨源合计就无法被解释为"所有来源都成立的某个时刻"。
 *
 * <p>行数字节数按"每值字符长度"估算而不是精确序列化：这里要回答的是
 * "这次会不会把内存吃光"，一个保守的估算比精确但昂贵的序列化更有用。
 */
@Component
@RequiredArgsConstructor
public class MysqlCrossSourceSourceFetcher implements CrossSourceSourceFetcher {

    /** 单值参与字节估算的字符上限（与 D03 的单值裁剪上限一致）。 */
    private static final int VALUE_CHAR_ESTIMATE = 1_000;

    /** 每个值参与估算的固定字节开销（Map 条目与对象头）。 */
    private static final int VALUE_OVERHEAD_BYTES = 48;

    private final AiCompiledQueryExecutor compiledQueryExecutor;

    @Override
    public FetchedRows fetch(CrossSourceSourceRequest request, CrossSourceBudget budget) {
        requireProbingRowCap(request, budget);
        AiMysqlQueryResultDTO result = compiledQueryExecutor.execute(request.connectorId(), request.preAggregation());
        List<PreAggregatedRow> rows = new ArrayList<>(result.getRowCount());
        LocalDateTime asOf = null;
        for (Map<String, Object> row : result.getRows()) {
            rows.add(new PreAggregatedRow(
                    new CrossSourceEntityKey(
                            String.valueOf(row.get(request.entityKeyColumn())), request.mappingRevision()),
                    toAmount(row.get(request.amountColumn()))));
            LocalDateTime sourceTime = toTime(row.get(request.sourceAsOfColumn().code()));
            // 各分组的数据时间不同源：取最小值才是"这个来源整体只到哪一刻"
            asOf = asOf == null || (sourceTime != null && sourceTime.isBefore(asOf)) ? sourceTime : asOf;
        }
        return new FetchedRows(rows, result.isTruncated(), estimateBytes(result), asOf, result.getElapsedMillis());
    }

    /**
     * 来源的取数行数上限必须**严格大于**预算，否则无法判定它是否超预算。
     *
     * <p>D06 把 {@code LIMIT} 编译进 SQL，MySQL 会在跨源层看到结果之前就把行数截到上限。
     * 如果编译上限等于预算，来源会"刚好"返回预算那么多行、`truncated` 还是 false，
     * 跨源层于是把一份被静默截断的预聚合当成完整结果——这正是本卡最想避免的那类错误。
     * 因此契约是"**多编译一行用于探测**"：上限必须留出探测位，越界交给
     * {@link CrossSourceBudgetAccountant#charge} 拒绝，而不是让来源自己悄悄截断。
     */
    private static void requireProbingRowCap(CrossSourceSourceRequest request, CrossSourceBudget budget) {
        if (request.preAggregation().maxRows() <= budget.maxSourceRows()) {
            throw AiCrossSourceExecutionErrors.resultTooLarge();
        }
    }

    /** 金额取值：数值字符串优先（MySQL DECIMAL 经 D03 已规范为十进制字符串），空值按 0 计。 */
    private static BigDecimal toAmount(Object value) {
        if (value == null) {
            return BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(String.valueOf(value));
        } catch (NumberFormatException notNumeric) {
            // 非数值不是"当成 0"的理由：金额列返回文本说明列选错了，必须显式失败
            throw AiCrossSourceExecutionErrors.sourceFailed();
        }
    }

    /** 时间取值：D03 把 DATETIME 规范化为 ISO 文本；解析不了就是缺数据时间点。 */
    private static LocalDateTime toTime(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return LocalDateTime.parse(String.valueOf(value));
        } catch (RuntimeException notATimestamp) {
            return null;
        }
    }

    /** 字节估算：逐值按长度累加（含固定开销），得到一个偏保守的上界。 */
    private static long estimateBytes(AiMysqlQueryResultDTO result) {
        long bytes = 0;
        for (Map<String, Object> row : result.getRows()) {
            for (Object value : row.values()) {
                int length = value == null ? 0 : String.valueOf(value).length();
                bytes += VALUE_OVERHEAD_BYTES + 2L * Math.min(length, VALUE_CHAR_ESTIMATE);
            }
        }
        return bytes;
    }
}

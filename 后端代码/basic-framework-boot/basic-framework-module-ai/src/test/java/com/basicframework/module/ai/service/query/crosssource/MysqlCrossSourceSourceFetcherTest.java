package com.basicframework.module.ai.service.query.crosssource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.module.ai.adapter.connector.mysql.AiMysqlQueryResultDTO;
import com.basicframework.module.ai.domain.query.CompiledQuery;
import com.basicframework.module.ai.domain.query.SqlParameter;
import com.basicframework.module.ai.enums.AiErrorCodeConstants;
import com.basicframework.module.ai.service.query.compiler.AiCompiledQueryExecutor;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * MySQL 跨源取数的翻译语义（Y04）。
 *
 * <p>两件事要钉住：一是**探测位契约**——来源的取数上限必须严格大于预算，
 * 否则来源会在跨源层看到结果之前就静默截断，而截断后的预聚合 SUM 仍是"合法数字"但偏小；
 * 二是**数据时间点取各分组最小值**——那是"这个来源整体只到哪一刻"。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MysqlCrossSourceSourceFetcherTest {

    private static final LocalDateTime LATE = LocalDateTime.of(2026, 9, 20, 10, 0);
    private static final LocalDateTime EARLY = LocalDateTime.of(2026, 9, 20, 8, 0);

    @Mock
    private AiCompiledQueryExecutor compiledQueryExecutor;

    private MysqlCrossSourceSourceFetcher fetcher;

    @BeforeEach
    void setUp() {
        fetcher = new MysqlCrossSourceSourceFetcher(compiledQueryExecutor);
    }

    @Test
    void refusesASourceWhoseRowCapCannotProveItFitsWithinTheBudget() {
        // 编译上限 == 预算：来源会"刚好"返回预算那么多行且 truncated=false，
        // 跨源层就分不出"刚好装下"与"被静默截断"——这种来源不允许参与汇总
        assertCode(() -> fetcher.fetch(request(10), budget(10)), AiErrorCodeConstants.AI_CROSS_SOURCE_RESULT_TOO_LARGE);
        assertCode(() -> fetcher.fetch(request(5), budget(10)), AiErrorCodeConstants.AI_CROSS_SOURCE_RESULT_TOO_LARGE);
    }

    @Test
    void mapsPreAggregatedRowsAndTakesTheEarliestGroupTimePoint() {
        givenResult(new Map[] {row("C-001", "40.00", LATE), row("C-002", "25.00", EARLY)}, false);

        CrossSourceSourceFetcher.FetchedRows fetched = fetcher.fetch(request(11), budget(10));

        assertThat(fetched.rows()).hasSize(2);
        assertThat(fetched.rows().get(0).entityKey()).isEqualTo(new CrossSourceEntityKey("C-001", 1L));
        assertThat(fetched.rows().get(0).amount()).isEqualByComparingTo("40.00");
        // 各分组时间不同源：取最小值才是"这个来源整体只到哪一刻"
        assertThat(fetched.asOf()).isEqualTo(EARLY);
        assertThat(fetched.truncated()).isFalse();
        assertThat(fetched.byteSize()).isPositive();
    }

    @Test
    void reportsTruncationInsteadOfHidingIt() {
        givenResult(new Map[] {row("C-001", "40.00", LATE)}, true);

        CrossSourceSourceFetcher.FetchedRows fetched = fetcher.fetch(request(11), budget(10));

        assertThat(fetched.truncated()).isTrue();
    }

    @Test
    void treatsNullAmountsAsZeroAndRejectsNonNumericOnes() {
        givenResult(new Map[] {rowWith("C-001", null, LATE)}, false);
        assertThat(fetcher.fetch(request(11), budget(10)).rows().get(0).amount())
                .isEqualByComparingTo(BigDecimal.ZERO);

        givenResult(new Map[] {rowWith("C-001", "not-a-number", LATE)}, false);
        // 非数值不是"当成 0"的理由：金额列返回文本说明列选错了
        assertCode(
                () -> fetcher.fetch(request(11), budget(10)),
                AiErrorCodeConstants.AI_CROSS_SOURCE_SOURCE_FAILED_CONFLICT);
    }

    @Test
    void reportsNoTimePointWhenTheSourceColumnIsUnparseable() {
        givenResult(new Map[] {row("C-001", "40.00", null)}, false);

        CrossSourceSourceFetcher.FetchedRows fetched = fetcher.fetch(request(11), budget(10));

        // 缺数据时间点时必须如实报 null，交给执行层按"无法解释时刻"阻断
        assertThat(fetched.asOf()).isNull();
    }

    // ---- 夹具 ----

    private void givenResult(Map<String, Object>[] rows, boolean truncated) {
        AiMysqlQueryResultDTO result = new AiMysqlQueryResultDTO()
                .setColumns(List.of("customer_name", "net_amount", "source_as_of"))
                .setRows(List.of(rows))
                .setRowCount(rows.length)
                .setTruncated(truncated)
                .setElapsedMillis(15);
        when(compiledQueryExecutor.execute(any(), any())).thenReturn(result);
    }

    private static Map<String, Object> row(String key, String amount, LocalDateTime asOf) {
        return rowWith(key, amount, asOf);
    }

    private static Map<String, Object> rowWith(String key, Object amount, LocalDateTime asOf) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("customer_name", key);
        row.put("net_amount", amount);
        row.put("source_as_of", asOf == null ? "not-a-timestamp" : asOf.toString());
        return row;
    }

    private static CrossSourceBudget budget(int maxSourceRows) {
        return new CrossSourceBudget(maxSourceRows, 1024 * 1024, 2, 10_000, 4 * 1024 * 1024, 300);
    }

    /** 编译上限由 {@code compiledMaxRows} 决定（模拟 D06 把 LIMIT 编译进 SQL）。 */
    private static CrossSourceSourceRequest request(int compiledMaxRows) {
        return new CrossSourceSourceRequest(
                "order",
                "dset_orders",
                1,
                9L,
                new CompiledQuery(
                        "SELECT customer AS customer_name, SUM(amount) AS net_amount, MAX(updated_at) AS source_as_of"
                                + " FROM catalog.orders WHERE customer IN (?) GROUP BY customer LIMIT ?",
                        List.of(SqlParameter.string("C-1"), SqlParameter.number((long) compiledMaxRows)),
                        List.of(new CompiledQuery.ResultColumn("customer_name", "客户", "STRING")),
                        compiledMaxRows,
                        10_000,
                        1L,
                        "plan-1",
                        "catalog.orders"),
                List.of(new CrossSourceEntityKey("C-1", 1L)),
                1L,
                "customer_name",
                "net_amount",
                new CrossSourceSourceRequest.LocalDateTimeColumn("source_as_of"),
                false);
    }

    private static void assertCode(
            Runnable operation, com.basicframework.framework.common.exception.ErrorCode expected) {
        assertThatThrownBy(operation::run)
                .isInstanceOfSatisfying(ServiceException.class, failure -> assertThat(failure.getCode())
                        .isEqualTo(expected.getCode()));
    }
}

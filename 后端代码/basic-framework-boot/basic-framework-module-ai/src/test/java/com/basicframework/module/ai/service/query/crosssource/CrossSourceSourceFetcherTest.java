package com.basicframework.module.ai.service.query.crosssource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.basicframework.module.ai.service.query.crosssource.CrossSourceSourceFetcher.FetchedRows;
import com.basicframework.module.ai.service.query.crosssource.CrossSourceSourceFetcher.PreAggregatedRow;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 跨源取数端口的 {@link FetchedRows} 契约（Y04）。
 *
 * <p>这个 record 是跨源执行器唯一的"来源自述"入口，其中 {@code truncated} 决定上游会不会把它
 * 当成完整结果——端口的 javadoc 明确要求实现方**不得**在截断时丢掉该标记，因为被截断的 SUM
 * 仍是"合法数字但偏小"，是跨源聚合里最容易被当成正确结果的一类错误。
 * 因此归一化规则（null 行集、负数计量）也必须被钉住，不能随实现漂移。
 */
class CrossSourceSourceFetcherTest {

    @Test
    void nullRowsAreNormalizedToAnEmptyList() {
        FetchedRows fetched = new FetchedRows(null, false, 128L, LocalDateTime.of(2026, 3, 1, 8, 0), 12L);

        assertThat(fetched.rows()).isEmpty();
        assertThat(fetched.byteSize()).isEqualTo(128L);
    }

    @Test
    void negativeCountersAreClampedToZeroInsteadOfPropagating() {
        // 负的计量值会让下游"预算已用尽"的判断失效，必须归零而不是透传
        FetchedRows fetched = new FetchedRows(List.of(), false, -1L, LocalDateTime.of(2026, 3, 1, 8, 0), -5L);

        assertThat(fetched.byteSize()).isZero();
        assertThat(fetched.elapsedMillis()).isZero();
    }

    @Test
    void rowListIsDefensivelyCopiedSoImplementersCannotMutateAfterTheFact() {
        List<PreAggregatedRow> mutable = new ArrayList<>();
        mutable.add(row("C-1", "100.00"));
        FetchedRows fetched = new FetchedRows(mutable, false, 64L, LocalDateTime.of(2026, 3, 1, 8, 0), 3L);

        mutable.add(row("C-2", "50.00"));

        // 实现方交出列表后不得再改；否则预算计量与实际参与汇总的行会不一致
        assertThat(fetched.rows()).hasSize(1);
        assertThatThrownBy(() -> fetched.rows().add(row("C-3", "1.00")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void truncatedFlagIsCarriedThroughUntouched() {
        assertThat(new FetchedRows(List.of(), true, 0L, LocalDateTime.of(2026, 3, 1, 8, 0), 1L).truncated())
                .isTrue();
        assertThat(new FetchedRows(List.of(), false, 0L, LocalDateTime.of(2026, 3, 1, 8, 0), 1L).truncated())
                .isFalse();
    }

    private static PreAggregatedRow row(String key, String amount) {
        return new PreAggregatedRow(new CrossSourceEntityKey(key, 1L), new BigDecimal(amount));
    }
}

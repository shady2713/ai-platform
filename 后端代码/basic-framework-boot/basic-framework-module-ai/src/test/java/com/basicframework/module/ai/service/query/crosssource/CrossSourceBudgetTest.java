package com.basicframework.module.ai.service.query.crosssource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.module.ai.enums.AiErrorCodeConstants;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * 跨源预算的形状校验（Y04）。
 *
 * <p>预算的每个字段都有服务端硬顶，越界必须**显式拒绝**而不是静默夹到硬顶——
 * 静默夹断会让调用方以为跑的是它要的预算。因此这里逐条打掉越界分支，
 * 包括"全局上限低于单源上限"这种自相矛盾的配置。
 */
class CrossSourceBudgetTest {

    private static final int OVER = CrossSourceBudget.HARD_MAX_SOURCE_ROWS + 1;

    @Test
    void defaultsAreWithinHardCaps() {
        CrossSourceBudget budget = CrossSourceBudget.defaults();

        assertThat(budget.maxSourceRows()).isEqualTo(CrossSourceBudget.MAX_RESULT_ROWS);
        assertThat(budget.maxSourceBytes()).isEqualTo(CrossSourceBudget.HARD_MAX_SOURCE_BYTES);
        assertThat(budget.maxConcurrentSources()).isEqualTo(4);
        assertThat(budget.sourceTimeout()).isEqualTo(Duration.ofMillis(10_000));
        assertThat(budget.describe()).contains("rows/source=999", "concurrent=4");
    }

    @Test
    void acceptsAValidBudget() {
        CrossSourceBudget budget = new CrossSourceBudget(500, 1_000_000, 2, 5_000, 4_000_000, 60);

        assertThat(budget.maxSourceRows()).isEqualTo(500);
        assertThat(budget.maxSkewSeconds()).isEqualTo(60);
        // 偏移容忍为 0 是合法配置：只允许各源数据时间完全一致
        assertThat(new CrossSourceBudget(1, 1, 1, 1, 1, 0).maxSkewSeconds()).isZero();
    }

    @Test
    void rejectsRowBudgetOutOfRange() {
        assertTooLarge(() -> new CrossSourceBudget(0, 1_000, 1, 1_000, 1_000_000, 60));
        assertTooLarge(() -> new CrossSourceBudget(OVER, 1_000, 1, 1_000, 1_000_000, 60));
    }

    @Test
    void rejectsSourceByteBudgetOutOfRange() {
        assertTooLarge(() -> new CrossSourceBudget(10, 0, 1, 1_000, 1_000_000, 60));
        assertTooLarge(
                () -> new CrossSourceBudget(10, CrossSourceBudget.HARD_MAX_SOURCE_BYTES + 1, 1, 1_000, 1_000_000, 60));
    }

    @Test
    void rejectsConcurrencyAndTimeoutOutOfRange() {
        assertTooLarge(() -> new CrossSourceBudget(10, 1_000, 0, 1_000, 1_000_000, 60));
        assertTooLarge(() -> new CrossSourceBudget(
                10, 1_000, CrossSourceBudget.HARD_MAX_CONCURRENT_SOURCES + 1, 1_000, 1_000_000, 60));
        assertTooLarge(() -> new CrossSourceBudget(10, 1_000, 1, 0, 1_000_000, 60));
        assertTooLarge(() -> new CrossSourceBudget(
                10, 1_000, 1, CrossSourceBudget.HARD_MAX_SOURCE_TIMEOUT_MILLIS + 1, 1_000_000, 60));
    }

    @Test
    void rejectsContradictoryOrOversizedTotalBudget() {
        // 全局上限低于单源上限：单源的合法预算在全局就不合法，配置自相矛盾
        assertTooLarge(() -> new CrossSourceBudget(10, 10_000, 1, 1_000, 1_000, 60));
        assertTooLarge(
                () -> new CrossSourceBudget(10, 1_000, 1, 1_000, CrossSourceBudget.HARD_MAX_TOTAL_BYTES + 1, 60));
    }

    @Test
    void rejectsNegativeOrOversizedSkewTolerance() {
        assertTooLarge(() -> new CrossSourceBudget(10, 1_000, 1, 1_000, 1_000_000, -1));
        assertTooLarge(() ->
                new CrossSourceBudget(10, 1_000, 1, 1_000, 1_000_000, CrossSourceBudget.HARD_MAX_SKEW_SECONDS + 1));
    }

    private static void assertTooLarge(Runnable operation) {
        assertThatThrownBy(operation::run)
                .isInstanceOfSatisfying(ServiceException.class, failure -> assertThat(failure.getCode())
                        .isEqualTo(AiErrorCodeConstants.AI_CROSS_SOURCE_RESULT_TOO_LARGE.getCode()));
    }
}

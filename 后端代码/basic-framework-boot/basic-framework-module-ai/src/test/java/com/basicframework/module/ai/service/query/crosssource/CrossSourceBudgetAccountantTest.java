package com.basicframework.module.ai.service.query.crosssource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.module.ai.enums.AiErrorCodeConstants;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * 跨源预算计量的受控结束语义（Y04 专项一：超过内存/行数预算受控结束）。
 *
 * <p>要钉住的是"受控"而不是"不崩"：越界时抛**稳定错误码**，
 * 而不是静默截断后给出一个偏小的合计，也不是把内存吃光后被 OOM Killer 干掉。
 */
class CrossSourceBudgetAccountantTest {

    private static final long ONE_MB = 1024L * 1024L;

    @Test
    void chargesAndReportsUsageAsEvidence() {
        CrossSourceBudgetAccountant accountant = new CrossSourceBudgetAccountant(budget(10, 1_000, ONE_MB, 2));

        accountant.charge(3, 100);
        accountant.charge(4, 200);

        assertThat(accountant.usedRows()).isEqualTo(7);
        assertThat(accountant.usedBytes()).isEqualTo(300);
        assertThat(accountant.usage().totalRows()).isEqualTo(7);
        assertThat(accountant.usage().totalBytes()).isEqualTo(300);
    }

    @Test
    void refusesWhenSingleSourceExceedsItsRowBudget() {
        CrossSourceBudgetAccountant accountant = new CrossSourceBudgetAccountant(budget(5, 1_000, ONE_MB, 2));

        // 6 行超出单源 5 行：受控结束，而不是截成 5 行后继续汇总
        assertTooLarge(() -> accountant.charge(6, 10));
        // 越界时不记账：金额还没进合计，受控结束后不存在被部分写入的用量
        assertThat(accountant.usedRows()).isZero();
        assertThat(accountant.usedBytes()).isZero();
    }

    @Test
    void refusesWhenSingleSourceExceedsItsByteBudget() {
        CrossSourceBudgetAccountant accountant = new CrossSourceBudgetAccountant(budget(10, 1_000, ONE_MB, 2));

        assertTooLarge(() -> accountant.charge(1, 2_000));
        assertThat(accountant.usedBytes()).isZero();
    }

    @Test
    void refusesWhenAccumulatedBytesExceedTheGlobalBudget() {
        // 全局 4MB、单源 2MB：两个源各 2MB 合法，第三个源的 1MB 越界
        CrossSourceBudgetAccountant accountant = new CrossSourceBudgetAccountant(budget(10, 2 * ONE_MB, 4 * ONE_MB, 4));

        accountant.charge(1, 2 * ONE_MB);
        accountant.charge(1, 2 * ONE_MB);
        assertThat(accountant.usedBytes()).isEqualTo(4 * ONE_MB);

        assertTooLarge(() -> accountant.charge(1, 1));
    }

    @Test
    void refusesNegativeUsageInsteadOfLettingItOffsetTheBudget() {
        CrossSourceBudgetAccountant accountant = new CrossSourceBudgetAccountant(budget(10, 1_000, ONE_MB, 2));

        // 负用量说明计量被破坏；夹成 0 会让"超预算"看起来像"没超"
        assertTooLarge(() -> accountant.charge(-1, 10));
        assertTooLarge(() -> accountant.charge(1, -1));
    }

    @Test
    void tracksConcurrencyPeakAndRefusesBeyondTheCap() {
        CrossSourceBudgetAccountant accountant = new CrossSourceBudgetAccountant(budget(10, 1_000, ONE_MB, 2));

        accountant.enter();
        accountant.enter();
        assertThat(accountant.concurrentPeak()).isEqualTo(2);

        // 第三个并发来源越界：报**并发超限**，不是"行数/内存预算超限"——
        // 并发要去查来源扇出与调度，规模要去查单次取数形状，证据指向完全不同的东西
        assertConcurrencyExceeded(accountant::enter);
        assertThat(accountant.concurrentPeak()).isEqualTo(2);

        accountant.exit();
        accountant.enter();
        assertThat(accountant.concurrentPeak()).isEqualTo(2);
    }

    @Test
    void refusesOneExtraByteBeyondTheGlobalBudget() {
        // 卡在边界上：恰好等于上限放行，多一个字节即受控结束
        CrossSourceBudgetAccountant accountant = new CrossSourceBudgetAccountant(budget(10, 100, 100, 2));

        accountant.charge(1, 100);
        assertThat(accountant.usedBytes()).isEqualTo(100);
        assertTooLarge(() -> accountant.charge(1, 1));
    }

    @Test
    void concurrentChargesCannotBothPassTheLastByte() throws Exception {
        // CAS 循环的意义：多个来源同时看到"还剩 1 字节"时，只有一个能放行。
        // 线程数必须**大于**字节预算，否则"全部放行"也满足不了"必须有被拒的"这个断言。
        CrossSourceBudgetAccountant accountant = new CrossSourceBudgetAccountant(budget(10, 10, 10, 4));
        int threads = 16;
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger refused = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (int index = 0; index < threads; index++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        accountant.charge(1, 1);
                        accepted.incrementAndGet();
                    } catch (ServiceException refusedCharge) {
                        refused.incrementAndGet();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                    return null;
                });
            }
            start.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }
        // 全局 10 字节：恰好 10 个成功、其余被拒，合计绝不会超过预算
        assertThat(accepted.get()).isEqualTo(10);
        assertThat(refused.get()).isEqualTo(threads - 10);
        assertThat(accountant.usedBytes()).isEqualTo(10);
    }

    private static CrossSourceBudget budget(int rows, long sourceBytes, long totalBytes, int concurrency) {
        return new CrossSourceBudget(rows, sourceBytes, concurrency, 1_000, totalBytes, 60);
    }

    private static void assertConcurrencyExceeded(Runnable operation) {
        assertThatThrownBy(operation::run)
                .isInstanceOfSatisfying(ServiceException.class, failure -> assertThat(failure.getCode())
                        .isEqualTo(AiErrorCodeConstants.AI_CROSS_SOURCE_CONCURRENCY_EXCEEDED_CONFLICT.getCode()));
    }

    private static void assertTooLarge(Runnable operation) {
        assertThatThrownBy(operation::run)
                .isInstanceOfSatisfying(ServiceException.class, failure -> assertThat(failure.getCode())
                        .isEqualTo(AiErrorCodeConstants.AI_CROSS_SOURCE_RESULT_TOO_LARGE.getCode()));
    }
}

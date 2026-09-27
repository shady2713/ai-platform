package com.basicframework.server.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.basicframework.module.ai.service.quota.AiQuotaService;
import com.basicframework.module.ai.service.quota.dto.AiQuotaAcquireDTO;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Q07 AT-059 验收（真实 MySQL）：并发配额**不超发**、异常结束**不永久占位**、失败可解释。
 *
 * <p>与 Q02 的既有证据（{@code AiUsageLedgerIT}：同一调用的去重、租约到期回收、释放幂等）互补，
 * 本类补的是 Q02 未覆盖的**真实并发争抢**：多个不同调用同时申请同一应用的名额时，
 * 生效占位数绝不能超过配置上限；以及进程崩溃（租约到期）与释放风暴后的可恢复性。
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AiQuotaConcurrencyAcceptanceIT extends AbstractPersistenceIntegrationTest {

    private static final long APP_ID = 9_300L;

    private static final String PREFIX = "it_q07_quota_";

    /** 配置的并发上限。 */
    private static final int LIMIT = 5;

    /** 争抢名额的并发数（远大于上限，制造真实争抢）。 */
    private static final int WORKERS = 24;

    /** 重复轮数：单轮可能因为时序恰好没有撞上，多轮更接近真实压力。 */
    private static final int ROUNDS = 3;

    @Autowired
    private AiQuotaService quotaService;

    private ExecutorService workers;

    /**
     * 配额按**已注册应用**执行：申请的应用行必须存在，服务才能在该行上加锁串行化"判定 + 占位"
     * （Q07 AT-059 修复；应用不存在时服务退化为全局闸门行）。这里补上夹具，让用例走真实路径。
     */
    @BeforeEach
    void seedApplication() {
        jdbcTemplate.update("DELETE FROM ai_application WHERE id = ?", APP_ID);
        jdbcTemplate.update(
                "INSERT INTO ai_application (id, app_code, name, origins) VALUES (?, ?, ?, ?)",
                APP_ID,
                "it_q07_quota_app",
                "Q07 配额并发验收应用",
                "[]");
    }

    @AfterEach
    void cleanUp() throws InterruptedException {
        if (workers != null) {
            workers.shutdownNow();
            workers.awaitTermination(10, TimeUnit.SECONDS);
            workers = null;
        }
        jdbcTemplate.update("DELETE FROM ai_quota_lease WHERE invocation_id LIKE ?", PREFIX + "%");
        jdbcTemplate.update("DELETE FROM ai_application WHERE id = ?", APP_ID);
    }

    private AiQuotaAcquireDTO request(String suffix) {
        return new AiQuotaAcquireDTO()
                .setApplicationId(APP_ID)
                .setInvocationId(PREFIX + suffix)
                .setLease(Duration.ofMinutes(5))
                .setLimit(LIMIT)
                .setHolderRef("it-q07-node");
    }

    private long activeRows() {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ai_quota_lease WHERE application_id = ? AND state = 'ACTIVE' AND"
                        + " lease_until > NOW()",
                Long.class,
                APP_ID);
    }

    /** AT-059：并发争抢配置上限内的名额时，生效占位数绝不超过上限（不超发）。 */
    @Test
    void concurrentAcquireNeverOversubscribesTheConfiguredLimit() throws Exception {
        workers = Executors.newFixedThreadPool(WORKERS);
        List<String> measurements = new ArrayList<>();
        int worstWinners = 0;
        long worstActive = 0;
        for (int round = 0; round < ROUNDS; round++) {
            jdbcTemplate.update("DELETE FROM ai_quota_lease WHERE invocation_id LIKE ?", PREFIX + "%");
            int roundIndex = round;
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Boolean>> futures = new ArrayList<>();
            for (int index = 0; index < WORKERS; index++) {
                String suffix = "r" + roundIndex + "-w" + index;
                futures.add(workers.submit((Callable<Boolean>) () -> {
                    start.await(10, TimeUnit.SECONDS);
                    return quotaService.acquire(request(suffix));
                }));
            }
            start.countDown();

            int winners = 0;
            for (Future<Boolean> future : futures) {
                winners += future.get(30, TimeUnit.SECONDS) ? 1 : 0;
            }
            long active = quotaService.activeCount(APP_ID);
            long rows = activeRows();
            worstWinners = Math.max(worstWinners, winners);
            worstActive = Math.max(worstActive, active);
            measurements.add(String.format(
                    "round=%d winners=%d activeCount=%d activeRows=%d limit=%d",
                    roundIndex, winners, active, rows, LIMIT));
        }
        System.out.printf("Q07-MEASURE AT-059 concurrentAcquire %s%n", String.join(" | ", measurements));

        assertThat(worstWinners)
                .as("AT-059 不超发：并发争抢下生效名额不得超过上限 [%s]", String.join(" | ", measurements))
                .isLessThanOrEqualTo(LIMIT);
        assertThat(worstActive)
                .as("AT-059 不超发：生效占位数不得超过上限 [%s]", String.join(" | ", measurements))
                .isLessThanOrEqualTo(LIMIT);
        assertThat(worstWinners)
                .as("AT-059 不浪费：上限内的名额应被占满 [%s]", String.join(" | ", measurements))
                .isEqualTo(LIMIT);
    }

    /** AT-059：进程崩溃（占位租约到期）后名额被回收，新请求可以重新占用——不永久占位。 */
    @Test
    void crashedHolderReleasesItsSlotThroughLeaseExpiry() {
        for (int index = 0; index < LIMIT; index++) {
            assertThat(quotaService.acquire(request("crash-" + index))).isTrue();
        }
        assertThat(quotaService.acquire(request("crash-overflow")))
                .as("上限已满：可解释地拒绝")
                .isFalse();

        // 持有者进程崩溃：租约到期即回收（无需清理任务），名额可以重新被占满
        jdbcTemplate.update(
                "UPDATE ai_quota_lease SET lease_until = DATE_SUB(NOW(), INTERVAL 1 SECOND) WHERE application_id = ?",
                APP_ID);
        assertThat(quotaService.activeCount(APP_ID)).isZero();
        for (int index = 0; index < LIMIT; index++) {
            assertThat(quotaService.acquire(request("after-crash-" + index))).isTrue();
        }
        assertThat(quotaService.activeCount(APP_ID)).isEqualTo(LIMIT);
        assertThat(quotaService.acquire(request("after-crash-overflow"))).isFalse();

        // 已被回收的占位续租失败：调用方必须停止工作，而不是"以为还占着名额"
        String reclaimedKey = APP_ID + ":0:" + PREFIX + "crash-0";
        assertThat(quotaService.renew(reclaimedKey, Duration.ofMinutes(1))).isFalse();
        assertThat(quotaService.renew(APP_ID + ":0:" + PREFIX + "after-crash-0", Duration.ofMinutes(1)))
                .isTrue();
    }

    /** AT-059：失败/取消路径的释放是幂等的；释放风暴后不残留"卡住"的名额。 */
    @Test
    void releaseStormIsIdempotentAndLeavesNoStuckSlot() throws Exception {
        for (int index = 0; index < LIMIT; index++) {
            assertThat(quotaService.acquire(request("release-" + index))).isTrue();
        }
        assertThat(quotaService.activeCount(APP_ID)).isEqualTo(LIMIT);

        List<String> keys = new ArrayList<>();
        for (int index = 0; index < LIMIT; index++) {
            keys.add(APP_ID + ":0:" + PREFIX + "release-" + index);
        }
        int workersPerKey = 4;
        workers = Executors.newFixedThreadPool(LIMIT * workersPerKey);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> futures = new ArrayList<>();
        for (String key : keys) {
            for (int copy = 0; copy < workersPerKey; copy++) {
                futures.add(workers.submit((Callable<Boolean>) () -> {
                    start.await(10, TimeUnit.SECONDS);
                    quotaService.release(key);
                    return true;
                }));
            }
        }
        start.countDown();
        for (Future<Boolean> future : futures) {
            assertThat(future.get(30, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(quotaService.activeCount(APP_ID)).as("释放风暴后没有残留占位").isZero();
        assertThat(activeRows()).isZero();
        // 名额可以再次被占用（释放是终态而不是"永久锁死"）
        for (int index = 0; index < LIMIT; index++) {
            assertThat(quotaService.acquire(request("reuse-" + index))).isTrue();
        }
        assertThat(quotaService.activeCount(APP_ID)).isEqualTo(LIMIT);
    }
}

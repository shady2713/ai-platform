package com.basicframework.module.ai.service.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.module.ai.enums.AiErrorCodeConstants;
import java.lang.reflect.Field;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;

/** A04 换票失败节流：窗口内超限 429、成功后清零、不同客户端互不影响、窗口过期后恢复、键数上界兜住。 */
class AiTicketAttemptThrottleTest {

    /**
     * 追踪键上界，对应生产常量 {@code MAX_TRACKED_KEYS}。
     *
     * <p>刻意复制这个值而不把它改成可注入：上界本身是"内存不无界"这条承诺的一部分，
     * 改成可注入就等于让生产默认值不再被任何测试碰到。这里也不做反射改写，直接按真实上界
     * 把表推过去——上界被改小时用例会立刻红（键数对不上），而不是悄悄失去覆盖。
     */
    private static final int TRACKED_KEY_LIMIT = 10_000;

    /** 淘汰频率：只有操作数落在 64 的倍数上时才真正做一次淘汰。 */
    private static final int EVICTION_INTERVAL = 64;

    @Test
    void blocksAfterConfiguredFailuresAndResetsOnSuccess() {
        AiTicketAttemptThrottle throttle = new AiTicketAttemptThrottle(3, Duration.ofMinutes(1));

        for (int i = 0; i < 3; i++) {
            throttle.checkAllowed("1.2.3.4|crm-portal");
            throttle.recordFailure("1.2.3.4|crm-portal");
        }

        assertThatThrownBy(() -> throttle.checkAllowed("1.2.3.4|crm-portal"))
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getCode())
                .isEqualTo(AiErrorCodeConstants.AI_QUOTA_EXCEEDED.getCode());

        // 另一个客户端不受影响
        assertThatCode(() -> throttle.checkAllowed("5.6.7.8|crm-portal")).doesNotThrowAnyException();

        // 成功换票清零计数
        throttle.recordSuccess("1.2.3.4|crm-portal");
        assertThatCode(() -> throttle.checkAllowed("1.2.3.4|crm-portal")).doesNotThrowAnyException();
        assertThat(throttle.trackedKeys()).isZero();
    }

    @Test
    void windowExpiryRestoresAccess() throws InterruptedException {
        // 窗口取 2 秒：窗口内"仍然拦截"的断言不再依赖线程调度抖动（原 50ms 窗口在门禁满载时会被调度延迟吃掉），
        // 过期仍用真实等待验证，语义不变
        AiTicketAttemptThrottle throttle = new AiTicketAttemptThrottle(1, Duration.ofSeconds(2));
        throttle.checkAllowed("ip|app");
        throttle.recordFailure("ip|app");
        assertThatThrownBy(() -> throttle.checkAllowed("ip|app")).isInstanceOf(ServiceException.class);

        Thread.sleep(2_100);
        assertThatCode(() -> throttle.checkAllowed("ip|app")).doesNotThrowAnyException();
    }

    @Test
    void invalidConfigurationFallsBackToSafeDefaults() {
        AiTicketAttemptThrottle throttle = new AiTicketAttemptThrottle(0, Duration.ZERO);
        assertThatCode(() -> throttle.checkAllowed("ip|app")).doesNotThrowAnyException();
        throttle.recordFailure("ip|app");
        assertThat(throttle.trackedKeys()).isEqualTo(1);
    }

    @Test
    void evictsOldestWindowsWhenTrackedKeysPassTheUpperBound() {
        // 阈值取 1：留下的窗口与已淘汰的窗口可以用 checkAllowed 区分开（留下的仍会被拦）
        AiTicketAttemptThrottle throttle = new AiTicketAttemptThrottle(1, Duration.ofMinutes(1));
        int inserts = TRACKED_KEY_LIMIT + EVICTION_INTERVAL;

        for (int index = 0; index < inserts; index++) {
            throttle.recordFailure("ip-" + index);
        }

        // 前 10000 次插入时键数一直低于上界，淘汰不触发；之后每 64 次才筛一次，
        // 触发那一轮把 64 个最旧窗口删到上界之下，再补回当前这一条 → 恰好落在上界。
        assertThat(throttle.trackedKeys()).as("键数必须被兜住在上界上").isEqualTo(TRACKED_KEY_LIMIT);
        assertThatCode(() -> throttle.checkAllowed("ip-0"))
                .as("窗口全都新鲜时第一步删不掉任何一条，必须靠第二步按最旧淘汰")
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> throttle.checkAllowed("ip-" + (inserts - 1)))
                .as("最新的窗口不能被淘汰")
                .isInstanceOf(ServiceException.class);
    }

    @Test
    void stopsEvictingWhenTheOldestWindowWasAlreadyTakenByAnotherThread() throws ReflectiveOperationException {
        AiTicketAttemptThrottle throttle = new AiTicketAttemptThrottle(1, Duration.ofMinutes(1));
        // 换一张"remove 一律回报已被别人删走"的表：并发退出这条分支只能靠真竞态复现，
        // 用多线程 + sleep 拼概率的用例要么常绿、要么偶发红，对门禁都是负价值。
        replaceWindows(throttle, new RacingWindows());
        int inserts = TRACKED_KEY_LIMIT + EVICTION_INTERVAL;

        for (int index = 0; index < inserts; index++) {
            throttle.recordFailure("ip-" + index);
        }

        // 第一次 remove 返回 null 就结束本轮：只少了一条，键数因此仍高于上界。
        // 这就是"避免空转"的含义——剩下的交给下一次触发，不在这一轮里死磕。
        assertThat(throttle.trackedKeys()).isEqualTo(TRACKED_KEY_LIMIT + EVICTION_INTERVAL - 1);
    }

    /** 把窗口表换成测试替身：只用于复现并发分支，其余用例拿到的都是真实的 ConcurrentHashMap。 */
    private static void replaceWindows(AiTicketAttemptThrottle throttle, Map<String, ?> replacement)
            throws ReflectiveOperationException {
        Field field = AiTicketAttemptThrottle.class.getDeclaredField("windows");
        field.setAccessible(true);
        field.set(throttle, replacement);
    }

    /**
     * 真删、但回报 {@code null} 的窗口表：语义上等价于"我挑中的这条刚好被别的线程抢先删走"。
     * 表本身仍是 ConcurrentHashMap，因此遍历与 removeIf 的并发语义与生产一致。
     */
    private static final class RacingWindows extends ConcurrentHashMap<String, Object> {

        @Override
        public Object remove(Object key) {
            super.remove(key);
            return null;
        }
    }
}

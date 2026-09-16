package com.basicframework.module.system.service.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import com.basicframework.module.system.enums.session.UserSessionRevocationReasonEnum;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SecuritySignalMetricsTest {

    private SimpleMeterRegistry registry;

    private SecuritySignalMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new SecuritySignalMetrics(registry);
    }

    @Test
    void counters_startAtZeroSoAlertingRulesCanRelyOnThem() {
        assertThat(registry.get(SecuritySignalMetrics.LOGIN_FAILURES).counter().count())
                .isZero();
        assertThat(registry.get(SecuritySignalMetrics.ACCOUNT_LOCKS).counter().count())
                .isZero();
        assertThat(registry.get(SecuritySignalMetrics.REFRESH_REPLAYS).counter().count())
                .isZero();
    }

    @Test
    void recordLoginFailureAndLock_areCountedSeparately() {
        metrics.recordLoginFailure();
        metrics.recordLoginFailure();
        metrics.recordAccountLock();

        assertThat(registry.get(SecuritySignalMetrics.LOGIN_FAILURES).counter().count())
                .isEqualTo(2.0);
        assertThat(registry.get(SecuritySignalMetrics.ACCOUNT_LOCKS).counter().count())
                .isEqualTo(1.0);
    }

    @Test
    void countersAccumulateAndTagCorrectly() {
        metrics.recordRefreshReplay();
        assertThat(registry.get(SecuritySignalMetrics.REFRESH_REPLAYS).counter().count())
                .isEqualTo(1.0);

        metrics.recordSessionRevocation(UserSessionRevocationReasonEnum.PASSWORD_CHANGED, 3);
        metrics.recordSessionRevocation(UserSessionRevocationReasonEnum.PASSWORD_CHANGED, 2);
        assertThat(registry.get(SecuritySignalMetrics.SESSION_REVOCATIONS)
                        .tag("reason", "PASSWORD_CHANGED")
                        .counter()
                        .count())
                .isEqualTo(5.0);

        // 非法入参不得创建指标，避免告警规则被空标签污染。
        metrics.recordSessionRevocation(null, 1);
        metrics.recordSessionRevocation(UserSessionRevocationReasonEnum.USER_DISABLED, 0);
        assertThat(registry.find(SecuritySignalMetrics.SESSION_REVOCATIONS)
                        .tag("reason", "USER_DISABLED")
                        .counters())
                .isEmpty();
    }
}

package com.basicframework.module.system.service.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.basicframework.module.system.config.LoginProtectionProperties;
import com.basicframework.module.system.dal.redis.auth.LoginAttemptRedisDAO;
import com.basicframework.module.system.dal.redis.auth.LoginAttemptRedisDAO.FailureResult;
import com.basicframework.module.system.service.metrics.SecuritySignalMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class LoginProtectionServiceTest {

    @Mock
    private LoginAttemptRedisDAO loginAttemptRedisDAO;

    private SimpleMeterRegistry meterRegistry;

    private SecuritySignalMetrics securitySignalMetrics;

    private LoginProtectionService loginProtectionService;

    @BeforeEach
    void setUp() {
        LoginProtectionProperties properties = new LoginProtectionProperties();
        meterRegistry = new SimpleMeterRegistry();
        securitySignalMetrics = new SecuritySignalMetrics(meterRegistry);
        loginProtectionService = new LoginProtectionService(loginAttemptRedisDAO, properties, securitySignalMetrics);
    }

    @Test
    void recordFailure_usesValidatedPolicy() {
        when(loginAttemptRedisDAO.recordFailure(1L, 5, Duration.ofMinutes(15))).thenReturn(FailureResult.NEWLY_LOCKED);

        assertThat(loginProtectionService.recordFailure(1L)).isTrue();

        assertThat(meterRegistry
                        .get(SecuritySignalMetrics.LOGIN_FAILURES)
                        .counter()
                        .count())
                .isEqualTo(1.0);
        assertThat(meterRegistry
                        .get(SecuritySignalMetrics.ACCOUNT_LOCKS)
                        .counter()
                        .count())
                .isEqualTo(1.0);
    }

    @Test
    void recordFailure_withoutLockOnlyCountsFailureSignal() {
        when(loginAttemptRedisDAO.recordFailure(1L, 5, Duration.ofMinutes(15))).thenReturn(FailureResult.COUNTED);

        assertThat(loginProtectionService.recordFailure(1L)).isFalse();

        assertThat(meterRegistry
                        .get(SecuritySignalMetrics.LOGIN_FAILURES)
                        .counter()
                        .count())
                .isEqualTo(1.0);
        assertThat(meterRegistry
                        .get(SecuritySignalMetrics.ACCOUNT_LOCKS)
                        .counter()
                        .count())
                .isZero();
    }

    @Test
    void isLocked_delegatesToRedisStore() {
        when(loginAttemptRedisDAO.isLocked(1L)).thenReturn(true);

        assertThat(loginProtectionService.isLocked(1L)).isTrue();
    }

    @Test
    void clear_removesCounterAndLock() {
        loginProtectionService.clear(1L);

        verify(loginAttemptRedisDAO).clear(1L);
    }

    @Test
    void concurrentFailures_countOnlyTheNewLockTransition() {
        when(loginAttemptRedisDAO.recordFailure(1L, 5, Duration.ofMinutes(15)))
                .thenReturn(FailureResult.NEWLY_LOCKED, FailureResult.ALREADY_LOCKED);
        assertThat(loginProtectionService.recordFailure(1L)).isTrue();
        assertThat(loginProtectionService.recordFailure(1L)).isTrue();
        assertThat(meterRegistry
                        .get(SecuritySignalMetrics.ACCOUNT_LOCKS)
                        .counter()
                        .count())
                .isEqualTo(1);
        assertThat(meterRegistry
                        .get(SecuritySignalMetrics.LOGIN_FAILURES)
                        .counter()
                        .count())
                .isEqualTo(2);
    }
}

package com.basicframework.framework.ratelimiter.core.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RRateLimiter;
import org.redisson.api.RateLimiterConfig;
import org.redisson.api.RateType;
import org.redisson.api.RedissonClient;

@ExtendWith(MockitoExtension.class)
class RateLimiterRedisDAOTest {

    @Mock
    private RedissonClient redissonClient;

    @Mock
    private RRateLimiter rateLimiter;

    private RateLimiterRedisDAO redisDAO;

    @BeforeEach
    void setUp() {
        redisDAO = new RateLimiterRedisDAO(redissonClient);
    }

    @Test
    void tryAcquire_missingLimiter_createsMillisecondRateAndExpiry() {
        when(redissonClient.getRateLimiter("rate_limiter:login")).thenReturn(rateLimiter);
        when(rateLimiter.tryAcquire()).thenReturn(true);

        assertThat(redisDAO.tryAcquire("login", 5, 500, TimeUnit.MILLISECONDS)).isTrue();

        verify(rateLimiter).trySetRate(RateType.OVERALL, 5, Duration.ofMillis(500));
        verify(rateLimiter).expire(Duration.ofMillis(500));
    }

    @Test
    void tryAcquire_matchingLimiter_reusesConfiguration() {
        when(redissonClient.getRateLimiter("rate_limiter:login")).thenReturn(rateLimiter);
        when(rateLimiter.getConfig()).thenReturn(new RateLimiterConfig(RateType.OVERALL, 500L, 5L));

        redisDAO.tryAcquire("login", 5, 500, TimeUnit.MILLISECONDS);

        verify(rateLimiter, never()).setRate(RateType.OVERALL, 5, Duration.ofMillis(500));
        verify(rateLimiter, never()).expire(Duration.ofMillis(500));
    }

    @Test
    void tryAcquire_changedLimiter_replacesConfigurationAndExpiry() {
        when(redissonClient.getRateLimiter("rate_limiter:login")).thenReturn(rateLimiter);
        when(rateLimiter.getConfig()).thenReturn(new RateLimiterConfig(RateType.PER_CLIENT, 1000L, 1L));

        redisDAO.tryAcquire("login", 5, 2, TimeUnit.SECONDS);

        verify(rateLimiter).setRate(RateType.OVERALL, 5, Duration.ofSeconds(2));
        verify(rateLimiter).expire(Duration.ofSeconds(2));
    }

    @Test
    void invalidContractValuesAreRejectedBeforeRedisAccess() {
        assertThatThrownBy(() -> redisDAO.tryAcquire(" ", 1, 1, TimeUnit.SECONDS))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Key");
        assertThatThrownBy(() -> redisDAO.tryAcquire("key", 0, 1, TimeUnit.SECONDS))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("次数");
        assertThatThrownBy(() -> redisDAO.tryAcquire("key", 1, 0, TimeUnit.SECONDS))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("时间");
        assertThatThrownBy(() -> redisDAO.tryAcquire("key", 1, 1, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("单位");
        assertThatThrownBy(() -> redisDAO.tryAcquire("key", 1, 1, TimeUnit.NANOSECONDS))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1 毫秒");
        verify(redissonClient, never()).getRateLimiter("rate_limiter:key");
    }
}

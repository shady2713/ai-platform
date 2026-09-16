package com.basicframework.framework.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.basicframework.framework.idempotent.config.BasicFrameworkIdempotentConfiguration;
import com.basicframework.framework.idempotent.core.keyresolver.IdempotentKeyResolver;
import com.basicframework.framework.idempotent.core.redis.IdempotentRedisDAO;
import com.basicframework.framework.lock4j.config.BasicFrameworkLock4jConfiguration;
import com.basicframework.framework.ratelimiter.config.BasicFrameworkRateLimiterConfiguration;
import com.basicframework.framework.ratelimiter.core.keyresolver.RateLimiterKeyResolver;
import com.basicframework.framework.ratelimiter.core.redis.RateLimiterRedisDAO;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;

class ProtectionAutoConfigurationTest {

    @Test
    void idempotentConfiguration_exposesCompleteCapabilitySet() {
        BasicFrameworkIdempotentConfiguration configuration = new BasicFrameworkIdempotentConfiguration();
        IdempotentRedisDAO redisDAO = configuration.idempotentRedisDAO(mock(StringRedisTemplate.class));
        List<IdempotentKeyResolver> resolvers = List.of(
                configuration.defaultIdempotentKeyResolver(),
                configuration.userIdempotentKeyResolver(),
                configuration.expressionIdempotentKeyResolver());

        assertThat(configuration.idempotentAspect(resolvers, redisDAO)).isNotNull();
        assertThat(resolvers).extracting(Object::getClass).doesNotHaveDuplicates();
    }

    @Test
    void rateLimiterConfiguration_exposesCompleteCapabilitySet() {
        BasicFrameworkRateLimiterConfiguration configuration = new BasicFrameworkRateLimiterConfiguration();
        RateLimiterRedisDAO redisDAO = configuration.rateLimiterRedisDAO(mock(RedissonClient.class));
        List<RateLimiterKeyResolver> resolvers = List.of(
                configuration.defaultRateLimiterKeyResolver(),
                configuration.userRateLimiterKeyResolver(),
                configuration.clientIpRateLimiterKeyResolver(),
                configuration.serverNodeRateLimiterKeyResolver(),
                configuration.expressionRateLimiterKeyResolver());

        assertThat(configuration.rateLimiterAspect(resolvers, redisDAO)).isNotNull();
        assertThat(resolvers).extracting(Object::getClass).doesNotHaveDuplicates();
    }

    @Test
    void lockConfiguration_exposesFailureStrategy() {
        assertThat(new BasicFrameworkLock4jConfiguration().lockFailureStrategy())
                .isNotNull();
    }
}

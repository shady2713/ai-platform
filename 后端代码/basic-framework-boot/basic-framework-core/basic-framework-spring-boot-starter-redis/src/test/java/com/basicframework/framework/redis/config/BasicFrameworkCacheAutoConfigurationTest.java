package com.basicframework.framework.redis.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.cache.CacheProperties;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;

class BasicFrameworkCacheAutoConfigurationTest {

    private final BasicFrameworkCacheAutoConfiguration configuration = new BasicFrameworkCacheAutoConfiguration();

    @Test
    void redisCacheConfiguration_appliesPrefixTtlAndNullPolicy() {
        CacheProperties properties = new CacheProperties();
        properties.getRedis().setKeyPrefix("service");
        properties.getRedis().setTimeToLive(Duration.ofMinutes(15));
        properties.getRedis().setCacheNullValues(false);

        RedisCacheConfiguration cacheConfiguration = configuration.redisCacheConfiguration(properties);

        assertThat(cacheConfiguration.getKeyPrefixFor("users")).isEqualTo("service:users:");
        assertThat(cacheConfiguration.getTtl()).isEqualTo(Duration.ofMinutes(15));
        assertThat(cacheConfiguration.getAllowCacheNullValues()).isFalse();
    }

    @Test
    void redisCacheConfiguration_canDisableKeyPrefix() {
        CacheProperties properties = new CacheProperties();
        properties.getRedis().setUseKeyPrefix(false);

        RedisCacheConfiguration cacheConfiguration = configuration.redisCacheConfiguration(properties);

        assertThat(cacheConfiguration.usePrefix()).isFalse();
    }

    @Test
    @SuppressWarnings("unchecked")
    void redisCacheManager_usesConfiguredConnectionFactory() {
        RedisConnectionFactory connectionFactory = mock(RedisConnectionFactory.class);
        RedisTemplate<String, Object> redisTemplate = mock(RedisTemplate.class);
        when(redisTemplate.getConnectionFactory()).thenReturn(connectionFactory);

        RedisCacheManager cacheManager = configuration.redisCacheManager(
                redisTemplate, RedisCacheConfiguration.defaultCacheConfig(), new BasicFrameworkCacheProperties());

        assertThat(cacheManager).isNotNull();
    }
}

package com.basicframework.framework.redis.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.cache.Cache;
import org.springframework.data.redis.cache.RedisCache;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheWriter;

class TimeoutRedisCacheManagerTest {

    @Test
    void parseDuration_supportsDocumentedUnitsAndDefaultSeconds() {
        assertThat(TimeoutRedisCacheManager.parseDuration("2d")).isEqualTo(Duration.ofDays(2));
        assertThat(TimeoutRedisCacheManager.parseDuration("3h")).isEqualTo(Duration.ofHours(3));
        assertThat(TimeoutRedisCacheManager.parseDuration("4m")).isEqualTo(Duration.ofMinutes(4));
        assertThat(TimeoutRedisCacheManager.parseDuration("5s")).isEqualTo(Duration.ofSeconds(5));
        assertThat(TimeoutRedisCacheManager.parseDuration("6")).isEqualTo(Duration.ofSeconds(6));
    }

    @Test
    void parseDuration_rejectsNonPositiveMalformedAndOverflowValues() {
        for (String invalid : new String[] {null, "", "0", "-1", "1x", " 1s", "9223372036854775808"}) {
            assertThatThrownBy(() -> TimeoutRedisCacheManager.parseDuration(invalid))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("缓存 TTL");
        }
    }

    @Test
    void getCache_appliesTtlAndRemovesTtlSuffixFromRedisName() {
        TimeoutRedisCacheManager manager = manager();

        Cache cache = manager.getCache("users#5m");

        assertThat(cache).isInstanceOf(RedisCache.class);
        RedisCache redisCache = (RedisCache) cache;
        assertThat(redisCache.getName()).isEqualTo("users");
        assertThat(redisCache.getCacheConfiguration().getTtl()).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    void getCache_keepsNamesWithoutExactlyOneTtlSeparator() {
        TimeoutRedisCacheManager manager = manager();

        assertThat(manager.getCache("users")).extracting(Cache::getName).isEqualTo("users");
        assertThat(manager.getCache("users#5m#extra"))
                .extracting(Cache::getName)
                .isEqualTo("users#5m#extra");
    }

    private static TimeoutRedisCacheManager manager() {
        return new TimeoutRedisCacheManager(mock(RedisCacheWriter.class), RedisCacheConfiguration.defaultCacheConfig());
    }
}
